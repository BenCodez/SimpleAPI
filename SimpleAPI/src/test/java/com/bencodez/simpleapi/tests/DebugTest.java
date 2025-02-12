package com.bencodez.simpleapi.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.bencodez.simpleapi.debug.DebugLevel;

public class DebugTest {

	@Test
	public void getDebugReturnsCorrectEnumForValidString() {
		assertEquals(DebugLevel.DEV, DebugLevel.getDebug("DEV"));
		assertEquals(DebugLevel.EXTRA, DebugLevel.getDebug("EXTRA"));
		assertEquals(DebugLevel.INFO, DebugLevel.getDebug("INFO"));
		assertEquals(DebugLevel.NONE, DebugLevel.getDebug("NONE"));
	}

	@Test
	public void getDebugReturnsNoneForInvalidString() {
		assertEquals(DebugLevel.NONE, DebugLevel.getDebug("INVALID"));
		assertEquals(DebugLevel.NONE, DebugLevel.getDebug(""));
		assertEquals(DebugLevel.NONE, DebugLevel.getDebug(null));
	}

	@Test
	public void isDebugReturnsTrueForDebugLevels() {
		assertTrue(DebugLevel.DEV.isDebug());
		assertTrue(DebugLevel.EXTRA.isDebug());
		assertTrue(DebugLevel.INFO.isDebug());
	}

	@Test
	public void isDebugReturnsFalseForNoneLevel() {
		assertFalse(DebugLevel.NONE.isDebug());
	}

	@Test
	public void isDebugWithCurrentLevelReturnsTrueForValidScenarios() {
		assertTrue(DebugLevel.DEV.isDebug(DebugLevel.INFO));
		assertTrue(DebugLevel.DEV.isDebug(DebugLevel.EXTRA));
		assertTrue(DebugLevel.DEV.isDebug(DebugLevel.DEV));
		assertTrue(DebugLevel.EXTRA.isDebug(DebugLevel.INFO));
		assertTrue(DebugLevel.EXTRA.isDebug(DebugLevel.EXTRA));
		assertTrue(DebugLevel.INFO.isDebug(DebugLevel.INFO));
	}

	@Test
	public void isDebugWithCurrentLevelReturnsFalseForInvalidScenarios() {
		assertFalse(DebugLevel.DEV.isDebug(DebugLevel.NONE));
		assertFalse(DebugLevel.EXTRA.isDebug(DebugLevel.DEV));
		assertFalse(DebugLevel.INFO.isDebug(DebugLevel.EXTRA));
		assertFalse(DebugLevel.NONE.isDebug(DebugLevel.INFO));
		assertFalse(DebugLevel.NONE.isDebug(DebugLevel.EXTRA));
		assertFalse(DebugLevel.NONE.isDebug(DebugLevel.DEV));
	}

}
