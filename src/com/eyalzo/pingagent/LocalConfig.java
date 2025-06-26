package com.eyalzo.pingagent;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Date;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.TreeMap;

/**
 * Read properties from the local configuration INI, using just key name and ignoring the section names.
 * <p>
 * The file located in <code>/opt/cbn/app.configs</code>:
 * 
 * <pre>
[instance]
network.id=measurement
appliance.name=aws-ap-northeast-1a-2pk6zrq15bd7r26ae14uejryyd
appliance.zone=ap-northeast-1a
appliance.region=ap-northeast-1
appliance.cloud=AWS
[servers]
subscription.api=http://control.wan.ninja/control/v1/agents/subscribe
 * </pre>
 */
public class LocalConfig {
	private final String fileName;
	private TreeMap<String, LocalConfigEntry> cache = new TreeMap<>();

	//
	// Statistics
	//
	private static JsonNodeFactory factory = JsonNodeFactory.instance;
	private long statFirstLoadTime;
	private long statLastLoadTime;
	private int statLoadCountSuccess = 0;
	private int statLoadCountError = 0;
	private String statLastLoadErrorMessage = "";
	private long statFileSize;
	private long statFileLastModifiedTime;

	public LocalConfig(String fileName) {
		this.fileName = fileName;
		load();
	}

	/**
	 * Load the configuration file into cache and update statistics accordingly.
	 * 
	 * @return True if read successfully, or false if file was not found and/or another IO error occurred.
	 */
	private boolean load() {
		// Update stats load time
		statLastLoadTime = System.currentTimeMillis();
		if (statFirstLoadTime == 0)
			statFirstLoadTime = statLastLoadTime;

		// Cleanup cache entries
		for (Entry<String, LocalConfigEntry> entry : cache.entrySet()) {
			LocalConfigEntry curConfigEntry = entry.getValue();
			curConfigEntry.available = false;
		}

		// Try to load the file
		Properties p = new Properties();
		try {
			p.load(new FileInputStream(fileName));
		} catch (FileNotFoundException e) {
			statLoadCountError++;
			statLastLoadErrorMessage = "file not found";
			return false;
		} catch (IOException e) {
			statLoadCountError++;
			statLastLoadErrorMessage = "io error - " + e.getMessage();
			return false;
		}
		statLoadCountSuccess++;
		statLastLoadErrorMessage = "ok";

		//
		// From file content to internal cache
		//

		long now = System.currentTimeMillis();
		// Walk through properties, some may be just headers
		for (Entry<Object, Object> entry : p.entrySet()) {
			String key = entry.getKey().toString().trim();

			// Skip headers and empty keys/lines
			if (key.startsWith("[") || "".equals(key))
				continue;

			// The value
			String value = entry.getValue().toString().trim();
			if ("".equals(value))
				continue;

			// Check if already known
			LocalConfigEntry localConfigEntry = cache.get(key);
			if (localConfigEntry == null) {
				localConfigEntry = new LocalConfigEntry();
				cache.put(key, localConfigEntry);
			}
			// Set value and stats
			localConfigEntry.value = value;
			localConfigEntry.available = true;
			localConfigEntry.loadTime = now;
		}

		//
		// For stats only
		//
		try {
			File file = new File(fileName);
			statFileSize = file.length();
			statFileLastModifiedTime = file.lastModified();
		} catch (Exception e) {
		}

		return true;
	}

	/**
	 * @param key
	 *            Key to match with the INI file entry (before the =).
	 * @param defaultValue
	 *            Optional. Can be null.
	 * @param description
	 *            Optional. Can be null.
	 * @return Default given value if missing or empty, or the value if found.
	 */
	String getString(String key, String defaultValue, String description) {
		LocalConfigEntry localConfigEntry = cache.get(key);
		if (localConfigEntry == null) {
			localConfigEntry = new LocalConfigEntry();
			cache.put(key, localConfigEntry);
		}
		localConfigEntry.useTime = System.currentTimeMillis();
		localConfigEntry.useCount++;
		localConfigEntry.isNumeric = false;
		localConfigEntry.deafultValue = defaultValue;
		localConfigEntry.description = description;
		// The "available" flag tells if key exists because it was found in a file
		return localConfigEntry.available ? localConfigEntry.value : defaultValue;
	}

	public boolean reload() {
		return load();
	}

