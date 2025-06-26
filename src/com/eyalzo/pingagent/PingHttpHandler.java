package com.eyalzo.pingagent;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.net.HttpURLConnection;
import java.util.Date;
import java.util.Random;

/**
 * Handling HTTP requests.
 * 
 * @author Eyal Zohar
 */
public class PingHttpHandler implements HttpHandler {
	private static JsonNodeFactory factory = JsonNodeFactory.instance;
	private final int version;
	private final AnnounceThread announeThread;
	private final PingThread pingThread;
	private final DownloadThread downloadThread;
	private final LocalConfig localConfig;
	private final long startTime = System.currentTimeMillis();
	//
	// Download (actually upload) buffers
	//
	private byte[] buffer_20k;
	private final static long MAX_DOWNLOAD_BUFFER_SIZE = 1000000;

	/**
	 * @param version
	 *            Software version, to be displayed in every returned json.
	 * @param announeThread
	 *            Announce thread, for statistics under "announce" in the result json.
	 */
	public PingHttpHandler(int version, AnnounceThread announeThread, PingThread pingThread,
			DownloadThread downloadThread, LocalConfig localConfig) {
		this.version = version;
		this.announeThread = announeThread;
		this.pingThread = pingThread;
		this.downloadThread = downloadThread;
		this.localConfig = localConfig;
		initBuffers();
	}

	private void initBuffers() {
		Random rd = new Random();
		buffer_20k = new byte[20 * 1000];
		rd.nextBytes(buffer_20k);
	}

