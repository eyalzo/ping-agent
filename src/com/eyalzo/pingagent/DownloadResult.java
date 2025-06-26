package com.eyalzo.pingagent;

import java.net.InetSocketAddress;
import java.net.URL;

public class DownloadResult {
	long enterQueueTime;
	long startConnectTime;
	long startDownloadTime;
	long endDownloadTime;
	public final URL url;
	Exception exception;
	private final InetSocketAddress ipAndPort;
	final int sizeToDownload;
	int sizeDownloaded;
	String regionName;

	public DownloadResult(URL url, InetSocketAddress ipAndPort, String regionName, int downloadSize) {
		this.url = url;
		this.ipAndPort = ipAndPort;
		this.sizeToDownload = downloadSize;
		this.enterQueueTime = System.nanoTime();
		this.regionName = regionName;
	}

	public String getAddressAsString() {
		return ipAndPort == null ? "" : ipAndPort.toString().substring(1);
	}

	/**
	 * @return Time is queue before started to connect. If did not try to connect it returns the time until now.
	 */
	public int getQueueMillis() {
		if (startConnectTime == 0)
			return (int) ((System.nanoTime() - enterQueueTime) / 1000000);
		return (int) ((startConnectTime - enterQueueTime) / 1000000);
	}

	/**
	 * @return Time it took to connect in micro seconds. Zero if did not connect.
	 */
	public int getConnectMicro() {
		if (startConnectTime == 0 || startDownloadTime == 0)
			return 0;
		return (int) ((startDownloadTime - startConnectTime) / 1000);
	}

	/**
	 * @return Time it took to download, not including the connect. Zero if did not download all the bytes and/or got an exception.
	 */
	public int getDownloadWithoutConnectMicro() {
		if (startDownloadTime == 0 || endDownloadTime == 0 || exception != null)
			return 0;
		return (int) ((endDownloadTime - startDownloadTime) / 1000);
	}
}
