package com.bencodez.simpleapi.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.bencodez.simpleapi.time.ParsedDuration;
import com.bencodez.simpleapi.time.ParsedDuration.DurationFormatLabels;

/**
 * Unit tests for ParsedDuration.
 */
public class ParsedDurationTest {

	@Test
	@DisplayName("Empty and null inputs return empty duration")
	public void testEmptyInputs() {
		assertTrue(ParsedDuration.parse(null).isEmpty());
		assertTrue(ParsedDuration.parse("").isEmpty());
		assertTrue(ParsedDuration.parse("   ").isEmpty());

		assertTrue(ParsedDuration.parse(null, TimeUnit.MINUTES).isEmpty());
		assertTrue(ParsedDuration.parse("", TimeUnit.MINUTES).isEmpty());
		assertTrue(ParsedDuration.parse("   ", TimeUnit.MINUTES).isEmpty());
	}

	@Test
	@DisplayName("ISO-8601 duration parsing works")
	public void testIso8601Duration() {
		assertEquals(30 * 60_000L, ParsedDuration.parse("PT30M", TimeUnit.MINUTES).getMillis());
		assertEquals(12 * 3_600_000L, ParsedDuration.parse("PT12H", TimeUnit.MINUTES).getMillis());
		assertEquals(86_400_000L, ParsedDuration.parse("P1D", TimeUnit.MINUTES).getMillis());
	}

	@Test
	@DisplayName("ISO-8601 months are rejected")
	public void testIsoMonthsRejected() {
		assertTrue(ParsedDuration.parse("P1M", TimeUnit.MINUTES).isEmpty());
	}

	@Test
	@DisplayName("Fixed month tokens behave")
	public void testFixedMonthTokens() {
		long expected3mo = 3L * 30L * 86_400_000L;
		assertEquals(expected3mo, ParsedDuration.parse("3mo", TimeUnit.MINUTES).getMillis());

		long expected = 1L * 30L * 86_400_000L + 2L * 86_400_000L;
		assertEquals(expected, ParsedDuration.parse("1mo2d", TimeUnit.MINUTES).getMillis());
	}

	@Test
	@DisplayName("Combined tokens allow spaces")
	public void testCombinedWithSpaces() {
		long expected = 2 * 86_400_000L + 12 * 3_600_000L;
		assertEquals(expected, ParsedDuration.parse("2d 12h", TimeUnit.MINUTES).getMillis());
	}

	@Test
	@DisplayName("Garbage input returns empty")
	public void testGarbageInput() {
		assertTrue(ParsedDuration.parse("abc", TimeUnit.MINUTES).isEmpty());
		assertTrue(ParsedDuration.parse("!@#$", TimeUnit.MINUTES).isEmpty());
	}

	@Test
	@DisplayName("Zero or negative values return empty")
	public void testZeroAndNegative() {
		assertTrue(ParsedDuration.parse("0m", TimeUnit.MINUTES).isEmpty());
		assertTrue(ParsedDuration.parse("-5m", TimeUnit.MINUTES).isEmpty());
	}

	@Test
	@DisplayName("Plain number uses default MINUTES when calling parse(String)")
	public void testPlainNumberDefaultMinutes() {
		ParsedDuration d = ParsedDuration.parse("30");
		assertEquals(30 * 60_000L, d.getMillis());
	}

	@Test
	@DisplayName("Plain number respects custom default TimeUnit")
	public void testPlainNumberCustomDefault() {
		assertEquals(15_000L, ParsedDuration.parse("15", TimeUnit.SECONDS).getMillis());
		assertEquals(2 * 3_600_000L, ParsedDuration.parse("2", TimeUnit.HOURS).getMillis());
	}

	@Test
	@DisplayName("Unknown suffix falls back to provided default TimeUnit")
	public void testUnknownSuffixFallbackSingle() {
		assertEquals(10 * 60_000L, ParsedDuration.parse("10x", TimeUnit.MINUTES).getMillis());
		assertEquals(10 * 1000L, ParsedDuration.parse("10x", TimeUnit.SECONDS).getMillis());
	}

	@Test
	@DisplayName("Unknown suffix falls back in combined tokens")
	public void testUnknownSuffixFallbackCombined() {
		long expectedMinutesFallback = 1 * 3_600_000L + 30 * 60_000L;
		assertEquals(expectedMinutesFallback, ParsedDuration.parse("1h30x", TimeUnit.MINUTES).getMillis());

		long expectedSecondsFallback = 1 * 3_600_000L + 30 * 1000L;
		assertEquals(expectedSecondsFallback, ParsedDuration.parse("1h30x", TimeUnit.SECONDS).getMillis());
	}

	@Test
	@DisplayName("Nanos and micros truncate to millis")
	public void testSubMillisDefaultsTruncate() {
		assertTrue(ParsedDuration.parse("999999", TimeUnit.NANOSECONDS).isEmpty());
		assertEquals(1L, ParsedDuration.parse("1500000", TimeUnit.NANOSECONDS).getMillis());

		assertTrue(ParsedDuration.parse("999", TimeUnit.MICROSECONDS).isEmpty());
		assertEquals(1L, ParsedDuration.parse("1500", TimeUnit.MICROSECONDS).getMillis());
	}