	/**
	 * @return Statistics as json, to be displayed in GUI.
	 */
	public ObjectNode getStatisticsAsJson() {
		ObjectNode result = factory.objectNode();

		//
		// File
		//
		ObjectNode subNode = result.putObject("file");
		subNode.put("filename", fileName);
		subNode.put("size_bytes", statFileSize);
		addTimeNodes(subNode, "last_modified_time", statFileLastModifiedTime);

		addTimeNodes(subNode, "first_load_time", statFirstLoadTime);
		addTimeNodes(subNode, "last_load_time", statLastLoadTime);

		subNode.put("last_load_message", statLastLoadErrorMessage);
		subNode.put("load_count_success", statLoadCountSuccess);
		subNode.put("load_count_errors", statLoadCountError);

		//
		// Properties
		//
		subNode = result.putObject("items_in_use");
		ObjectNode subNodeNotInUse = result.putObject("items_not_in_use");
		// Walk through properties, some may be just headers
		for (Entry<String, LocalConfigEntry> entry : cache.entrySet()) {
			String key = entry.getKey();
			LocalConfigEntry localConfigEntry = entry.getValue();
			// Add the sub item under the "in use" or not
			ObjectNode itemNode = localConfigEntry.useCount > 0 ? subNode.putObject(key)
					: subNodeNotInUse.putObject(key);
			if (localConfigEntry.available)
				itemNode.put("available", localConfigEntry.available);
			if (localConfigEntry.useCount > 0)
				itemNode.put("use_count", localConfigEntry.useCount);
			itemNode.put("value", localConfigEntry.value);
			if (localConfigEntry.deafultValue != null)
				itemNode.put("default_value", localConfigEntry.deafultValue);
			if (localConfigEntry.description != null)
				itemNode.put("description", localConfigEntry.description);
		}

		return result;
	}

	/**
	 * Serves {@link #getStatisticsAsJson()}.
	 */
	static void addTimeNodes(ObjectNode node, String prefix, long time) {
		if (node == null || time <= 0) {
			node.put(prefix + "_readable", "(never)");
			return;
		}
		node.put(prefix + "_unix", time);
		node.put(prefix + "_sec_ago", (System.currentTimeMillis() - time) / 1000);
		try {
			node.put(prefix + "_readable", (new Date(time)).toString());
		} catch (Exception e) {
		}
	}

	/**
	 * @param key
	 *            Key to match with the INI file entry (before the =).
	 * @param defaultValue
	 *            Optional. Can be null.
	 * @param description
	 *            Optional. Can be null.
	 * @return Default given value if missing or not a long number, or the value if found a legal long number. Can be null if the default is null.
	 */
	long getLong(String key, long defaultValue, String description) {
		LocalConfigEntry localConfigEntry = cache.get(key);
		if (localConfigEntry == null) {
			localConfigEntry = new LocalConfigEntry();
			cache.put(key, localConfigEntry);
		}
		localConfigEntry.useTime = System.currentTimeMillis();
		localConfigEntry.useCount++;
		localConfigEntry.isNumeric = true;
		localConfigEntry.deafultValue = Long.toString(defaultValue);
		localConfigEntry.description = description;

		// The "available" flag tells if key exists because it was found in a file
		if (!localConfigEntry.available)
			return defaultValue;

		// Parse it
		Long resultLong;
		try {
			resultLong = Long.parseLong(localConfigEntry.value);
		} catch (NumberFormatException e) {
			return defaultValue;
		}
		return resultLong;
	}

	/**
	 * @return Null if missing, or the value if found.
	 */
	public String getFileName() {
		return fileName;
	}

	/**
	 * @return Null if missing, or the value if found.
	 */
	public String getCloudName() {
		String result = getString("appliance.cloud", null,
				"Cloud provider name, converted to lowercase. Mandatory at some point - either during announce or by another agent or service.");
		return result == null ? null : result.toLowerCase();
	}

	/**
	 * @return Null if missing, or the value if found.
	 */
	public String getZone() {
		return getString("appliance.zone", null,
				"The cloud's zone, under the region (optional, e.g. eu-west2a). Optional - not in use.");
	}

	/**
	 * @return Empty if missing, or the value if found.
	 */
	public String getCloudRegion() {
		return getString("appliance.region", "",
				"The cloud's region (e.g. eu-west2). Mandatory at some point - either during announce or by another agent or service.");
	}

	/**
	 * @return Empty if missing, or the value if found.
	 */
	public String getNetworkId() {
		return getString("network.id", "",
				"The network id (e.g. eyalzo). Optional, because the ping service no longer requires it.");
	}

	/**
	 * @return Empty if missing, or the value if found.
	 */
	public String getMachineName() {
		return getString("appliance.name", "",
				"Machine name to be reported in the announce. Optional, because it is informative only.");
	}
}
