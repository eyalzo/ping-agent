package com.eyalzo.pingagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.eyalzo.pingagent.PingMeasure.PingResult;
import org.apache.http.HttpEntity;
import org.apache.http.NameValuePair;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.*;

/**
 * Wakes up every interval and ping.
 * 
 * @author Eyal Zohar
 */
public class PingThread extends LoopThread {
	private static JsonNodeFactory factory = JsonNodeFactory.instance;
	private static final int LOOP_SECONDS_IF_NO_PINGS = 5;
	private final String reportUrlBase;
	private final Config config;

	//
	// Statistics to be displayed in json
	//
	private String statLastLoopLog = "";
	private ObjectNode statPingResult = factory.objectNode();
	private String statReportResponse = "";
	/**
	 * Size of list of addresses to ping on the last loop. This is the list in use, not a pending list.
	 */
	private int statAddressesToPing;
	/**
	 * Last time the addresses to ping were updated as pending.
	 */
	private long statAddressesToPingLastUpdateTimePending;
	private static final long ADDRESSES_TTL_MILLIS = 10 * 60 * 1000;
	/**
	 * Last time the addresses to ping were updated as in-use (after pending for a while).
	 */
	private long statAddressesToPingLastUpdateTimeUsed;
	/**
	 * The IP and port of all targets, as received from the announce thread.
	 */
	private HashSet<InetSocketAddress> addressesToPing = new HashSet<>();

	/**
	 * @param reportUrlBase
	 *            The URL where ping results should be reported to.
	 */
	public PingThread(final String reportUrlBase, Config config) {
		super("Ping", config.getPingIntervalSec() * 1000);
		this.reportUrlBase = reportUrlBase;
		this.config = config;
	}

	/**
	 * Set the addresses for next loop. To be called by {@link AnnounceThread} after it received addresses.
	 * 
	 * @param addressesToPing
	 */
	public void setAddressesToPing(Set<InetSocketAddress> addressesToPing) {
		synchronized (this.addressesToPing) {
			this.addressesToPing.clear();
			this.addressesToPing.addAll(addressesToPing);
			// Must remember when the list was last modified by announce - not to use old list
			statAddressesToPingLastUpdateTimePending = System.currentTimeMillis();
		}
	}

	@Override
	public boolean runLoop() {
		// Set the interval according to configuration
		setIntervalOnce(config.getPingIntervalSec() * 1000);

		statLastLoopLog = "Loop start";
		statReportResponse = "";
		synchronized (statPingResult) {
			statPingResult.removeAll();
		}
		statLastLoopLog = "Results cleanup done";

		// Get the addresses to ping in this round, by copying from the pending list
		Set<InetSocketAddress> loopAddressesToPing;
		synchronized (this.addressesToPing) {
			// Check if addresses are not too old
			if (statAddressesToPingLastUpdateTimePending < (System.currentTimeMillis() - ADDRESSES_TTL_MILLIS)) {
				if (statAddressesToPingLastUpdateTimePending <= 0)
					statLastLoopLog = "Nothing to ping. Did not get addresses even once.";
				else
					statLastLoopLog = "Nothing to ping. Addresses too old. No update since "
							+ statAddressesToPingLastUpdateTimePending + " ("
							+ ((System.currentTimeMillis() - statAddressesToPingLastUpdateTimePending) / 1000 / 60)
							+ "min ago).";
				// Set a shorter loop time, to try again soon
				setIntervalOnce(LOOP_SECONDS_IF_NO_PINGS * 1000);
				return true;
			}
			loopAddressesToPing = new HashSet<InetSocketAddress>(this.addressesToPing);
		}
		statAddressesToPingLastUpdateTimeUsed = System.currentTimeMillis();
		statAddressesToPing = loopAddressesToPing == null ? 0 : loopAddressesToPing.size();
		statLastLoopLog = "Got addresses to ping";

		// Skip the rest if there is nothing to ping
		if (statAddressesToPing == 0) {
			statLastLoopLog = "Nothing to ping. Check hostnames result.";
			// Set a shorter loop time, to try again soon
			setIntervalOnce(LOOP_SECONDS_IF_NO_PINGS * 1000);
			return true;
		}

		// Do the ping
		statLastLoopLog = "Do the pings";
		Map<InetSocketAddress, PingResult> pingResults = PingMeasure.pingHosts(loopAddressesToPing,
				config.getPingExecuters(), config.getPingTimeoutMs(), config.getPingIntervalSec() * 1000);
		statLastLoopLog = "Completed the pings executers";

		// Analyze the ping results and get a json with summary and detailed results
		ObjectNode analyzedPingResultsJson = analyzePingResults(pingResults);
		synchronized (statPingResult) {
			statPingResult.setAll(analyzedPingResultsJson);
		}

		// Report results to database
		boolean success = false;
		try {
			success = reportToServer(analyzedPingResultsJson);
		} catch (IOException e) {
			statLastLoopLog = "IOException when trying to report the server.";
			e.printStackTrace();
		}

		statLastLoopLog = success ? "Reported to server. Loop complete" : "Failed to report to server. Loop complete";

		return success;
	}

