package com.eyalzo.pingagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.LinkedList;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Wakes up every interval and performs an announce to the VGN server.
 * <p>
 * The result contains the following:
 * <ol>
 * <li>Addresses of other agents to ping.
 * <li>Addresses of other agents to download content from.
 * <li>Configuration updates related to announce, ping, download etc.
 * </ol>
 * Upon the reception of the response, the mentioned items are being pushed to the relevant threads and the configuration.
 * 
 * @author Eyal Zohar
 */
public class AnnounceThread extends LoopThread {
	private static final ObjectMapper objectMapper = new ObjectMapper();
	private static final int LOOP_SECONDS = 5 * 60;
	private final long startTime = System.currentTimeMillis();
	private final String announceUrlBase;
	/**
	 * The last address list received from the server. It always contains a complete filled list (except for the null on start), so there is no need to lock
	 * anything. Informative only.
	 */
	private AnnounceReceivedList addrLastReceivedList;
	private ReentrantLock lock = new ReentrantLock();
	private final Config config;
	private String statLastUrl;
	private int statLastResponseLinesCount = 0;
	private int statLastResponseRegionsCount = 0;
	private String statLastResponseParseError = "";
	private final PingThread pingThread;
	private final DownloadThread downloadThread;
	private boolean statLastListSetAsActive;

	/**
	 * @param announceUrlBase
	 *            Base URL for the announce. Can add more HTTP parameters later.
	 * @param addrToPingList
	 *            Addresses to ping. Empty at first, and filled up and cleaned by this announce thread.
	 */
	public AnnounceThread(final String announceUrlBase, PingThread pingThread, DownloadThread downloadThread,
			Config config) {
		super("Announce", LOOP_SECONDS * 1000);
		this.announceUrlBase = announceUrlBase;
		this.pingThread = pingThread;
		this.downloadThread = downloadThread;
		this.config = config;
	}

	@Override
	public boolean runLoop() {
		// Reset statistics
		statLastResponseLinesCount = 0;
		statLastResponseRegionsCount = 0;

		statLastUrl = this.announceUrlBase + "&announce_count=" + this.getLoopsCount() + "&runtime_sec="
				+ (System.currentTimeMillis() - this.startTime) / 1000;
		LinkedList<String> textLines = HttpUtils.readUrlTextLines(statLastUrl, null);
		if (textLines == null || textLines.isEmpty()) {
			return false;
		}
		// Count lines (for debug)
		statLastResponseLinesCount = textLines.size();

		//
		// Parse the response
		//
		StringBuffer buffer = new StringBuffer();
		for (String curLine : textLines) {
			buffer.append(curLine);
		}
		JsonNode readTree = null;
		try {
			readTree = objectMapper.readTree(buffer.toString());
		} catch (IOException e) {
			statLastResponseParseError = e.getMessage();
		}

		// Update config
		config.setFromJson(readTree.path("agent_configuration"));

		JsonNode clientsNode = readTree.get("clients_to_ping");
		if (clientsNode == null) {
			statLastResponseParseError = "No clients node";
			return false;
		}

		statLastResponseRegionsCount = clientsNode.size();
		statLastResponseParseError = "";

		// Now add the json addresses to the list
		AnnounceReceivedList receivedAddrList = new AnnounceReceivedList(clientsNode);

		// Set the ping thread's addresses for next time it pings
		pingThread.setAddressesToPing(receivedAddrList.getAddresses());

		// Set the ping thread's addresses for next time it pings
		statLastListSetAsActive = downloadThread.setDownloadList(receivedAddrList.getDownloadsAsNewDownloadList("/download?size="));

		// Only now we can safely use it, because until now it could be in use
		lock.lock();
		try {
			addrLastReceivedList = receivedAddrList;
		} finally {
			lock.unlock();
		}

		// Update loop interval by config that might have been changed
		if (config.getAnnounceIntervalSec() > 0) {
			long intervalMillis = config.getAnnounceIntervalSec() * 1000;
			// From next on
			this.loopIntervalMillis = intervalMillis;
			// For this round
			setIntervalOnce(intervalMillis);
		}

		return true;
	}

	@Override
	public boolean runFirstTime() {
		return runLoop();
	}

	@Override
	public ObjectNode getStatisticsAsJson() {
		return getStatisticsAsJson(true);
	}

	public ObjectNode getStatisticsAsJson(boolean withAddresses) {
		ObjectNode result = super.getStatisticsAsJson();
		ObjectNode node = result.putObject("server_response");
		node.put("url", statLastUrl);
		node.put("lines_count", statLastResponseLinesCount);
		node.put("parse_error", statLastResponseParseError);
		node.put("regions_count", statLastResponseRegionsCount);
		node.put("last_addr_list_set_as_active", statLastListSetAsActive);
		lock.lock();
		try {
			if (addrLastReceivedList != null) {
				node.put("addresses_count_in_last_response", addrLastReceivedList.size());
				node.put("downloads_count_in_last_response", addrLastReceivedList.getDownloadsCount());
				if (withAddresses) {
					node.set("addresses_in_last_response", addrLastReceivedList.getAddressesAsJson());
				}
			}
		} finally {
			lock.unlock();
		}

		return result;
	}
}