	@Override
	public void handle(HttpExchange httpExchange) throws IOException {
		long before = System.nanoTime();
		String command = httpExchange.getRequestURI().getPath().toLowerCase();

		// Request for binary random bytes
		if (command.equals("/download")) {
			handleBinaryDownload(httpExchange, command);
			return;
		}

		ObjectNode jsonRoot = factory.objectNode();
		addBasicProperties(jsonRoot, httpExchange);

		// By command
		if (command.equals("/memory")) {
			jsonRoot.set("memory", getMemoryJson());
		} else if (command.equals("/announce_thread")) {
			jsonRoot.set("announce_thread", announeThread.getStatisticsAsJson(true));
		} else if (command.equals("/ping_thread")) {
			jsonRoot.set("ping_thread", pingThread.getStatisticsAsJson());
		} else if (command.equals("/download_thread")) {
			jsonRoot.set("download_thread", downloadThread.getStatisticsAsJson());
		} else if (command.equals("/config")) {
			jsonRoot.set("config", localConfig.getStatisticsAsJson());
		} else if (command.equals("/config_reload")) {
			localConfig.reload();
			jsonRoot.set("config", localConfig.getStatisticsAsJson());
		} else if (command.equals("/help")) {
			jsonRoot.set("supported_commands", getSupportedCommands(httpExchange));
		} else if (command.equals("/main") || command.equals("/index") || command.equals("/home") || command.equals("/root")) {
			jsonRoot.set("announce_thread", announeThread.getStatisticsAsJson(false));
			// Ping results statistics, without the detailed results
			jsonRoot.set("ping_thread", pingThread.getStatisticsAsJsonMinimal());
			// Ping results statistics, without the detailed results
			jsonRoot.set("download_thread", downloadThread.getStatisticsAsJsonMinimal());
		} else {
			// Show an error, so the security tools will not think the agent is vulnerable to Java Deserialization Attack
			httpExchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
			httpExchange.sendResponseHeaders(HttpURLConnection.HTTP_FORBIDDEN, 0);
			httpExchange.getResponseBody().flush();
			httpExchange.getResponseBody().close();
		}

		// Time it took to process in ms with 0.1 precision
		jsonRoot.put("processing_ms", ((float) ((System.nanoTime() - before) / 100000)) / 10);

		httpExchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
		httpExchange.getResponseHeaders().set("Content-Type", "application/json");

		byte[] response = jsonRoot.toString().getBytes();
		httpExchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, response.length);
		httpExchange.getResponseBody().write(response);
		httpExchange.getResponseBody().flush();
		httpExchange.getResponseBody().close();
	}

	/**
	 * Provides random buffer to the HTTP client.
	 * <p>
	 * Optionally, the size in specified in the request as "size" parameter.
	 */
	private void handleBinaryDownload(HttpExchange httpExchange, String command) throws IOException {
		String query = httpExchange.getRequestURI().getQuery();
		long size = Math.min(MAX_DOWNLOAD_BUFFER_SIZE, HttpUtils.paramAsLong(query, "size", buffer_20k.length));
		if (size <= 0)
			return;
		httpExchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
		httpExchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
		httpExchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, size);
		for (int i = (int) size; i > 0; i -= buffer_20k.length) {
			httpExchange.getResponseBody().write(buffer_20k, 0, Math.min(buffer_20k.length, i));
		}
		httpExchange.getResponseBody().flush();
		httpExchange.getResponseBody().close();
	}

	private void addBasicProperties(ObjectNode jsonRoot, HttpExchange httpExchange) {
		jsonRoot.put("version", this.version);

		// Readable string with time zone
		jsonRoot.put("start_time", this.startTime / 1000);
		jsonRoot.put("start_time_readable", (new Date(this.startTime)).toString());
		jsonRoot.put("up_time_sec", (System.currentTimeMillis() - startTime) / 1000);
		// Help
		String hostName = httpExchange.getRequestHeaders().getFirst("Host");
		jsonRoot.put("help", "Go to /help for command list.");
		if (hostName != null && !hostName.isEmpty()) {
			jsonRoot.put("help_uri", "http://" + hostName + "/help");
		}

		//
		// Client request
		//
		ObjectNode node = jsonRoot.putObject("request");
		node.put("start_time", System.currentTimeMillis() / 1000);
		node.put("remote_address", httpExchange.getRemoteAddress().toString());
		node.put("agent_address", httpExchange.getLocalAddress().toString());
		if (hostName != null && !hostName.isEmpty()) {
			node.put("uri", "http://" + hostName + httpExchange.getRequestURI().toString());
		} else {
			node.put("uri", httpExchange.getRequestURI().toString());
		}
	}

	private ObjectNode getSupportedCommands(HttpExchange httpExchange) {
		// Hostname for commands
		String hostName = httpExchange.getRequestHeaders().getFirst("Host");
		String commandPrefix = (hostName == null || hostName.isEmpty()) ? null : "http://" + hostName;

		// Create result json root
		ObjectNode result = factory.objectNode();

		// Main
		ObjectNode node = result.putObject("main");
		node.put("description", "The root menu.");
		if (commandPrefix != null)
			node.put("uri", commandPrefix + "/main");

		// Memory
		node = result.putObject("memory");
		node.put("description", "Detailed memory report of heap and non-heap.");
		if (commandPrefix != null)
			node.put("uri", commandPrefix + "/memory");

		// Announce-thread
		node = result.putObject("announce_thread");
		node.put("description", "Announce thread - last call, statistics and results.");
		if (commandPrefix != null)
			node.put("uri", commandPrefix + "/announce_thread");

		// Ping-thread
		node = result.putObject("ping_thread");
		node.put("description", "Results and statistics related to the ping thread, with detailed results.");
		if (commandPrefix != null)
			node.put("uri", commandPrefix + "/ping_thread");

		// Download-thread
		node = result.putObject("download_thread");
		node.put("description", "Download thread - last call, statistics and results.");
		if (commandPrefix != null)
			node.put("uri", commandPrefix + "/download_thread");

		// Download
		node = result.putObject("download");
		node.put("description", "Download random binary content for speed tests.");
		ObjectNode params = node.putObject("params");
		params.put("size", "Download size in bytes. Default is " + buffer_20k.length + ". Max is "
				+ MAX_DOWNLOAD_BUFFER_SIZE + ".");
		if (commandPrefix != null)
			node.put("uri", commandPrefix + "/download?size=" + buffer_20k.length);

		// Config
		node = result.putObject("config");
		node.put("description",
				"Display configruation file name, statistics, and a list of read parameters and values.");
		if (commandPrefix != null)
			node.put("uri", commandPrefix + "/config");

		// Config-reload
		node = result.putObject("config_reload");
		node.put("description", "Reload local config file, and then display /config.");
		if (commandPrefix != null)
			node.put("uri", commandPrefix + "/config_reload");

		return result;
	}

	private ObjectNode getMemoryJson() {
		// Create result json root
		ObjectNode result = factory.objectNode();

		//
		// Memory
		//
		MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
		MemoryUsage heapMemoryUsage = memoryBean.getHeapMemoryUsage();
		MemoryUsage nonHeapMemoryUsage = memoryBean.getNonHeapMemoryUsage();

		//
		// Heap
		//
		ObjectNode node = result.putObject("heap");
		node.put("init", heapMemoryUsage.getInit());
		node.put("used", heapMemoryUsage.getUsed());
		node.put("committed", heapMemoryUsage.getCommitted());
		node.put("max", heapMemoryUsage.getMax());

		//
		// Non-heap
		//
		node = result.putObject("non_heap");
		node.put("init", nonHeapMemoryUsage.getInit());
		node.put("used", nonHeapMemoryUsage.getUsed());
		node.put("committed", nonHeapMemoryUsage.getCommitted());
		node.put("max", nonHeapMemoryUsage.getMax());

		return result;
	}
}