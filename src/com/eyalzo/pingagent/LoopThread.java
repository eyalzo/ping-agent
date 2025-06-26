package com.eyalzo.pingagent;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.LinkedList;

/**
 * Wakes up every interval and performs an action.
 * 
 * @author Eyal Zohar
 */
public abstract class LoopThread extends Thread {
	private static JsonNodeFactory factory = JsonNodeFactory.instance;
	protected long loopIntervalMillis;
	protected long nextLoop;
	protected boolean quit = false;

	//
	// Statistics
	//
	/**
	 * Current statistics.
	 */
	private LoopThreadStats stats;
	private final int MAX_STATS_HISTORY = 20;
	private LinkedList<LoopThreadStats> statsHistory = new LinkedList<>();
	/**
	 * Total number of loops, includes an incomplete loop that is running now.
	 */
	private long statLoops;
	/**
	 * Loops that ran code that returned true, just for statistics.
	 */
	private long statLoopsSuccess;
	/**
	 * Loops that ran code that returned false, just for statistics.
	 */
	private long statLoopsFailed;
	/**
	 * Loops that caused an exception, which is surrounded by try/catch here.
	 */
	private long statLoopsException;
	/**
	 * Last time an exception occurred - the message.
	 */
	private String statLastExceptionMessage = "";
	private String statLastExceptionStackTrace = "";
	/**
	 * Last time an exception occurred - the time.
	 */
	private long statLastExceptionTimeMillis;
	private long statLastWakeupTime;

	public LoopThread(String name, long loopIntervalMillis) {
		super(name);
		this.loopIntervalMillis = loopIntervalMillis;
	}

	/**
	 * override this to run code on initialize, before going to sleep for the first time.
	 * 
	 * @return True on success, for success-loops counter.
	 */
	public abstract boolean runFirstTime();

	/**
	 * override this to run code on every loop after the first sleep.
	 * 
	 * @return True on success, for success-loops counter.
	 */
	public abstract boolean runLoop();

	/**
	 * Signals the thread to wakeup by interrupting a sleep.
	 */
	public void wakeup() {
		statLastWakeupTime = System.currentTimeMillis();
		this.interrupt();
	}

	/**
	 * Set the interval only once, to override the normal interval in special cases. To be called when the loop is running.
	 * 
	 * @param intervalMillis
	 *            When to start the next loop, after the start time of the current.
	 * @return Next loop time.
	 */
	public long setIntervalOnce(long intervalMillis) {
		if (nextLoop > 0 && stats.startTime > 0)
			nextLoop = stats.startTime + intervalMillis;
		return nextLoop;
	}

	@Override
	public void run() {
		while (true) {
			// Loop counter considers also loops that are incomplete
			statLoops++;
			// Next loop should not be subject to how long it took to perform
			// the actions
			stats = new LoopThreadStats(statLoops);
			nextLoop = stats.startTime + loopIntervalMillis;

			//
			// Run code
			//
			long before = System.nanoTime();
			boolean success;
			try {
				success = (statLoops == 1) ? runFirstTime() : runLoop();
				if (success) {
					statLoopsSuccess++;
				} else {
					statLoopsFailed++;
				}
			} catch (Exception e) {
				statLoopsException++;
				statLastExceptionTimeMillis = System.currentTimeMillis();
				stats.exceptionMessage = e.getMessage();
				if (stats.exceptionMessage == null) {
					stats.exceptionMessage = e.getClass().getCanonicalName();
				}
				statLastExceptionMessage = stats.exceptionMessage;
				statLastExceptionStackTrace = getExceptionStackTrace(e);
			}

			//
			// Save stats history
			//
			stats.completionTimeMillis = (System.nanoTime() - before) / 1000000;
			statsHistory.addLast(stats);
			if (statsHistory.size() > MAX_STATS_HISTORY) {
				statsHistory.removeFirst();
			}

			//
			// Sleep
			//
			
			// The interval might have been changed in the loop itself

			long sleepMillis = nextLoop - System.currentTimeMillis();
			if (sleepMillis > 10 && !quit) {
				try {
					Thread.sleep(sleepMillis);
				} catch (InterruptedException e) {
					statLastWakeupTime = System.currentTimeMillis();
				}
			}

			// Check for the quit signal
			if (quit)
				return;
		}
	}

