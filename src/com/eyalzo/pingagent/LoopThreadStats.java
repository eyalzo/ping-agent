package com.eyalzo.pingagent;

public class LoopThreadStats {
	/**
	 * When the current loop started.
	 */
	long startTime;
	/**
	 * Loop's sequence number 1-based.
	 */
	long sequence;
	/**
	 * How long it took to complete a loop in mSec.
	 */
	long completionTimeMillis;
	/**
	 * Exception message. If none occurred, then the string is empty. Never null.
	 */
	String exceptionMessage = "";

	public LoopThreadStats(long sequence) {
		super();
		this.startTime = System.currentTimeMillis();
		this.sequence = sequence;
	}

}
