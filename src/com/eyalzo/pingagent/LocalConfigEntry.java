package com.eyalzo.pingagent;

public class LocalConfigEntry {
	/**
	 * Optional description, when trying to use the value.
	 */
	String description;
	String value;
	/**
	 * Default value, given when trying to read the value in the code. Also when {@link #isNumeric} is True.
	 */
	String deafultValue;
	boolean isNumeric = false;
	/**
	 * How many times read and used by the code.
	 */
	int useCount;
	/**
	 * When it was last read and used by the code.
	 */
	long useTime;
	/**
	 * When it was last loaded form file.
	 */
	long loadTime;
	/**
	 * True if found in file during the last load.
	 */
	boolean available = false;
}
