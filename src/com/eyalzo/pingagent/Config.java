package com.eyalzo.pingagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class Config {
	private static JsonNodeFactory factory = JsonNodeFactory.instance;

	/**
	 * How long to wait for a single ping to complete.
	 */
	private static final int DEFAULT_PING_TIMEOUT_MS = 1000;
	private static final int DEFAULT_DOWNLOAD_TIMEOUT_MS = 5000;
	private static final int DEFAULT_PING_INTERVAL_SEC = 59;
	private static final int DEFAULT_DOWNLOAD_INTERVAL_SEC = 59;
	private static final int DEFAULT_PING_EXECUTERS = 20;
	private static final int DEFAULT_DOWNLOAD_EXECUTERS = 10;
	private static final int DEFAULT_ANNOUNCE_INTERVAL_SEC = 58;
	private int pingTimeoutMs;
	private int downloadTimeoutMs;
	private int pingIntervalSec;
	private int downloadIntervalSec;
	private int downloadExecuters;
	private int pingExecuters;
	private int announceIntervalSec;

	public synchronized int getPingTimeoutMs() {
		return pingTimeoutMs > 0 ? pingTimeoutMs : DEFAULT_PING_TIMEOUT_MS;
	}

	public synchronized int getDownloadTimeoutMs() {
		return downloadTimeoutMs > 0 ? downloadTimeoutMs : DEFAULT_DOWNLOAD_TIMEOUT_MS;
	}

	public synchronized int getPingIntervalSec() {
		return pingIntervalSec > 0 ? pingIntervalSec : DEFAULT_PING_INTERVAL_SEC;
	}

	public synchronized int getDownloadIntervalSec() {
		return downloadIntervalSec > 0 ? downloadIntervalSec : DEFAULT_DOWNLOAD_INTERVAL_SEC;
	}

	public synchronized int getDownloadExecuters() {
		return downloadExecuters > 0 ? downloadExecuters : DEFAULT_DOWNLOAD_EXECUTERS;
	}

	public synchronized int getPingExecuters() {
		return pingExecuters > 0 ? pingExecuters : DEFAULT_PING_EXECUTERS;
	}

	public synchronized int getAnnounceIntervalSec() {
		return announceIntervalSec > 0 ? announceIntervalSec : DEFAULT_ANNOUNCE_INTERVAL_SEC;
	}

	public synchronized void setFromJson(JsonNode configNode) {
		if (configNode == null)
			return;

		int temp = configNode.path("ping_timeout_ms").asInt();
		if (temp > 0)
			this.pingTimeoutMs = temp;

		temp = configNode.path("ping_interval_sec").asInt();
		if (temp > 0)
			this.pingIntervalSec = temp;

		temp = configNode.path("download_timeout_ms").asInt();
		if (temp > 0)
			this.downloadTimeoutMs = temp;

		temp = configNode.path("download_interval_sec").asInt();
		if (temp > 0)
			this.downloadIntervalSec = temp;

		temp = configNode.path("download_executers").asInt();
		if (temp > 0)
			this.downloadExecuters = temp;

		temp = configNode.path("ping_executers").asInt();
		if (temp > 0)
			this.pingExecuters = temp;

		temp = configNode.path("announce_interval_sec").asInt();
		if (temp > 0)
			this.announceIntervalSec = temp;
	}

	public ObjectNode getAllAsJson() {
		ObjectNode result = factory.objectNode();
		result.put("ping_timeout_ms", pingTimeoutMs);
		result.put("ping_interval_sec", pingIntervalSec);
		result.put("download_timeout_ms", downloadTimeoutMs);
		result.put("download_interval_sec", downloadIntervalSec);
		result.put("announce_interval_sec", announceIntervalSec);

		return result;
	}
}