	private static String getExceptionStackTrace(Exception e) {
		if (e == null)
			return "";
		StringWriter sw = new StringWriter();
		PrintWriter pw = new PrintWriter(sw);
		e.printStackTrace(pw);
		return sw.toString();
	}

	/**
	 * Adds the current stack trace to a json tree, for debug.
	 * 
	 * @param node
	 *            Root node, where to create the array with stack trace.
	 * @param name
	 *            Name of the array to create under the given node.
	 */
	private void addCurrentStackTrace(ObjectNode node, String name) {
		StackTraceElement[] stackTrace = this.getStackTrace();
		if (stackTrace == null || stackTrace.length < 1)
			return;
		ArrayNode arrayNode = node.putArray(name);
		for (StackTraceElement curElement : stackTrace) {
			arrayNode.add(curElement.toString());
		}
	}

	public ObjectNode getStatisticsAsJson() {
		long now = System.currentTimeMillis();

		// Create result json root
		ObjectNode result = factory.objectNode();

		// Number of loops started so far, not necessarily completed yet (the
		// last one)
		ObjectNode node = result.putObject("loops");
		node.put("total", statLoops);
		// Number of loops that returned "true" for statistics
		node.put("success", statLoopsSuccess);
		// Number of loops that returned "false" for statistics
		node.put("failed", statLoopsFailed);
		// Number of loops that crashed with an exception
		node.put("exception", statLoopsException);
		node.put("running", statLoops - statLoopsSuccess - statLoopsFailed - statLoopsException);

		node = result.putObject("last_loop");
		LocalConfig.addTimeNodes(node, "start_time", stats == null ? 0 : stats.startTime);
		// Last loop exception message, or empty if none happened
		node.put("exception_message", stats.exceptionMessage);
		// How long it took to complete the last loop
		node.put("completion_time_ms", stats.completionTimeMillis);

		// Stats history
		if (statsHistory.size() > 0) {
			ArrayNode itemsArray = result.arrayNode(statsHistory.size());
			result.set("loops_history", itemsArray);
			long prevStartTime = 0;
			for (LoopThreadStats curStats : statsHistory) {
				ObjectNode curNode = itemsArray.addObject();
				curNode.put("seq", curStats.sequence);
				LocalConfig.addTimeNodes(curNode, "start_time", curStats.startTime);
				if (prevStartTime > 0) {
					curNode.put("start_time_diff_sec", (curStats.startTime - prevStartTime) / 1000);
				}
				curNode.put("completion_time_ms", curStats.completionTimeMillis);
				// Loop's exception message, or empty if none happened
				node.put("exception_message", curStats.exceptionMessage);
				prevStartTime = curStats.startTime;
			}
		}

		if (statLoopsException > 0) {
			node = result.putObject("last_exception");
			// Last exception message, or empty if never happened
			node.put("message", statLastExceptionMessage);
			LocalConfig.addTimeNodes(node, "time", statLastExceptionTimeMillis);
			node.put("stack_trace", statLastExceptionStackTrace);
		}

		// When the next loop will begin
		result.put("next_loop_sec", (this.nextLoop - now) / 1000);
		// Interval
		result.put("config_loop_sec", loopIntervalMillis / 1000);
		// When the last wakeup happened
		result.put("last_wakeup",
				statLastWakeupTime == 0 ? "(never)" : String.format("%,d Sec", (now - statLastWakeupTime) / 1000));

		this.addCurrentStackTrace(result, "stack_trace");

		return result;
	}

	public long getLoopsCount() {
		return this.statLoops;
	}

	/**
	 * Mark a flag to quit and send an interrupt. Call {@link Thread#interrupt()} if no need to quit but just to run the loop now.
	 */
	public void quit() {
		this.quit = true;
		this.interrupt();
	}
}
