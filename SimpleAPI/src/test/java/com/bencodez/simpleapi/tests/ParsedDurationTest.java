package com.bencodez.simpleapi.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.bencodez.simpleapi.time.ParsedDuration;
import com.bencodez.simpleapi.time.ParsedDuration.Unit;

public class ParsedDurationTest {

	@Test
	@DisplayName("Empty and null inputs return empty duration")
	public void testEmptyInputs() {
		assertTrue(ParsedDuration.parse(null).isEmpty());
		assertTrue(ParsedDuration.parse("").isEmpty());
		assertTrue(ParsedDuration.parse("   ").isEmpty());
	}

	@Test
	@DisplayName("Plain number uses default MINUTES")
	public void testPlainNumberDefaultMinutes() {
		ParsedDuration d = ParsedDuration.parse("30");
		assertEquals(30 * 60_000L, d.getMillis());
		assertEquals(0, d.getMonths());
	}

	@Test
	@DisplayName("Plain number respects custom default unit")
	public void testPlainNumberCustomUnit() {
		ParsedDuration seconds = ParsedDuration.withDefaultUnit("15", Unit.SECONDS);
		assertEquals(15_000L, seconds.getMillis());
		assertEquals(0, seconds.getMonths());

		ParsedDuration hours = ParsedDuration.withDefaultUnit("2", Unit.HOURS);
		assertEquals(2 * 3_600_000L, hours.getMillis());
		assertEquals(0, hours.getMonths());

		ParsedDuration months = ParsedDuration.withDefaultUnit("3", Unit.MONTHS);
		assertEquals(0L, months.getMillis());
		assertEquals(3, months.getMonths());
	}

	@Test
	@DisplayName("Milliseconds parsing")
	public void testMilliseconds() {
		ParsedDuration d = ParsedDuration.parse("5000ms");
		assertEquals(5000L, d.getMillis());
		assertEquals(0, d.getMonths());
	}

	@Test
	@DisplayName("Seconds parsing")
	public void testSeconds() {
		ParsedDuration d = ParsedDuration.parse("60s");
		assertEquals(60_000L, d.getMillis());
		assertEquals(0, d.getMonths());
	}

	@Test
	@DisplayName("Minutes parsing")
	public void testMinutes() {
		ParsedDuration d = ParsedDuration.parse("30m");
		assertEquals(30 * 60_000L, d.getMillis());
		assertEquals(0, d.getMonths());
	}

	@Test
	@DisplayName("Hours parsing")
	public void testHours() {
		ParsedDuration d = ParsedDuration.parse("12h");
		assertEquals(12 * 3_600_000L, d.getMillis());
		assertEquals(0, d.getMonths());
	}

	@Test
	@DisplayName("Days parsing")
	public void testDays() {
		ParsedDuration d = ParsedDuration.parse("1d");
		assertEquals(86_400_000L, d.getMillis());
		assertEquals(0, d.getMonths());
	}

	@Test
	@DisplayName("Weeks parsing")
	public void testWeeks() {
		ParsedDuration d = ParsedDuration.parse("2w");
		assertEquals(2 * 604_800_000L, d.getMillis());
		assertEquals(0, d.getMonths());
	}

	@Test
	@DisplayName("Months parsing uses calendar months (not millis)")
	public void testMonths() {
		ParsedDuration d = ParsedDuration.parse("3mo");
		assertEquals(0L, d.getMillis());
		assertEquals(3, d.getMonths());
	}

	@Test
	@DisplayName("Combined tokens: 1h30m")
	public void testCombinedHoursMinutes() {
		ParsedDuration d = ParsedDuration.parse("1h30m");
		assertEquals(1 * 3_600_000L + 30 * 60_000L, d.getMillis());
		assertEquals(0, d.getMonths());
	}

	@Test
	@DisplayName("Combined tokens allow spaces: 2d 12h")
	public void testCombinedWithSpaces() {
		ParsedDuration d = ParsedDuration.parse("2d 12h");
		assertEquals(2 * 86_400_000L + 12 * 3_600_000L, d.getMillis());
		assertEquals(0, d.getMonths());
	}

	@Test
	@DisplayName("Combined tokens: 1w2d3h4m5s6ms")
	public void testCombinedManySegments() {
		ParsedDuration d = ParsedDuration.parse("1w2d3h4m5s6ms");
		long expected =
				1 * 604_800_000L +
				2 * 86_400_000L +
				3 * 3_600_000L +
				4 * 60_000L +
				5 * 1000L +
				6;
		assertEquals(expected, d.getMillis());
		assertEquals(0, d.getMonths());
	}

	@Test
	@DisplayName("Combined tokens can mix months + fixed units: 1mo2d")
	public void testCombinedMonthsPlusDays() {
		ParsedDuration d = ParsedDuration.parse("1mo2d");
		assertEquals(1, d.getMonths());
		assertEquals(2 * 86_400_000L, d.getMillis());
	}

	@Test
	@DisplayName("ISO-8601 duration parsing (PT)")
	public void testIso8601Duration() {
		ParsedDuration d1 = ParsedDuration.parse("PT30M");
		assertEquals(30 * 60_000L, d1.getMillis());

		ParsedDuration d2 = ParsedDuration.parse("PT12H");
		assertEquals(12 * 3_600_000L, d2.getMillis());

		ParsedDuration d3 = ParsedDuration.parse("P1D");
		assertEquals(86_400_000L, d3.getMillis());
	}

	@Test
	@DisplayName("ISO-8601 months are rejected (P1M)")
	public void testIsoMonthsRejected() {
		ParsedDuration d = ParsedDuration.parse("P1M");
		assertTrue(d.isEmpty(), "ISO months should not be parsed as minutes or months");
	}

	@Test
	@DisplayName("Unknown suffix falls back to default unit")
	public void testUnknownSuffixFallback() {
		ParsedDuration d = ParsedDuration.parse("10x");
		assertEquals(10 * 60_000L, d.getMillis()); // default = minutes
		assertEquals(0, d.getMonths());
	}

	@Test
	@DisplayName("Unknown suffix inside combined tokens falls back to default unit")
	public void testUnknownSuffixFallbackCombined() {
		ParsedDuration d = ParsedDuration.parse("1h30x"); // 30x => 30 minutes by default
		assertEquals(1 * 3_600_000L + 30 * 60_000L, d.getMillis());
		assertEquals(0, d.getMonths());
	}

	@Test
	@DisplayName("Garbage input returns empty")
	public void testGarbageInput() {
		assertTrue(ParsedDuration.parse("abc").isEmpty());
		assertTrue(ParsedDuration.parse("!@#$").isEmpty());
	}

	@Test
	@DisplayName("Zero or negative values return empty")
	public void testZeroAndNegative() {
		assertTrue(ParsedDuration.parse("0m").isEmpty());
		assertTrue(ParsedDuration.parse("-5m").isEmpty());
		assertTrue(ParsedDuration.withDefaultUnit("0", Unit.SECONDS).isEmpty());
	}

	@Test
	@DisplayName("Large values do not overflow months")
	public void testLargeMonthValues() {
		ParsedDuration d = ParsedDuration.parse("9999999999mo");
		assertEquals(Integer.MAX_VALUE, d.getMonths());
	}

	@Test
	@DisplayName("toString does not throw and reflects state")
	public void testToString() {
		ParsedDuration empty = ParsedDuration.empty();
		assertNotNull(empty.toString());

		ParsedDuration d = ParsedDuration.parse("5m");
		assertTrue(d.toString().contains("millis"));
	}
}
