package com.eyalzo.pingagent;

import java.io.IOException;
import java.io.InputStream;
import java.net.*;
import java.util.concurrent.TimeoutException;

/**
 * Several (static) network utilities required for ping agent functionality.
 * 
 * @author Eyal Zohar
 */
public class PingUtils {
	private static final byte[] buffer = new byte[100000];

	/**
	 * Pings a specified address by creating a TCP connection and then closing it immediately.
	 * This method is useful for checking the reachability of a service at a given IP address and port.
	 *
	 * @param addrAndPort
	 *            The {@link InetSocketAddress} containing the IP address and port to connect to.
	 * @param timeoutMillis
	 *            The maximum time to wait for the connection in milliseconds. Must be at least 1 ms.
	 * @return Returns {@code null} if the connection was successful, allowing the caller to use
	 *         the connection time for measurements. If the connection fails or times out, the specific
	 *         {@link Exception} encountered is returned.
	 *
	 * @throws IllegalArgumentException if {@code timeoutMillis} is less than 1 or if {@code addrAndPort} is {@code null}.
	 * <p>
	 * The method operates as follows:
	 * 1. Validates the input parameters. If `timeoutMillis` is less than 1 or if `addrAndPort` is `null`,
	 *    an {@link IllegalArgumentException} is thrown.
	 * 2. Attempts to create a socket and connect to the specified address and port within the given timeout period.
	 * 3. If the connection is successful, the socket is immediately closed, and the method returns {@code null}.
	 * 4. If a {@link SocketTimeoutException} occurs, indicating a timeout, the exception is caught and returned.
	 * 5. If any other {@link IOException} occurs, the exception is caught and returned.
	 * <p>
	 * Example usage:
	 * <pre>{@code
	 * InetSocketAddress address = new InetSocketAddress("192.168.1.1", 8080);
	 * int timeout = 1000; // 1 second
	 * Exception result = pingTcpAddress(address, timeout);
	 * if (result == null) {
	 *     System.out.println("Ping successful");
	 * } else {
	 *     System.err.println("Ping failed: " + result.getMessage());
	 * }
	 * }</pre>
	 *
	 * Note: Future implementations might handle specific cases such as explicit RST (connection reset) differently.
	 */
	public static Exception pingTcpAddress(InetSocketAddress addrAndPort, int timeoutMillis) {
		if (timeoutMillis < 1 || addrAndPort == null) {
			throw new IllegalArgumentException("Bad input parameter value: timeoutMillis must be at least 1 and addrAndPort cannot be null.");
		}

		Exception result = null;
		try (Socket socket = new Socket()) {
			socket.connect(addrAndPort, timeoutMillis);
		} catch (IOException e) {
			result = e;
		}

		return result;
	}

	/**
	 * Retrieves the hostname of the local machine.
	 * <p>
	 * This method attempts to obtain the local machine's hostname by querying the local network
	 * configuration. It uses {@link InetAddress#getLocalHost()} to get the local IP address and then
	 * retrieves the hostname associated with this address.
	 * <p>
	 * @return The hostname of the local machine, or {@code null} if the hostname cannot be determined or
	 * if an error occurs during the process.
	 * <p>
	 * The method operates as follows:
	 * <p>
	 * 1. Attempts to get the local host's IP address using {@link InetAddress#getLocalHost()}.
	 * <p>
	 * 2. Retrieves the hostname associated with the local IP address.
	 * <p>
	 * 3. Returns the hostname if it is not {@code null} or empty. If the hostname is {@code null} or empty,
	 *    it returns {@code null}.
	 * <p>
	 * 4. Catches {@link UnknownHostException} if the local host name could not be resolved into an address.
	 *    In this case, it returns {@code null}.
	 * <p>
	 * Example usage:
	 * <pre>{@code
	 * String machineName = getMachineName();
	 * if (machineName != null) {
	 *     System.out.println("Hostname: " + machineName);
	 * } else {
	 *     System.err.println("Hostname could not be determined.");
	 * }
	 * }</pre>
	 */
	public static String getMachineName() {
		try {
			InetAddress addr = InetAddress.getLocalHost();
			String hostname = addr.getHostName();
			return hostname == null ? null : hostname.isEmpty() ? null : hostname;
		} catch (UnknownHostException ex) {
			return null;
		}
	}

