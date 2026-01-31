package com.bencodez.simpleapi.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.bencodez.simpleapi.time.ParsedDuration;
import com.bencodez.simpleapi.time.ParsedDuration.Unit;

/**
 * Unit tests for {@link ParsedDuration}.
 */
public class ParsedDurationTest {

	// ---------------------------------------------------------------------------------
	// Shared/basic behavior (null/empty, tokens, ISO) for BOTH Unit and TimeUnit APIs
	// ---------------------------------------------------------------------------------

	@Test
	@DisplayName("Empty and null inputs return empty duration (Unit + TimeUnit)")
	public void testEmptyInputs() {
		assertTrue(ParsedDuration.parse(null).isEmpty());
		assertTrue(ParsedDuration.parse("").isEmpty());
		assertTrue(ParsedDuration.parse("   ").isEmpty());

		assertTrue(ParsedDuration.parse(null, Unit.MINUTES).isEmpty());
		assertTrue(ParsedDuration.parse("", Unit.MINUTES).isEmpty());
		assertTrue(ParsedDuration.parse("   ", Unit.MINUTES).isEmpty());

		assertTrue(ParsedDuration.parse(null, TimeUnit.MINUTES).isEmpty());
		assertTrue(ParsedDuration.parse("", TimeUnit.MINUTES).isEmpty());
		assertTrue(ParsedDuration.parse("   ", TimeUnit.MINUTES).isEmpty());
	}

	@Test
	@DisplayName("ISO-8601 duration parsing (PT/P1D) works the same (Unit + TimeUnit)")
	public void testIso8601Duration() {
		assertEquals(30 * 60_000L, ParsedDuration.parse("PT30M", Unit.MINUTES).getMillis());
		assertEquals(12 * 3_600_000L, ParsedDuration.parse("PT12H", Unit.MINUTES).getMillis());
		assertEquals(86_400_000L, ParsedDuration.parse("P1D", Unit.MINUTES).getMillis());

		assertEquals(30 * 60_000L, ParsedDuration.parse("PT30M", TimeUnit.MINUTES).getMillis());
		assertEquals(12 * 3_600_000L, ParsedDuration.parse("PT12H", TimeUnit.MINUTES).getMillis());
		assertEquals(86_400_000L, ParsedDuration.parse("P1D", TimeUnit.MINUTES).getMillis());
	}

	@Test
	@DisplayName("ISO-8601 months are rejected (P1M) for both APIs")
	public void testIsoMonthsRejected() {
		assertTrue(ParsedDuration.parse("P1M", Unit.MINUTES).isEmpty());
		assertTrue(ParsedDuration.parse("P1M", TimeUnit.MINUTES).isEmpty());
	}

	@Test
	@DisplayName("Fixed month tokens behave the same (3mo, 1mo2d) for both APIs")
	public void testFixedMonthTokens() {
		long expected3mo = 3L * 30L * 86_400_000L;
		assertEquals(expected3mo, ParsedDuration.parse("3mo", Unit.MINUTES).getMillis());
		assertEquals(expected3mo, ParsedDuration.parse("3mo", TimeUnit.MINUTES).getMillis());

		long expected = 1L * 30L * 86_400_000L + 2L * 86_400_000L;
		assertEquals(expected, ParsedDuration.parse("1mo2d", Unit.MINUTES).getMillis());
		assertEquals(expected, ParsedDuration.parse("1mo2d", TimeUnit.MINUTES).getMillis());
	}

	@Test
	@DisplayName("Combined tokens allow spaces (2d 12h) for both APIs")
	public void testCombinedWithSpaces() {
		long expected = 2 * 86_400_000L + 12 * 3_600_000L;
		assertEquals(expected, ParsedDuration.parse("2d 12h", Unit.MINUTES).getMillis());
		assertEquals(expected, ParsedDuration.parse("2d 12h", TimeUnit.MINUTES).getMillis());
	}

	@Test
	@DisplayName("Garbage input returns empty for both APIs")
	public void testGarbageInput() {
		assertTrue(ParsedDuration.parse("abc", Unit.MINUTES).isEmpty());
		assertTrue(ParsedDuration.parse("!@#$", Unit.MINUTES).isEmpty());

		assertTrue(ParsedDuration.parse("abc", TimeUnit.MINUTES).isEmpty());
		assertTrue(ParsedDuration.parse("!@#$", TimeUnit.MINUTES).isEmpty());
	}

	@Test
	@DisplayName("Zero or negative values return empty for both APIs")
	public void testZeroAndNegative() {
		assertTrue(ParsedDuration.parse("0m", Unit.MINUTES).isEmpty());
		assertTrue(ParsedDuration.parse("-5m", Unit.MINUTES).isEmpty());

		assertTrue(ParsedDuration.parse("0m", TimeUnit.MINUTES).isEmpty());
		assertTrue(ParsedDuration.parse("-5m", TimeUnit.MINUTES).isEmpty());
	}

	// ---------------------------------------------------------------------------------
	// Unit-specific behavior tests
	// ---------------------------------------------------------------------------------

	@Test
	@DisplayName("Unit API: plain number uses default MINUTES")
	public void testUnitPlainNumberDefaultMinutes() {
		ParsedDuration d = ParsedDuration.parse("30"); // default Unit.MINUTES
		assertEquals(30 * 60_000L, d.getMillis());
	}