	@Test
	@DisplayName("Suffix parsing is independent of default TimeUnit")
	public void testSuffixParsingIndependentOfDefault() {
		assertEquals(5000L, ParsedDuration.parse("5000ms", TimeUnit.HOURS).getMillis());
		assertEquals(60_000L, ParsedDuration.parse("60s", TimeUnit.DAYS).getMillis());
		assertEquals(12 * 3_600_000L, ParsedDuration.parse("12h", TimeUnit.SECONDS).getMillis());
	}

	@Test
	@DisplayName("Factory methods create expected durations")
	public void testFactoryMethods() {
		assertEquals(0L, ParsedDuration.ofMillis(0).getMillis());
		assertEquals(1000L, ParsedDuration.ofSeconds(1).getMillis());
		assertEquals(2 * 60_000L, ParsedDuration.ofMinutes(2).getMillis());
		assertEquals(3 * 3_600_000L, ParsedDuration.ofHours(3).getMillis());
		assertEquals(4 * 86_400_000L, ParsedDuration.ofDays(4).getMillis());
		assertEquals(2 * 7 * 86_400_000L, ParsedDuration.ofWeeks(2).getMillis());
		assertEquals(3 * 30L * 86_400_000L, ParsedDuration.ofMonths(3).getMillis());
	}

	@Test
	@DisplayName("formatDuration formats standard compact output")
	public void testFormatDuration() {
		assertEquals("0s", ParsedDuration.formatDuration(0));
		assertEquals("1s", ParsedDuration.formatDuration(1000));
		assertEquals("1m 5s", ParsedDuration.formatDuration(65_000));
		assertEquals("1h 1m 1s", ParsedDuration.formatDuration(3_661_000));
		assertEquals("1d 2h 3m 4s",
				ParsedDuration.formatDuration((1L * 86_400_000L) + (2L * 3_600_000L) + (3L * 60_000L) + 4000L));
	}

	@Test
	@DisplayName("format uses instance millis and default labels")
	public void testInstanceFormat() {
		assertEquals("1h 30m", ParsedDuration.parse("1h30m", TimeUnit.MINUTES).format());
		assertEquals("2d", ParsedDuration.ofDays(2).format());
	}

	@Test
	@DisplayName("formatDuration uses 0s when less than one second")
	public void testFormatDurationSubSecond() {
		assertEquals("0s", ParsedDuration.formatDuration(500));
		assertEquals("0s", ParsedDuration.formatDuration(999));
	}

	@Test
	@DisplayName("formatShort returns largest whole unit")
	public void testFormatShort() {
		assertEquals("0s", ParsedDuration.formatShort(0));
		assertEquals("0s", ParsedDuration.formatShort(500));
		assertEquals("59s", ParsedDuration.formatShort(59_000));
		assertEquals("2m", ParsedDuration.formatShort(120_000));
		assertEquals("3h", ParsedDuration.formatShort(3 * 3_600_000L));
		assertEquals("4d", ParsedDuration.formatShort(4 * 86_400_000L));
		assertEquals("2w", ParsedDuration.formatShort(2 * 7 * 86_400_000L));
		assertEquals("3mo", ParsedDuration.formatShort(3 * 30L * 86_400_000L));
	}

	@Test
	@DisplayName("Custom labels can be applied per instance")
	public void testCustomLabelsPerInstance() {
		DurationFormatLabels labels = new DurationFormatLabels(" secs", " mins", " hours", " days", " weeks",
				" months");

		ParsedDuration duration = ParsedDuration.parse("1h30m", TimeUnit.MINUTES).withFormatLabels(labels);
		assertEquals("1 hours 30 mins", duration.format());

		assertEquals("2 days 5 secs",
				ParsedDuration.formatDuration((2L * 86_400_000L) + 5000L, labels));

		assertEquals("3 months", ParsedDuration.formatDuration(3L * 30L * 86_400_000L, labels));
	}

	@Test
	@DisplayName("Per instance labels do not affect other instances")
	public void testPerInstanceLabelsDoNotLeak() {
		DurationFormatLabels labels = new DurationFormatLabels(" sec", " min", " hr", " day", " wk", " mon");

		ParsedDuration first = ParsedDuration.ofHours(2).withFormatLabels(labels);
		ParsedDuration second = ParsedDuration.ofHours(2);

		assertEquals("2 hr", first.format());
		assertEquals("2h", second.format());
	}

	@Test
	@DisplayName("Null labels fall back to defaults")
	public void testNullLabelsFallback() {
		ParsedDuration duration = ParsedDuration.ofDays(1).withFormatLabels((DurationFormatLabels) null);
		assertEquals("1d", duration.format());
	}

	@Test
	@DisplayName("Blank or null label values fall back to defaults")
	public void testLabelFallbacks() {
		DurationFormatLabels labels = new DurationFormatLabels(null, "   ", " hr", " day", null, "");

		assertEquals("s", labels.getSeconds());
		assertEquals("m", labels.getMinutes());
		assertEquals("hr", labels.getHours());
		assertEquals("day", labels.getDays());
		assertEquals("w", labels.getWeeks());
		assertEquals("mo", labels.getMonths());
	}

	@Test
	@DisplayName("toString does not throw and reflects state")
	public void testToString() {
		ParsedDuration empty = ParsedDuration.empty();
		assertNotNull(empty.toString());

		ParsedDuration d = ParsedDuration.parse("5m", TimeUnit.MINUTES);
		assertTrue(d.toString().contains("millis"));
	}
}