	/**
	 * Downloads content from the specified URL and measures the time taken for the connection and download.
	 * This method does not save the downloaded content but reads it to ensure the download completes.
	 *
	 * @param downloadResult
	 *            An instance of {@link DownloadResult} which holds the download details such as URL, start times, and downloaded size.
	 * @param timeoutMillis
	 *            The maximum time allowed for both connecting and reading the data in milliseconds. Must be positive.
	 * @return An {@link Exception} if an error occurs during the download process, or {@code null} if the download completes successfully.
	 *         If the download completes successfully, the download end-time is updated in {@link DownloadResult#endDownloadTime}.
	 * <p>
	 * The method operates as follows:
	 * 1. Validates the timeout parameter. If {@code timeoutMillis} is less than or equal to 0, an {@link IllegalArgumentException} is returned.
	 * 2. Records the start time for the connection attempt in {@link DownloadResult#startConnectTime}.
	 * 3. Initiates an HTTP connection to the URL specified in {@link DownloadResult#url} using the provided timeout for both connection and read operations.
	 *    If the connection fails, an {@link IOException} is returned.
	 * 4. Records the start time for the download attempt in {@link DownloadResult#startDownloadTime}.
	 * 5. Adjusts the read timeout based on the time taken to establish the connection.
	 *    If there is no time left for reading, a {@link TimeoutException} is returned.
	 * 6. Attempts to open an input stream to begin the GET request. If this fails, the corresponding {@link IOException} is returned.
	 * 7. Reads the content in a loop and updates the downloaded size in {@link DownloadResult#sizeDownloaded}.
	 *    If an {@link IOException} occurs during reading, it is returned.
	 * 8. Closes the input stream after reading is complete, ignoring any exceptions during the close operation.
	 * 9. Verifies that the total downloaded size matches the expected size in {@link DownloadResult#sizeToDownload}.
	 *    If they do not match, an {@link Exception} indicating a partial download is returned.
	 * 10. Records the end time for the download in {@link DownloadResult#endDownloadTime} and returns {@code null} to indicate success.
	 *
	 * @see HttpUtils#downloadUrlToFile(String, String, int, int, boolean, java.util.logging.Logger)
	 */
	public static Exception downloadUrlToNull(DownloadResult downloadResult, int timeoutMillis) {
		// Default timeouts
		if (timeoutMillis <= 0)
			return new IllegalArgumentException("Timeout must be positive");

		// Connect only, meaning 3-way handshake
		downloadResult.startConnectTime = System.nanoTime();
		HttpURLConnection connection = HttpUtils.sendHttpRequest(downloadResult.url, timeoutMillis, timeoutMillis, null,
				null);
		if (connection == null)
			return new IOException("Failed to connect");

		downloadResult.startDownloadTime = System.nanoTime();

		// Update the read timeout to be the remaining time
		int readTimeoutMillis = timeoutMillis
				- (int) (downloadResult.startDownloadTime - downloadResult.startConnectTime) / 1000000;
		if (readTimeoutMillis < 0)
			return new TimeoutException("Connect but do not have time to read");
		connection.setReadTimeout(readTimeoutMillis);

		//
		// Prepare input stream
		//
		InputStream is;
		try {
			// Only after this call the GET request is sent
			is = connection.getInputStream();
		} catch (IOException e) {
			return e;
		}

		//
		// Read loop
		//
		downloadResult.sizeDownloaded = 0;
		try {
			int readBytes;
			while ((readBytes = is.read(buffer)) != -1) {
				downloadResult.sizeDownloaded += readBytes;
			}
		} catch (IOException e) {
			return e;
		} finally {
			try {
				is.close();
			} catch (IOException _) {
			}
		}

		if (downloadResult.sizeDownloaded != downloadResult.sizeToDownload)
			return new Exception("Partial download");

		downloadResult.endDownloadTime = System.nanoTime();

		return null;
	}
}