	@Test
	@DisplayName("Unit API: plain number respects custom default Unit")
	public void testUnitPlainNumberCustomDefault() {
		assertEquals(15_000L, ParsedDuration.parse("15", Unit.SECONDS).getMillis());
		assertEquals(2 * 3_600_000L, ParsedDuration.parse("2", Unit.HOURS).getMillis());
	}

	@Test
	@DisplayName("Unit API: unknown suffix falls back to provided default Unit (single token)")
	public void testUnitUnknownSuffixFallbackSingle() {
		// default = minutes
		assertEquals(10 * 60_000L, ParsedDuration.parse("10x", Unit.MINUTES).getMillis());
		// default = seconds
		assertEquals(10 * 1000L, ParsedDuration.parse("10x", Unit.SECONDS).getMillis());
	}

	@Test
	@DisplayName("Unit API: unknown suffix falls back to provided default Unit (combined tokens)")
	public void testUnitUnknownSuffixFallbackCombined() {
		// 30x should fallback to minutes (default unit = MINUTES)
		long expectedMinutesFallback = 1 * 3_600_000L + 30 * 60_000L;
		assertEquals(expectedMinutesFallback, ParsedDuration.parse("1h30x", Unit.MINUTES).getMillis());

		// 30x should fallback to seconds (default unit = SECONDS)
		long expectedSecondsFallback = 1 * 3_600_000L + 30 * 1000L;
		assertEquals(expectedSecondsFallback, ParsedDuration.parse("1h30x", Unit.SECONDS).getMillis());
	}

	// ---------------------------------------------------------------------------------
	// TimeUnit-specific behavior tests
	// ---------------------------------------------------------------------------------

	@Test
	@DisplayName("TimeUnit API: plain number respects custom default TimeUnit")
	public void testTimeUnitPlainNumberCustomDefault() {
		assertEquals(15_000L, ParsedDuration.parse("15", TimeUnit.SECONDS).getMillis());
		assertEquals(2 * 3_600_000L, ParsedDuration.parse("2", TimeUnit.HOURS).getMillis());
	}

	@Test
	@DisplayName("TimeUnit API: unknown suffix falls back to provided default TimeUnit (single token)")
	public void testTimeUnitUnknownSuffixFallbackSingle() {
		// default = minutes
		assertEquals(10 * 60_000L, ParsedDuration.parse("10x", TimeUnit.MINUTES).getMillis());
		// default = seconds
		assertEquals(10 * 1000L, ParsedDuration.parse("10x", TimeUnit.SECONDS).getMillis());
	}

	@Test
	@DisplayName("TimeUnit API: unknown suffix falls back to provided default TimeUnit (combined tokens)")
	public void testTimeUnitUnknownSuffixFallbackCombined() {
		// 30x should fallback to minutes (default timeunit = MINUTES)
		long expectedMinutesFallback = 1 * 3_600_000L + 30 * 60_000L;
		assertEquals(expectedMinutesFallback, ParsedDuration.parse("1h30x", TimeUnit.MINUTES).getMillis());

		// 30x should fallback to seconds (default timeunit = SECONDS)
		long expectedSecondsFallback = 1 * 3_600_000L + 30 * 1000L;
		assertEquals(expectedSecondsFallback, ParsedDuration.parse("1h30x", TimeUnit.SECONDS).getMillis());
	}

	@Test
	@DisplayName("TimeUnit API: nanos/micros default units truncate to millis (number-only)")
	public void testTimeUnitSubMillisDefaultsTruncate() {
		// 999,999ns -> 0ms => empty (because ParsedDuration clamps <=0 to empty)
		assertTrue(ParsedDuration.parse("999999", TimeUnit.NANOSECONDS).isEmpty());

		// 1,500,000ns -> 1ms
		assertEquals(1L, ParsedDuration.parse("1500000", TimeUnit.NANOSECONDS).getMillis());

		// 999us -> 0ms => empty
		assertTrue(ParsedDuration.parse("999", TimeUnit.MICROSECONDS).isEmpty());

		// 1,500us -> 1ms
		assertEquals(1L, ParsedDuration.parse("1500", TimeUnit.MICROSECONDS).getMillis());
	}

	// ---------------------------------------------------------------------------------
	// A few sanity checks for parsing suffixes are consistent across both APIs
	// ---------------------------------------------------------------------------------

	@Test
	@DisplayName("Suffix parsing yields same millis regardless of default (Unit + TimeUnit)")
	public void testSuffixParsingIndependentOfDefault() {
		assertEquals(5000L, ParsedDuration.parse("5000ms", Unit.HOURS).getMillis());
		assertEquals(5000L, ParsedDuration.parse("5000ms", TimeUnit.HOURS).getMillis());

		assertEquals(60_000L, ParsedDuration.parse("60s", Unit.DAYS).getMillis());
		assertEquals(60_000L, ParsedDuration.parse("60s", TimeUnit.DAYS).getMillis());

		assertEquals(12 * 3_600_000L, ParsedDuration.parse("12h", Unit.SECONDS).getMillis());
		assertEquals(12 * 3_600_000L, ParsedDuration.parse("12h", TimeUnit.SECONDS).getMillis());
	}

	@Test
	@DisplayName("toString does not throw and reflects state")
	public void testToString() {
		ParsedDuration empty = ParsedDuration.empty();
		assertNotNull(empty.toString());

		ParsedDuration d = ParsedDuration.parse("5m", Unit.MINUTES);
		assertTrue(d.toString().contains("millis"));

		ParsedDuration dt = ParsedDuration.parse("5m", TimeUnit.MINUTES);
		assertTrue(dt.toString().contains("millis"));
	}
}
