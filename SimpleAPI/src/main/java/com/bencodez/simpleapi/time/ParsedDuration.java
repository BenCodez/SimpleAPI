package com.bencodez.simpleapi.time;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parsed duration that preserves calendar-month units separately from fixed millis.
 *
 * <p>
 * Supported formats (case-insensitive):
 * <ul>
 *   <li>5000ms</li>
 *   <li>60s</li>
 *   <li>30m</li>
 *   <li>12h</li>
 *   <li>1d</li>
 *   <li>2w</li>
 *   <li>1mo (calendar months)</li>
 *   <li>Combined tokens: 1h30m, 2d12h, 1w2d3h4m5s6ms, 1mo2d (spaces allowed between segments)</li>
 *   <li>ISO-8601 (Duration.parse): PT30M, PT12H, P1D, etc (months/years not supported)</li>
 *   <li>Plain number: "30" -> uses a configurable default unit</li>
 * </ul>
 *
 * <p>
 * Notes:
 * <ul>
 *   <li>Calendar months are stored in {@link #months} and are NOT converted to millis.</li>
 *   <li>For ISO strings, {@code P1M} is NOT accepted (ambiguous vs minutes).</li>
 * </ul>
 */
public final class ParsedDuration {

	public enum Unit {
		MS("ms"),
		SECONDS("s"),
		MINUTES("m"),
		HOURS("h"),
		DAYS("d"),
		WEEKS("w"),
		MONTHS("mo");

		private final String suffix;

		Unit(String suffix) {
			this.suffix = suffix;
		}

		public String getSuffix() {
			return suffix;
		}
	}

	private static final Pattern PLAIN_NUMBER = Pattern.compile("^[0-9]+$");
	private static final Pattern VALUE_SUFFIX = Pattern.compile("^([0-9]+)\\s*([a-zA-Z]+)$");
	private static final Pattern SEGMENT = Pattern.compile("([0-9]+)\\s*([a-zA-Z]+)");

	private final long millis;
	private final int months;

	private ParsedDuration(long millis, int months) {
		this.millis = Math.max(0L, millis);
		this.months = Math.max(0, months);
	}

	public static ParsedDuration empty() {
		return new ParsedDuration(0L, 0);
	}

	public static ParsedDuration ofMillis(long millis) {
		return new ParsedDuration(millis, 0);
	}

	public static ParsedDuration ofMonths(int months) {
		return new ParsedDuration(0L, months);
	}

	public static ParsedDuration of(long millis, int months) {
		return new ParsedDuration(millis, months);
	}

	public long getMillis() {
		return millis;
	}

	public int getMonths() {
		return months;
	}

	public boolean isEmpty() {
		return millis <= 0L && months <= 0;
	}

	/**
	 * Parses the input using {@code defaultUnit} if the string is just a number.
	 *
	 * <p>Example: {@code parse("30", Unit.MINUTES)} => 30 minutes</p>
	 */
	public static ParsedDuration parse(String raw, Unit defaultUnit) {
		if (raw == null) {
			return empty();
		}
		String s = raw.trim();
		if (s.isEmpty()) {
			return empty();
		}

		// number-only -> default unit
		if (PLAIN_NUMBER.matcher(s).matches()) {
			return applyDefaultUnit(s, defaultUnit);
		}

		String lower = s.toLowerCase(Locale.ROOT);

		// ISO-8601 Duration support (PT30M, PT12H, P1D, etc)
		// We explicitly reject strings that look like "P...M" without "T" (month ambiguous)
		if (lower.startsWith("p")) {
			// reject potential month/year ISO (P1M, P2Y, etc)
			// allow PnD / PTnH / PTnM / PTnS etc, but not months/years.
			if (looksLikeIsoWithMonthsOrYears(lower)) {
				// Let it fall through to suffix parsing if it's like "1mo" (not ISO), otherwise empty.
			} else {
				try {
					Duration d = Duration.parse(s.toUpperCase(Locale.ROOT));
					return ofMillis(d.toMillis());
				} catch (Exception ignored) {
					// fall through
				}
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
				return applyDefaultUnit(digits, defaultUnit);
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
	 * Parses the input using MINUTES as the default unit for number-only strings.
	 */
	public static ParsedDuration parse(String raw) {
		return parse(raw, Unit.MINUTES);
	}

	/**
	 * If {@code raw} is just a number, returns the same value with {@code defaultUnit} applied,
	 * otherwise returns {@link #parse(String, Unit)} result.
	 *
	 * <p>This is the "set default unit value if it's just a number" helper.</p>
	 *
	 * <p>Example: {@code ParsedDuration.withDefaultUnit("30", Unit.SECONDS)} => 30s</p>
	 */
	public static ParsedDuration withDefaultUnit(String raw, Unit defaultUnit) {
		return parse(raw, defaultUnit);
	}

	private static ParsedDuration applyDefaultUnit(String digits, Unit unit) {
		Objects.requireNonNull(unit, "defaultUnit");
		long value = safeParseLong(digits);
		if (value <= 0L) {
			return empty();
		}

		switch (unit) {
		case MS:
			return ofMillis(value);
		case SECONDS:
			return ofMillis(value * 1000L);
		case MINUTES:
			return ofMillis(value * 60_000L);
		case HOURS:
			return ofMillis(value * 3_600_000L);
		case DAYS:
			return ofMillis(value * 86_400_000L);
		case WEEKS:
			return ofMillis(value * 604_800_000L);
		case MONTHS:
			return ofMonths((int) Math.min(Integer.MAX_VALUE, value));
		default:
			return empty();
		}
	}

	/**
	 * Parses strings with multiple "value+suffix" segments like "1h30m" or "2d 12h".
	 *
	 * <p>Returns null if the input does not look like a valid combined-token duration.</p>
	 */
	private static ParsedDuration parseCombinedTokens(String lower, Unit defaultUnit) {
		// Combined tokens must have at least two segments.
		Matcher seg = SEGMENT.matcher(lower);
		int count = 0;
		int pos = 0;
		long totalMillis = 0L;
		int totalMonths = 0;

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
			totalMonths = safeAddMonths(totalMonths, piece.months);
		}

		// trailing non-whitespace means it's not a valid combined token string
		if (!onlyWhitespaceBetween(lower, pos, lower.length())) {
			return null;
		}

		// Must be at least 2 segments to count as combined tokens.
		if (count < 2) {
			return null;
		}

		ParsedDuration out = of(totalMillis, totalMonths);
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

	private static int safeAddMonths(int a, int b) {
		long r = (long) a + (long) b;
		if (r > Integer.MAX_VALUE) {
			return Integer.MAX_VALUE;
		}
		return (int) r;
	}

	private static ParsedDuration applySuffix(long value, String suffixRaw, Unit defaultUnit) {
		String suffix = suffixRaw.toLowerCase(Locale.ROOT);

		switch (suffix) {
		case "ms":
		case "msec":
		case "msecs":
		case "millisecond":
		case "milliseconds":
			return ofMillis(value);
		case "s":
		case "sec":
		case "secs":
		case "second":
		case "seconds":
			return ofMillis(safeMul(value, 1000L));
		case "m":
		case "min":
		case "mins":
		case "minute":
		case "minutes":
			return ofMillis(safeMul(value, 60_000L));
		case "h":
		case "hr":
		case "hrs":
		case "hour":
		case "hours":
			return ofMillis(safeMul(value, 3_600_000L));
		case "d":
		case "day":
		case "days":
			return ofMillis(safeMul(value, 86_400_000L));
		case "w":
		case "wk":
		case "wks":
		case "week":
		case "weeks":
			return ofMillis(safeMul(value, 604_800_000L));
		case "mo":
		case "mon":
		case "mons":
		case "month":
		case "months":
			return ofMonths((int) Math.min(Integer.MAX_VALUE, value));
		default:
			// unknown suffix -> fallback to default unit
			return applyDefaultUnit(Long.toString(value), defaultUnit);
		}
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

	private static boolean looksLikeIsoWithMonthsOrYears(String lower) {
		if (!lower.startsWith("p")) {
			return false;
		}
		boolean hasT = lower.indexOf('t') >= 0;
		if (hasT) {
			return false;
		}
		return lower.indexOf('m') >= 0 || lower.indexOf('y') >= 0;
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
		return "ParsedDuration{millis=" + millis + ", months=" + months + "}";
	}
}
