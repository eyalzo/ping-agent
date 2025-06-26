package com.eyalzo.pingagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

/**
 * List of items to ping and/or download.
 * <p>
 * This list is filled with response from VGN server during an announce, and delivered to ping and download threads.
 * 
 * @see {@link AnnounceReceivedItem}.
 * @author Eyal Zohar
 */
public class AnnounceReceivedList {
	/**
	 * When the list was created. Should be used when the list is being copied/merged.
	 */
	private long updateTime = System.currentTimeMillis();
	/**
	 * Addresses (IP+port) along with the region name (informative).
	 */
	private Map<InetSocketAddress, AnnounceReceivedItem> addresses = new HashMap<>();

	/**
	 * @param clientsNode
	 *            The json node that contains object with regions, each region with a object under "agents". Can be null for tests.
	 */
	public AnnounceReceivedList(JsonNode clientsNode) {
		addAll(clientsNode);
	}

	public int size() {
		synchronized (addresses) {
			return addresses.size();
		}
	}

	/**
	 * @return Number of addresses with at least one download command.
	 */
	public int getDownloadsCount() {
		int result = 0;
		synchronized (addresses) {
			for (AnnounceReceivedItem curItem : addresses.values()) {
				if (curItem.hasDownloads())
					result++;
			}
		}
		return result;
	}

	/**
	 * @param clientsNode
	 *            The json node that contains object with regions, each region with a object under "agents". Can be null for tests.
	 * @return Number of addresses found and added, even if already exist in the list.
	 */
	private int addAll(JsonNode clientsNode) {
		if (clientsNode == null)
			return 0;

		int result = 0;

		Iterator<Map.Entry<String, JsonNode>> iter = clientsNode.fields();
		while (iter.hasNext()) {
			Map.Entry<String, JsonNode> entry = iter.next();
			// Region name as "provider\region"
			String regionName = entry.getKey();
			// Region records with "agents" node
			JsonNode regionNode = entry.getValue();
			JsonNode regionAgentsNode = regionNode.get("agents");
			Iterator<JsonNode> iterAgents = regionAgentsNode.elements();
			while (iterAgents.hasNext()) {
				JsonNode agentNode = iterAgents.next();
				if (this.addAddress(regionName, agentNode))
					result++;
			}
		}

		return result;
	}

	/**
	 * Add an address and/or update it's time so it won't be cleared out.
	 * 
	 * @param regionName
	 *            Informative region name as "provider\region".
	 * @param agentNode
	 *            The agent's node, containing "ip", "port", "rank" (not used at the moment). <br>
	 *            For example: { "ip": "3.24.138.198", "port": "5001", "rank": "99", "download": { "20000": 3000 } }
	 * @return True if item was added and is new (per IP and port).
	 */
	boolean addAddress(String regionName, JsonNode agentNode) {
		AnnounceReceivedItem item;
		try {
			item = new AnnounceReceivedItem(regionName, agentNode);
		} catch (MalformedURLException e) {
			return false;
		}

		synchronized (addresses) {
			return addresses.put(item.getIpAndPort(), item) == null;
		}
	}

	public ArrayNode getAddressesAsJson() {
		ArrayNode result = JsonNodeFactory.instance.arrayNode();
		synchronized (addresses) {
			for (Entry<InetSocketAddress, AnnounceReceivedItem> entry : addresses.entrySet()) {
				result.add(entry.getValue().asJson());
			}
		}

		return result;
	}

	@Override
	public String toString() {
		return getAddressesAsJson().toString();
	}

	public Set<InetSocketAddress> getAddresses() {
		synchronized (addresses) {
			return addresses.keySet();
		}
	}

	/**
	 * @return When the list was created. Should be used when the list is being copied/merged.
	 */
	public long getUpdateTime() {
		return updateTime;
	}

	/**
	 * @param downloadCommand
	 *            What to add to the IP and port of the agent in order to download. For example "/download?size=".
	 * @return Couples of full URLs with download timeout for each.
	 */
	public DownloadList getDownloadsAsNewDownloadList(String downloadCommand) {
		// HashMap<URL, Integer> result = new HashMap<URL, Integer>();
		DownloadList result = new DownloadList();
		synchronized (addresses) {
			for (AnnounceReceivedItem curItem : addresses.values()) {
				result.add(curItem, downloadCommand);
			}
		}
		return result;
	}
}
