package com.bencodez.simpleapi.time;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parsed duration stored as fixed milliseconds.
 *
 * <p>
 * Supported formats (case-insensitive):
 * <ul>
 * <li>5000ms</li>
 * <li>60s</li>
 * <li>30m</li>
 * <li>12h</li>
 * <li>1d</li>
 * <li>2w</li>
 * <li>1mo (treated as a fixed 30 days)</li>
 * <li>Combined tokens: 1h30m, 2d12h, 1w2d3h4m5s6ms (spaces allowed between segments)</li>
 * <li>ISO-8601 (Duration.parse): PT30M, PT12H, P1D, etc (months/years not supported by Duration)</li>
 * <li>Plain number: "30" -> uses a configurable default unit</li>
 * </ul>
 *
 * <p>
 * This class stores durations as fixed milliseconds. Any parsing that uses
 * {@link TimeUnit#MICROSECONDS} or {@link TimeUnit#NANOSECONDS} will be truncated to milliseconds.
 * </p>
 */
public final class ParsedDuration {

	private static final Pattern PLAIN_NUMBER = Pattern.compile("^[0-9]+$");
	private static final Pattern VALUE_SUFFIX = Pattern.compile("^([0-9]+)\\s*([a-zA-Z]+)$");
	private static final Pattern SEGMENT = Pattern.compile("([0-9]+)\\s*([a-zA-Z]+)");

	private final long millis;

	private ParsedDuration(long millis) {
		this.millis = Math.max(0L, millis);
	}

	/**
	 * Creates an empty duration (0ms).
	 *
	 * @return empty duration
	 */
	public static ParsedDuration empty() {
		return new ParsedDuration(0L);
	}

	/**
	 * Creates a duration from milliseconds.
	 *
	 * @param millis milliseconds
	 * @return duration
	 */
	public static ParsedDuration ofMillis(long millis) {
		return new ParsedDuration(millis);
	}

	/**
	 * Gets the duration in fixed milliseconds.
	 *
	 * @return milliseconds
	 */
	public long getMillis() {
		return millis;
	}

	/**
	 * Checks if this duration is empty (<= 0ms).
	 *
	 * @return true if empty
	 */
	public boolean isEmpty() {
		return millis <= 0L;
	}

	/**
	 * Parses the input using MINUTES as the default unit for number-only strings.
	 *
	 * @param raw raw input string
	 * @return parsed duration (never null)
	 */
	@Deprecated
	public static ParsedDuration parse(String raw) {
		return parse(raw, TimeUnit.MINUTES);
	}

	/**
	 * If {@code raw} is just a number, returns the same value with {@code defaultUnit} applied,
	 * otherwise returns {@link #parse(String, TimeUnit)} result.
	 *
	 * @param raw raw input string
	 * @param defaultUnit default unit for plain numbers and unknown suffixes
	 * @return parsed duration (never null)
	 */
	public static ParsedDuration withDefaultUnit(String raw, TimeUnit defaultUnit) {
		return parse(raw, defaultUnit);
	}

	/**
	 * Parses the input using {@code defaultUnit} if the string is just a number.
	 *
	 * <p>
	 * Example: {@code parse("30", TimeUnit.MINUTES)} => 30 minutes
	 * </p>
	 *
	 * <p>
	 * Note: {@link ParsedDuration} stores fixed milliseconds. If you use
	 * {@link TimeUnit#MICROSECONDS} or {@link TimeUnit#NANOSECONDS} as the default unit,
	 * the parsed value will be truncated to milliseconds (via {@link TimeUnit#toMillis(long)}).
	 * </p>
	 *
	 * @param raw raw input string
	 * @param defaultUnit default unit for plain numbers and unknown suffixes
	 * @return parsed duration (never null)
	 */
	public static ParsedDuration parse(String raw, TimeUnit defaultUnit) {
		if (raw == null) {
			return empty();
		}
		String s = raw.trim();
		if (s.isEmpty()) {
			return empty();
		}

		Objects.requireNonNull(defaultUnit, "defaultUnit");

		// number-only -> default time unit
		if (PLAIN_NUMBER.matcher(s).matches()) {
			return applyDefaultTimeUnit(s, defaultUnit);
		}

		String lower = s.toLowerCase(Locale.ROOT);

		// ISO-8601 Duration support (PT30M, PT12H, P1D, etc)
		if (lower.startsWith("p")) {
			try {
				Duration d = Duration.parse(s.toUpperCase(Locale.ROOT));
				return ofMillis(d.toMillis());
			} catch (Exception ignored) {
				// fall through
			}
		}

		// Try combined token parsing first: "1h30m", "2d 12h", "1w2d3h", etc.
		ParsedDuration combined = parseCombinedTokens(lower, defaultUnit);
		if (combined != null) {
			return combined;
		}

		// parse single suffix form: "10m", "5000ms", "1mo"
		Matcher m = VALUE_SUFFIX.matcher(lower);
		if (!m.matches()) {
			// unknown -> best-effort fallback: try extract number, apply default unit
			String digits = extractLeadingDigits(lower);
			if (!digits.isEmpty()) {
				return applyDefaultTimeUnit(digits, defaultUnit);
			}
			return empty();
		}

		long value = safeParseLong(m.group(1));
		String suffix = m.group(2);

		if (value <= 0L) {
			return empty();
		}

		return applySuffix(value, suffix, defaultUnit);
	}

	/**
	 * Adds this duration to the provided instant using fixed millis.
	 *
	 * @param base base instant
	 * @return base + duration, or base if null/empty
	 */
	public Instant addTo(Instant base) {
		if (base == null || isEmpty()) {
			return base;
		}
		return base.plusMillis(millis);
	}

	/**
	 * Delay in milliseconds from now until now + this duration.
	 *
	 * Guaranteed to return at least 1ms when not empty.
	 *
	 * @return delay in milliseconds (>= 1)
	 */
	public long delayMillisFromNow() {
		if (isEmpty()) {
			return 1L;
		}
		return millis <= 0L ? 1L : millis;
	}

	/**
	 * Parses strings with multiple "value+suffix" segments like "1h30m" or "2d 12h",
	 * using {@link TimeUnit} as the default unit for unknown suffixes.
	 *
	 * <p>
	 * Returns null if the input does not look like a valid combined-token duration.
	 * </p>
	 *
	 * @param lower lowercase input
	 * @param defaultUnit default unit
	 * @return parsed duration, or null if not a combined-token string
	 */
	private static ParsedDuration parseCombinedTokens(String lower, TimeUnit defaultUnit) {
		Matcher seg = SEGMENT.matcher(lower);
		int count = 0;
		int pos = 0;
		long totalMillis = 0L;

		while (seg.find()) {
			// Ensure we only skip whitespace between segments.
			if (!onlyWhitespaceBetween(lower, pos, seg.start())) {
				return null;
			}
			pos = seg.end();
			count++;

			long value = safeParseLong(seg.group(1));
			String suffix = seg.group(2);
			if (value <= 0L) {
				return null;
			}

			ParsedDuration piece = applySuffix(value, suffix, defaultUnit);
			if (piece == null) {
				return null;
			}

			totalMillis = safeAddMillis(totalMillis, piece.millis);
		}

		// trailing non-whitespace means it's not a valid combined token string
		if (!onlyWhitespaceBetween(lower, pos, lower.length())) {
			return null;
		}

		// Must be at least 2 segments to count as combined tokens.
		if (count < 2) {
			return null;
		}

		ParsedDuration out = ofMillis(totalMillis);
		return out.isEmpty() ? empty() : out;
	}

	private static boolean onlyWhitespaceBetween(String s, int from, int to) {
		for (int i = from; i < to; i++) {
			if (!Character.isWhitespace(s.charAt(i))) {
				return false;
			}
		}
		return true;
	}

	private static long safeAddMillis(long a, long b) {
		long r = a + b;
		if (((a ^ r) & (b ^ r)) < 0) {
			return Long.MAX_VALUE;
		}
		return r;
	}

	private static ParsedDuration applyDefaultTimeUnit(String digits, TimeUnit unit) {
		long value = safeParseLong(digits);
		if (value <= 0L) {
			return empty();
		}
		// TimeUnit.toMillis includes overflow saturation to Long.MAX_VALUE
		return ofMillis(unit.toMillis(value));
	}

	private static ParsedDuration applySuffix(long value, String suffixRaw, TimeUnit defaultUnit) {
		String suffix = suffixRaw.toLowerCase(Locale.ROOT);

		// Milliseconds
		if (equalsAny(suffix, "ms", "msec", "msecs", "millisecond", "milliseconds")) {
			return ofMillis(value);
		}

		// Seconds
		if (equalsAny(suffix, "s", "sec", "secs", "second", "seconds")) {
			return ofMillis(TimeUnit.SECONDS.toMillis(value));
		}

		// Minutes
		if (equalsAny(suffix, "m", "min", "mins", "minute", "minutes")) {
			return ofMillis(TimeUnit.MINUTES.toMillis(value));
		}

		// Hours
		if (equalsAny(suffix, "h", "hr", "hrs", "hour", "hours")) {
			return ofMillis(TimeUnit.HOURS.toMillis(value));
		}

		// Days
		if (equalsAny(suffix, "d", "day", "days")) {
			return ofMillis(TimeUnit.DAYS.toMillis(value));
		}

		// Weeks (fixed 7 days)
		if (equalsAny(suffix, "w", "wk", "wks", "week", "weeks")) {
			return ofMillis(safeMul(TimeUnit.DAYS.toMillis(7L), value));
		}

		// Months (fixed 30 days)
		if (equalsAny(suffix, "mo", "mon", "mons", "month", "months")) {
			return ofMillis(safeMul(TimeUnit.DAYS.toMillis(30L), value));
		}

		// Unknown suffix -> fallback to default timeunit
		return applyDefaultTimeUnit(Long.toString(value), defaultUnit);
	}

	private static boolean equalsAny(String value, String... options) {
		for (String o : options) {
			if (o.equals(value)) {
				return true;
			}
		}
		return false;
	}

	private static long safeMul(long a, long b) {
		if (a == 0L || b == 0L) {
			return 0L;
		}
		if (a > Long.MAX_VALUE / b) {
			return Long.MAX_VALUE;
		}
		return a * b;
	}

	private static String extractLeadingDigits(String s) {
		int i = 0;
		while (i < s.length() && Character.isDigit(s.charAt(i))) {
			i++;
		}
		return i > 0 ? s.substring(0, i) : "";
	}

	private static long safeParseLong(String s) {
		try {
			return Long.parseLong(s);
		} catch (Exception e) {
			return 0L;
		}
	}

	@Override
	public String toString() {
		if (isEmpty()) {
			return "ParsedDuration{empty}";
		}
		return "ParsedDuration{millis=" + millis + "}";
	}
}
