package com.eyalzo.pingagent;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;

/**
 * Perform ping to IP addresses (without name resolving), using a thread pool for fast parallel operation.
 * 
 * @author Eyal Zohar
 */
public class PingMeasure {
	/**
	 * The ping result. Successful only if the recorded exception is null.
	 */
	static class PingResult {
		public final InetSocketAddress addr;
		public final Exception exception;
		public final int rttMicro;
		/**
		 * When the ping operation (socket open) was actually started. After it was (optionally) in queue.
		 */
		public final long startPingTime;
		/**
		 * Time spent in queue (if at all).
		 */
		public final int millisInQueue;

		/**
		 * @param startPingTime
		 *            When the ping operation (socket open) was actually started. After it was (optionally) in queue.
		 * @param addr
		 * @param exception
		 * @param rttMicro
		 */
		public PingResult(long startPingTime, int MillisInQueue, InetSocketAddress addr, Exception exception,
				int rttMicro) {
			super();
			this.startPingTime = startPingTime;
			this.millisInQueue = MillisInQueue;
			this.addr = addr;
			this.exception = exception;
			this.rttMicro = rttMicro;
		}
	}

	/**
	 * Ping command, built for thread pool.
	 */
	static class PingHost implements Callable<PingResult> {
		private final InetSocketAddress addr;
		private final int timeoutMillis;
		private final long initTime;

		public PingHost(InetSocketAddress addr, int timeoutMillis) {
			super();
			this.addr = addr;
			this.timeoutMillis = timeoutMillis;
			this.initTime = System.currentTimeMillis();
		}

		@Override
		public PingResult call() {
			// Ping while measuring time
			long before = System.nanoTime();
			long pingStartTime = System.currentTimeMillis();
			Exception pingException = PingUtils.pingTcpAddress(addr, timeoutMillis);
			long intervalMicro = (System.nanoTime() - before) / 1000;
			return new PingResult(pingStartTime, (int) Math.max(0, pingStartTime - initTime), addr, pingException,
					(int) intervalMicro);
		}
	}

	/**
	 * @return List of addresses to ping, some of them (hopefully) with results. May be empty, but never null.
	 */
	static Map<InetSocketAddress, PingResult> pingHosts(Set<InetSocketAddress> hosts, int maxExecuters,
			int pingTimeoutMillis, int overallTimeoutMillis) {
		// Sanity check
		if (hosts == null || hosts.isEmpty())
			return Collections.emptyMap();

		int executersCount = Math.max(1, Math.min(maxExecuters, hosts.size()));
		final ExecutorService pingExecutor = Executors.newFixedThreadPool(executersCount);

		Map<InetSocketAddress, Future<PingResult>> pingResultsFutures = new HashMap<>(hosts.size());

		for (InetSocketAddress curFullAddr : hosts) {
			PingHost callable = new PingHost(curFullAddr, pingTimeoutMillis);
			// Start running, without waiting
			pingResultsFutures.put(curFullAddr, pingExecutor.submit(callable));
		}
		// Initiate shutdown so we can wait for completion
		pingExecutor.shutdown();
		// Wait for all to complete
		try {
			// Try to wait until a timeout, or terminate if not
			if (!pingExecutor.awaitTermination(overallTimeoutMillis, TimeUnit.MILLISECONDS))
				pingExecutor.shutdownNow();
		} catch (InterruptedException e1) {
			// Ignore, but remember that some futures may fail so check isDone()
			pingExecutor.shutdownNow();
		}

		// To return
		Map<InetSocketAddress, PingResult> finalResult = new HashMap<>(hosts.size());

		for (Map.Entry<InetSocketAddress, Future<PingResult>> entry : pingResultsFutures.entrySet()) {
			InetSocketAddress curFullAddr = entry.getKey();

			// The host's future (may not complete resolving) and address list
			Future<PingResult> futurePingResult = entry.getValue();
			PingResult curPingResult = null;
			// Get the address list only if done, to prevent blocking
			if (futurePingResult.isDone()) {
				try {
					curPingResult = futurePingResult.get();
				} catch (InterruptedException | ExecutionException | CancellationException e) {
					curPingResult = null;
				}
			} else {
				try {
					futurePingResult.cancel(true);
				} catch (Exception e) {
				}
			}

			// Put each host, even if it has no addresses, for later count of
			// failures
			finalResult.put(curFullAddr, curPingResult);
		}

		return finalResult;
	}
}
