package com.eyalzo.pingagent;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map.Entry;

/**
 * List of download results that are actually candidates and/or results in any of several different states.
 */
public class DownloadList {
	private HashMap<URL, DownloadResult> list = new HashMap<URL, DownloadResult>();
	private static JsonNodeFactory factory = JsonNodeFactory.instance;

	public int add(AnnounceReceivedItem curItem, String downloadCommand) {
		HashMap<Integer, Integer> downloads = curItem.getDownloads();
		// Skip if no downloads
		if (downloads == null)
			return 0;
		int result = 0;
		// Add all downloads of this agent
		for (Entry<Integer, Integer> curDownload : downloads.entrySet()) {
			int size = curDownload.getKey();
			URL curUrl;
			try {
				curUrl = new URL("http://" + curItem.getIpAndPort().toString().substring(1) + downloadCommand + size);
			} catch (MalformedURLException e) {
				continue;
			}
			// With URL and size add to the result
			DownloadResult downloadResult = new DownloadResult(curUrl, curItem.getIpAndPort(), curItem.getRegionName(),
					size);
			list.put(curUrl, downloadResult);
			result++;
		}
		return result;
	}

	public int size() {
		return list.size();
	}

	public boolean isEmpty() {
		return list.isEmpty();
	}

	public Collection<DownloadResult> getDownloadResults() {
		return list.values();
	}

	public ObjectNode analyzeResults() {
		ObjectNode result = factory.objectNode();

		// Prepare an array node, to hold each result separately
		ArrayNode itemsArray = result.arrayNode(list.size());
		result.set("items", itemsArray);

		int downloadFailed = 0;
		int downloadSuccess = 0;

		for (DownloadResult curResult : list.values()) {
			//
			// Host, port and the json node in the array
			//
			ObjectNode curNode = itemsArray.addObject();
			curNode.put("ip_port", curResult.getAddressAsString());
			curNode.put("region", curResult.regionName);

			curNode.put("queue_ms", curResult.getQueueMillis());

			// Check if connected so RTT is know
			int connectMicro = curResult.getConnectMicro();
			if (connectMicro > 0)
				curNode.put("connect_us", connectMicro);

			int downloadWithoutConnectMicro = curResult.getDownloadWithoutConnectMicro();

			// Check if error
			if (downloadWithoutConnectMicro == 0 || curResult.exception != null) {
				curNode.put("error", curResult.exception == null ? "No download" : curResult.exception.getMessage());
				downloadFailed++;
				continue;
			}

			downloadSuccess++;

			curNode.put("download_us", connectMicro + downloadWithoutConnectMicro);
			curNode.put("size", curResult.sizeDownloaded);
		}

		// Add statistics
		result.put("download_failed", downloadFailed);
		result.put("download_success", downloadSuccess);

		return result;
	}

}
