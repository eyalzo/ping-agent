package com.eyalzo.pingagent;

import java.util.LinkedList;
import java.util.concurrent.*;

/**
 * Perform download from URL, using a thread pool for fast parallel operation.
 * 
 * @author Eyal Zohar
 */
public class DownloadMeasure {
	/**
	 * Ping command, built for thread pool.
	 */
	static class DownloadCall implements Callable<DownloadResult> {
		private final DownloadResult downloadResult;
		private final int timeoutMillis;

		public DownloadCall(DownloadResult downloadResult, int timeoutMillis) {
			super();
			this.downloadResult = downloadResult;
			this.timeoutMillis = timeoutMillis;
		}

		@Override
		public DownloadResult call() {
			// Actually download the file - times are saved inside the download result object
			Exception downloadException = PingUtils.downloadUrlToNull(downloadResult, timeoutMillis);
			downloadResult.exception = downloadException;
			return downloadResult;
		}
	}

	/**
	 * @return Number of completed downloads. Some more may complete but marked as terminated. This is why the results of each download should be examined.
	 */
	static int download(DownloadList downloadList, int maxExecuters, int downloadTimeoutMillis,
			int overallTimeoutMillis) {
		// Sanity check
		if (downloadList == null || downloadList.isEmpty())
			return 0;

		int executersCount = Math.max(1, Math.min(maxExecuters, downloadList.size()));
		final ExecutorService downloadExecutor = Executors.newFixedThreadPool(executersCount);

		LinkedList<Future<DownloadResult>> downloadResultsFutures = new LinkedList<Future<DownloadResult>>();

		for (DownloadResult curDownloadResult : downloadList.getDownloadResults()) {
			DownloadCall callable = new DownloadCall(curDownloadResult, downloadTimeoutMillis);
			// Start running, without waiting
			downloadResultsFutures.add(downloadExecutor.submit(callable));
		}
		// Initiate shutdown so we can wait for completion
		downloadExecutor.shutdown();
		// Wait for all to complete
		try {
			// Try to wait until a timeout, or terminate if not
			if (!downloadExecutor.awaitTermination(overallTimeoutMillis, TimeUnit.MILLISECONDS))
				downloadExecutor.shutdownNow();
		} catch (InterruptedException e1) {
			// Ignore, but remember that some futures may fail so check isDone()
			downloadExecutor.shutdownNow();
		}

		int result = 0;
		for (Future<DownloadResult> futurePingResult : downloadResultsFutures) {
			// Get the address list only if done, to prevent blocking
			if (futurePingResult.isDone()) {
				try {
					futurePingResult.get();
					result++;
				} catch (InterruptedException | ExecutionException | CancellationException e) {
				}
			} else {
				try {
					futurePingResult.cancel(true);
				} catch (Exception e) {
				}
			}
		}

		return result;
	}
}
