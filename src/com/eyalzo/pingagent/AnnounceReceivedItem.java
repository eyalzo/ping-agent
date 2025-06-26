package com.eyalzo.pingagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Pattern;

/**
 * Item to ping and/or download. The command received from VGN server during an announce.
 * 
 * @see {@link AnnounceReceivedList}.
 * @author Eyal Zohar
 */
public class AnnounceReceivedItem {
	private static final Pattern IPV4_PATTERN = Pattern
			.compile("^(25[0-5]|2[0-4]\\d|[0-1]?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|[0-1]?\\d?\\d)){3}$");
	/**
	 * IP and port. Can be null if malformed (although it had to generate exception on constructor).
	 */
	private InetSocketAddress ipAndPort;

	/**
	 * @return IP and port. Can be null if malformed (although it had to generate exception on constructor).
	 */
	public InetSocketAddress getIpAndPort() {
		return ipAndPort;
	}

	/**
	 * Informative region name as "provider\region".
	 */
	private String regionName;
	/**
	 * Optional. Downloads to perform: size in bytes and complete download timeout in millis. Can be null.
	 */
	private HashMap<Integer, Integer> downloads;

	/**
	 * @param regionName
	 *            Informative region name as "provider\region".
	 * @param agentNode
	 *            The agent's address node, containing "ip", "port", "rank" (not used at the moment). <br>
	 *            For example: { "ip": "3.24.138.198", "port": "5001", "rank": "99", "download": { "20000": 3000 } }
	 * 
	 * @throws MalformedURLException
	 *             If the address or port are missing or malformed.
	 */
	public AnnounceReceivedItem(String regionName, JsonNode agentNode) throws MalformedURLException {
		this.regionName = regionName;

		// Get IPv4 and use it only if format is IP, to prevent resolving
		// The use of path and asText means that we never get null or exception
		String ipStr = agentNode.path("ip").asText();
		if (!isIPv4Address(ipStr))
			throw new MalformedURLException("Not IPv4");

		int port = agentNode.path("port").asInt();
		if (ipStr.isEmpty() || port <= 0 || port > 65535)
			throw new MalformedURLException("Missing port number");

		InetAddress addr = null;
		try {
			addr = InetAddress.getByName(ipStr);
		} catch (UnknownHostException e) {
			throw new MalformedURLException("Unknown host");
		}

		this.ipAndPort = new InetSocketAddress(addr, port);
		if (this.ipAndPort.isUnresolved())
			throw new MalformedURLException("Address not resolved");

		// Downloads (optional)
		JsonNode downloadsNode = agentNode.get("download");
		if (downloadsNode != null) {
			Iterator<Map.Entry<String, JsonNode>> iter = downloadsNode.fields();
			while (iter.hasNext()) {
				Map.Entry<String, JsonNode> entry = iter.next();
				int size;
				try {
					size = Integer.parseInt(entry.getKey());
				} catch (NumberFormatException e) {
					continue;
				}
				if (size <= 0)
					continue;
				int timeoutMillis = entry.getValue().asInt();
				if (timeoutMillis <= 0)
					continue;
				if (downloads == null)
					downloads = new HashMap<Integer, Integer>();
				downloads.put(size, timeoutMillis);
			}
		}
	}

	/**
	 * @param input
	 *            Candidate for IPv4, without leading or trailing spaces. Can be null (false).
	 * @return True of the format seems like an IPv4.
	 */
	private static boolean isIPv4Address(final String input) {
		// Sanity check
		if (input == null)
			return false;

		return IPV4_PATTERN.matcher(input).matches();
	}

	/**
	 * @return Agent's full details as node object that can be added to an array.
	 */
	public ObjectNode asJson() {
		ObjectNode result = JsonNodeFactory.instance.objectNode();
		if (ipAndPort == null)
			return result;

		// IP and port
		result.put("address", ipAndPort.toString().substring(1));
		// Region name (informative only)
		if (regionName != null && !regionName.isEmpty())
			result.put("region", regionName);
		// Downloads (optional)
		if (this.hasDownloads()) {
			ObjectNode downloadsNode = JsonNodeFactory.instance.objectNode();
			for (Entry<Integer, Integer> entry : downloads.entrySet()) {
				downloadsNode.put(entry.getKey().toString(), entry.getValue());
			}
			result.set("download", downloadsNode);
		}

		return result;
	}

	@Override
	public String toString() {
		return asJson().toString();
	}

	/**
	 * @return True if it has at least one download command.
	 */
	public boolean hasDownloads() {
		return downloads != null && !downloads.isEmpty();
	}

	public HashMap<Integer, Integer> getDownloads() {
		return downloads;
	}

	public String getRegionName() {
		return regionName;
	}
}