	private ObjectNode analyzePingResults(Map<InetSocketAddress, PingResult> pingHosts) {
		ObjectNode result = factory.objectNode();

		// Prepare an array node, to hold each result separately
		ArrayNode itemsArray = result.arrayNode(pingHosts.size());
		result.set("items", itemsArray);

		int pingFailed = 0;
		int pingSuccess = 0;

		for (Map.Entry<InetSocketAddress, PingResult> entry : pingHosts.entrySet()) {
			InetSocketAddress curAddrAndPort = entry.getKey();

			//
			// Host, port and the json node in the array
			//
			ObjectNode curNode = itemsArray.addObject();
			curNode.put("ip", curAddrAndPort.getAddress().toString().split("/")[1]);
			// Port is optional
			int curPort = curAddrAndPort.getPort();
			curNode.put("port", curPort);

			// Get ping results
			PingResult curPingResult = entry.getValue();
			if (curPingResult == null) {
				continue;
			}

			curNode.put("queue_ms", curPingResult.millisInQueue);

			// If managed to ping, then show the result
			if (curPingResult.exception == null) {
				curNode.put("rtt_us", curPingResult.rttMicro);
				pingSuccess++;
			} else {
				pingFailed++;
				curNode.put("error", curPingResult.exception.getMessage());
				curNode.put("timeout_us", curPingResult.rttMicro);
			}
		}

		// Add statistics
		result.put("ping_failed", pingFailed);
		result.put("ping_success", pingSuccess);

		return result;
	}

	private boolean reportToServer(ObjectNode analyzedPingResultsJson) throws IOException {
		if (analyzedPingResultsJson == null)
			return false;

		CloseableHttpClient httpclient = HttpClients.createDefault();
		try {
			HttpPost httpPost = new HttpPost(this.reportUrlBase);

			// Set timeouts
			RequestConfig.Builder requestConfig = RequestConfig.custom();
			requestConfig.setConnectTimeout(10 * 1000);
			requestConfig.setConnectionRequestTimeout(10 * 1000);
			requestConfig.setSocketTimeout(20 * 1000);
			httpPost.setConfig(requestConfig.build());

			// Fill the POST data
			List<NameValuePair> nvps = new ArrayList<>(1);
			nvps.add(new BasicNameValuePair("result", analyzedPingResultsJson.toString()));
			httpPost.setEntity(new UrlEncodedFormEntity(nvps));

			// Submit
			statLastLoopLog = "Report to server - connecting.";
			CloseableHttpResponse response2 = httpclient.execute(httpPost);
			try {
				HttpEntity entity2 = response2.getEntity();
				statReportResponse = EntityUtils.toString(entity2, "UTF-8");
				// Do something useful with the response body and ensure it is fully
				// consumed
				EntityUtils.consume(entity2);
			} finally {
				response2.close();
			}
		} finally {
			httpclient.close();
		}

		return true;
	}

	@Override
	public boolean runFirstTime() {
		return runLoop();
	}

	@Override
	public ObjectNode getStatisticsAsJson() {
		ObjectNode result = super.getStatisticsAsJson();
		result.put("last_loop_log", statLastLoopLog);
		result.put("ping_executers", config.getPingExecuters());
		result.put("addresses_to_ping", statAddressesToPing);
		result.put("addresses_to_ping", statAddressesToPing);
		LocalConfig.addTimeNodes(result, "addresses_modified_by_announce", statAddressesToPingLastUpdateTimePending);
		LocalConfig.addTimeNodes(result, "addresses_modified_and_used", statAddressesToPingLastUpdateTimeUsed);
		result.put("server_report_url", this.reportUrlBase);
		result.put("server_response", statReportResponse);
		result.set("ping_results", statPingResult.deepCopy());

		return result;
	}

	public ObjectNode getStatisticsAsJsonMinimal() {
		ObjectNode result = this.getStatisticsAsJson();
		JsonNode pingResults = result.get("ping_results");
		if (pingResults instanceof ObjectNode)
			((ObjectNode) pingResults).remove("items");

		return result;
	}
}
