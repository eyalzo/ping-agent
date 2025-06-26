package com.eyalzo.pingagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Wakes up every interval and download.
 * 
 * @author Eyal Zohar
 */
public class DownloadThread extends LoopThread {
	/**
	 * Locks both pending and active lists of downloads.
	 */
	private ReentrantLock listsLock = new ReentrantLock();
	private DownloadList pendingDownloadList;
	private DownloadList activeDownloadList;
	private DownloadList completedDownloadList;
	private static JsonNodeFactory factory = JsonNodeFactory.instance;
	private static final int LOOP_MILLIS_IF_NO_DOWNLOADS = 1000;
	private final String reportUrlBase;
	private final Config config;

	//
	// Statistics to be displayed in json
	//
	private String statLastLoopLog = "";
	/**
	 * All the active downloads, whether in queue, in progress or completed.
	 */
	private ObjectNode resultsJson = factory.objectNode();
	private String statReportResponse = "";
	/**
	 * Size of list of addresses to download on the last loop.
	 */
	private int statDownloadsToPerform;

	/**
	 * @param reportUrlBase
	 *            The URL where download results should be reported to.
	 */
	public DownloadThread(final String reportUrlBase, Config config) {
		super("Download", config.getDownloadIntervalSec() * 1000);
		this.reportUrlBase = reportUrlBase;
		this.config = config;
	}

	/**
	 * @param downloadList
	 * @return True if the list is active now and wake-up was called, or false if it was set as pending.
	 */
	public boolean setDownloadList(DownloadList downloadList) {
		listsLock.lock();
		try {
			if (activeDownloadList == null || activeDownloadList.size() == 0) {
				activeDownloadList = downloadList;
				pendingDownloadList = null;
				this.wakeup();
				return true;
			} else {
				if (downloadList != null && downloadList.size() > 0)
					pendingDownloadList = downloadList;
				return false;
			}
		} finally {
			listsLock.unlock();
		}
	}

	@Override
	public boolean runLoop() {
		// Set the interval according to configuration
		setIntervalOnce(config.getDownloadIntervalSec() * 1000);

		statLastLoopLog = "Loop start";
		statReportResponse = "";
		// All the active downloads, whether in queue, in progress or completed
		synchronized (resultsJson) {
			resultsJson.removeAll();
		}
		statLastLoopLog = "Results cleanup done";

		// Check if there is an active list, while locking because of the wake-up
		listsLock.lock();
		try {
			statDownloadsToPerform = (activeDownloadList == null) ? 0 : activeDownloadList.size();
		} finally {
			listsLock.unlock();
		}

		// Skip the rest if there is nothing to download
		if (statDownloadsToPerform == 0) {
			statLastLoopLog = "Nothing to download. Check hostnames result.";
			// Set a shorter loop time, to try again soon
			setIntervalOnce(LOOP_MILLIS_IF_NO_DOWNLOADS);
			return true;
		}

		// Do the download
		statLastLoopLog = "Do the downloads";
		DownloadMeasure.download(activeDownloadList, config.getDownloadExecuters(), config.getDownloadTimeoutMs(),
				config.getDownloadIntervalSec() * 1000);
		statLastLoopLog = "Completed the download executers";

		listsLock.lock();
		try {
			completedDownloadList = activeDownloadList;
			activeDownloadList = pendingDownloadList;
			pendingDownloadList = null;
		} finally {
			listsLock.unlock();
		}

		// Analyze the download results and get a json with summary and detailed results
		ObjectNode analyzedDownloadResultsJson = completedDownloadList.analyzeResults();
		synchronized (resultsJson) {
			resultsJson.setAll(analyzedDownloadResultsJson);
		}

		// Report results to database
		boolean success = false;
		try {
			success = reportToServer(analyzedDownloadResultsJson);
		} catch (IOException e) {
			statLastLoopLog = "IOException when trying to report the server.";
			e.printStackTrace();
		}

		statLastLoopLog = success ? "Reported to server. Loop complete" : "Failed to report to server. Loop complete";

		return success;
	}

	/**
	 * Send report to server.
	 * <p>
	 * For example:
	 * 
	 * <pre>
	 {
	 	"items": [
	 		{
	 			"ip_port":"3.24.138.198:5001",
	 			"region":"aws\\ap-southeast-2",
	 			"queue_ms":3,
	 			"connect_us":444293,
	 			"download_us":1277630,
	 			"size":20000
	 		}
	 	],
	 	"download_failed":0,
	 	"download_success":1
	 }
	 * </pre>
	 * 
	 * @param analyzedDownloadResultsJson
	 * @return True on success to report.
	 * @throws IOException
	 */
	private boolean reportToServer(ObjectNode analyzedDownloadResultsJson) throws IOException {
		if (analyzedDownloadResultsJson == null)
			return false;

		System.out.println(analyzedDownloadResultsJson.toString());

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
			nvps.add(new BasicNameValuePair("result", analyzedDownloadResultsJson.toString()));
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
		result.put("download_executers", config.getDownloadExecuters());
		result.put("downloads_to_perform", statDownloadsToPerform);
		result.put("server_report_url", this.reportUrlBase);
		result.put("server_response", statReportResponse);
		result.set("download_results", resultsJson.deepCopy());

		return result;
	}

	public ObjectNode getStatisticsAsJsonMinimal() {
		ObjectNode result = this.getStatisticsAsJson();
		JsonNode downloadResults = result.get("download_results");
		if (downloadResults instanceof ObjectNode)
			((ObjectNode) downloadResults).remove("items");

		return result;
	}
}
