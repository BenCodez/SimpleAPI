package com.bencodez.simpleapi.time;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import lombok.Getter;
import lombok.Setter;

/**
 * Parsed duration stored as fixed milliseconds.
 *
 * Supported formats (case-insensitive): - 5000ms - 60s - 30m - 12h - 1d - 2w -
 * 1mo (treated as a fixed 30 days) - Combined tokens: 1h30m, 2d12h,
 * 1w2d3h4m5s6ms - ISO-8601 durations such as PT30M, PT12H, P1D - Plain number
 * such as "30" using a configurable default unit
 *
 * This class stores durations as fixed milliseconds.
 */
@Getter
@Setter
public final class ParsedDuration {

	private static final Pattern PLAIN_NUMBER = Pattern.compile("^[0-9]+$");
	private static final Pattern VALUE_SUFFIX = Pattern.compile("^([0-9]+)\\s*([a-zA-Z]+)$");
	private static final Pattern SEGMENT = Pattern.compile("([0-9]+)\\s*([a-zA-Z]+)");

	private static final DurationFormatLabels DEFAULT_LABELS = new DurationFormatLabels("s", "m", "h", "d", "w", "mo");

	private final long millis;

	/**
	 * Formatting labels for this duration instance.
	 */
	private DurationFormatLabels formatLabels;

	private ParsedDuration(long millis) {
		this.millis = Math.max(0L, millis);
		this.formatLabels = DEFAULT_LABELS.copy();
	}

	/**
	 * Label container used for formatting durations.
	 */
	@Getter
	@Setter
	public static class DurationFormatLabels {
		private String seconds;
		private String minutes;
		private String hours;
		private String days;
		private String weeks;
		private String months;

		/**
		 * Creates a new label set.
		 *
		 * @param seconds Seconds label
		 * @param minutes Minutes label
		 * @param hours   Hours label
		 * @param days    Days label
		 * @param weeks   Weeks label
		 * @param months  Months label
		 */
		public DurationFormatLabels(String seconds, String minutes, String hours, String days, String weeks,
				String months) {
			this.seconds = sanitizeLabel(seconds, "s");
			this.minutes = sanitizeLabel(minutes, "m");
			this.hours = sanitizeLabel(hours, "h");
			this.days = sanitizeLabel(days, "d");
			this.weeks = sanitizeLabel(weeks, "w");
			this.months = sanitizeLabel(months, "mo");
		}

		/**
		 * Creates a copy of this label set.
		 *
		 * @return Copied labels
		 */
		public DurationFormatLabels copy() {
			return new DurationFormatLabels(seconds, minutes, hours, days, weeks, months);
		}
	}

	/**
	 * Creates an empty duration.
	 *
	 * @return Empty duration
	 */
	public static ParsedDuration empty() {
		return new ParsedDuration(0L);
	}

	/**
	 * Creates a duration from milliseconds.
	 *
	 * @param millis Milliseconds
	 * @return Duration
	 */
	public static ParsedDuration ofMillis(long millis) {
		return new ParsedDuration(millis);
	}

	/**
	 * Creates a duration from seconds.
	 *
	 * @param seconds Seconds
	 * @return Duration
	 */
	public static ParsedDuration ofSeconds(long seconds) {
		return ofMillis(TimeUnit.SECONDS.toMillis(seconds));
	}

	/**
	 * Creates a duration from minutes.
	 *
	 * @param minutes Minutes
	 * @return Duration
	 */
	public static ParsedDuration ofMinutes(long minutes) {
		return ofMillis(TimeUnit.MINUTES.toMillis(minutes));
	}

	/**
	 * Creates a duration from hours.
	 *
	 * @param hours Hours
	 * @return Duration
	 */
	public static ParsedDuration ofHours(long hours) {
		return ofMillis(TimeUnit.HOURS.toMillis(hours));
	}

	/**
	 * Creates a duration from days.
	 *
	 * @param days Days
	 * @return Duration
	 */
	public static ParsedDuration ofDays(long days) {
		return ofMillis(TimeUnit.DAYS.toMillis(days));
	}

	/**
	 * Creates a duration from weeks.
	 *
	 * @param weeks Weeks
	 * @return Duration
	 */
	public static ParsedDuration ofWeeks(long weeks) {
		return ofMillis(safeMul(TimeUnit.DAYS.toMillis(7L), weeks));
	}

	/**
	 * Creates a duration from fixed 30-day months.
	 *
	 * @param months Months
	 * @return Duration
	 */
	public static ParsedDuration ofMonths(long months) {
		return ofMillis(safeMul(TimeUnit.DAYS.toMillis(30L), months));
	}

	/**
	 * Creates default formatting labels.
	 *
	 * @return Default labels
	 */
	public static DurationFormatLabels defaultLabels() {
		return DEFAULT_LABELS.copy();
	}

	/**
	 * Sets this instance's labels.
	 *
	 * @param labels Labels to use
	 * @return This instance
	 */
	public ParsedDuration withFormatLabels(DurationFormatLabels labels) {
		this.formatLabels = labels == null ? DEFAULT_LABELS.copy() : labels.copy();
		return this;
	}

	/**
	 * Sets this instance's labels using individual values.
	 *
	 * @param seconds Seconds label
	 * @param minutes Minutes label
	 * @param hours   Hours label
	 * @param days    Days label
	 * @param weeks   Weeks label
	 * @param months  Months label
	 * @return This instance
	 */
	public ParsedDuration withFormatLabels(String seconds, String minutes, String hours, String days, String weeks,
			String months) {
		this.formatLabels = new DurationFormatLabels(seconds, minutes, hours, days, weeks, months);
		return this;
	}

	/**
	 * Parses the input using MINUTES as the default unit for number-only strings.
	 *
	 * @param raw Raw input
	 * @return Parsed duration
	 */
	@Deprecated
	public static ParsedDuration parse(String raw) {
		return parse(raw, TimeUnit.MINUTES);
	}

	/**
	 * Parses the input using the provided default unit for number-only strings.
	 *
	 * @param raw         Raw input
	 * @param defaultUnit Default unit
	 * @return Parsed duration
	 */
	public static ParsedDuration withDefaultUnit(String raw, TimeUnit defaultUnit) {
		return parse(raw, defaultUnit);
	}

	/**
	 * Parses the input using the provided default unit for number-only strings.
	 *
	 * @param raw         Raw input
	 * @param defaultUnit Default unit
	 * @return Parsed duration
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

		if (PLAIN_NUMBER.matcher(s).matches()) {
			return applyDefaultTimeUnit(s, defaultUnit);
		}

		String lower = s.toLowerCase(Locale.ROOT);

		if (lower.startsWith("p")) {
			try {
				Duration d = Duration.parse(s.toUpperCase(Locale.ROOT));
				return ofMillis(d.toMillis());
			} catch (Exception ignored) {
				// Ignore and fall through to other parsing modes.
			}
		}

		ParsedDuration combined = parseCombinedTokens(lower, defaultUnit);
		if (combined != null) {
			return combined;
		}

		Matcher m = VALUE_SUFFIX.matcher(lower);
		if (!m.matches()) {
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
	 * Adds this duration to an instant.
	 *
	 * @param base Base instant
	 * @return Updated instant
	 */
	public Instant addTo(Instant base) {
		if (base == null || isEmpty()) {
			return base;
		}
		return base.plusMillis(millis);
	}

	/**
	 * Gets delay in milliseconds from now for this duration.
	 *
	 * @return Delay in milliseconds
	 */
	public long delayMillisFromNow() {
		if (isEmpty()) {
			return 1L;
		}
		return millis <= 0L ? 1L : millis;
	}

	/**
	 * Checks if this duration is empty.
	 *
	 * @return True if empty
	 */
	public boolean isEmpty() {
		return millis <= 0L;
	}

	/**
	 * Formats this duration using this instance's labels.
	 *
	 * @return Formatted duration
	 */
	public String format() {
		return formatDuration(this.millis, this.formatLabels);
	}

	/**
	 * Formats this duration using the provided labels.
	 *
	 * @param labels Labels to use
	 * @return Formatted duration
	 */
	public String format(DurationFormatLabels labels) {
		return formatDuration(this.millis, labels);
	}

	/**
	 * Formats milliseconds using default labels.
	 *
	 * @param millis Milliseconds
	 * @return Formatted duration
	 */
	public static String formatDuration(long millis) {
		return formatDuration(millis, DEFAULT_LABELS);
	}

	/**
	 * Formats milliseconds using the provided labels.
	 *
	 * @param millis Milliseconds
	 * @param labels Labels
	 * @return Formatted duration
	 */
	public static String formatDuration(long millis, DurationFormatLabels labels) {
		DurationFormatLabels use = labels == null ? DEFAULT_LABELS : labels;

		if (millis <= 0) {
			return "0" + use.getSeconds();
		}

		long remaining = millis;

		long monthMillis = TimeUnit.DAYS.toMillis(30L);
		long weekMillis = TimeUnit.DAYS.toMillis(7L);
		long dayMillis = TimeUnit.DAYS.toMillis(1L);
		long hourMillis = TimeUnit.HOURS.toMillis(1L);
		long minuteMillis = TimeUnit.MINUTES.toMillis(1L);
		long secondMillis = TimeUnit.SECONDS.toMillis(1L);

		long months = remaining / monthMillis;
		remaining %= monthMillis;

		long weeks = remaining / weekMillis;
		remaining %= weekMillis;

		long days = remaining / dayMillis;
		remaining %= dayMillis;

		long hours = remaining / hourMillis;
		remaining %= hourMillis;

		long minutes = remaining / minuteMillis;
		remaining %= minuteMillis;

		long seconds = remaining / secondMillis;

		StringBuilder sb = new StringBuilder();

		appendPart(sb, months, use.getMonths());
		appendPart(sb, weeks, use.getWeeks());
		appendPart(sb, days, use.getDays());
		appendPart(sb, hours, use.getHours());
		appendPart(sb, minutes, use.getMinutes());
		appendPart(sb, seconds, use.getSeconds());

		if (sb.length() == 0) {
			return "0" + use.getSeconds();
		}

		return sb.toString().trim();
	}

	/**
	 * Formats milliseconds using the largest whole unit with default labels.
	 *
	 * @param millis Milliseconds
	 * @return Short formatted duration
	 */
	public static String formatShort(long millis) {
		return formatShort(millis, DEFAULT_LABELS);
	}

	/**
	 * Formats milliseconds using the largest whole unit with the provided labels.
	 *
	 * @param millis Milliseconds
	 * @param labels Labels
	 * @return Short formatted duration
	 */
	public static String formatShort(long millis, DurationFormatLabels labels) {
		DurationFormatLabels use = labels == null ? DEFAULT_LABELS : labels;

		if (millis <= 0) {
			return "0" + use.getSeconds();
		}

		long monthMillis = TimeUnit.DAYS.toMillis(30L);
		long weekMillis = TimeUnit.DAYS.toMillis(7L);
		long dayMillis = TimeUnit.DAYS.toMillis(1L);
		long hourMillis = TimeUnit.HOURS.toMillis(1L);
		long minuteMillis = TimeUnit.MINUTES.toMillis(1L);
		long secondMillis = TimeUnit.SECONDS.toMillis(1L);

		if (millis >= monthMillis) {
			return (millis / monthMillis) + use.getMonths();
		}
		if (millis >= weekMillis) {
			return (millis / weekMillis) + use.getWeeks();
		}
		if (millis >= dayMillis) {
			return (millis / dayMillis) + use.getDays();
		}
		if (millis >= hourMillis) {
			return (millis / hourMillis) + use.getHours();
		}
		if (millis >= minuteMillis) {
			return (millis / minuteMillis) + use.getMinutes();
		}
		if (millis >= secondMillis) {
			return (millis / secondMillis) + use.getSeconds();
		}
		return "0" + use.getSeconds();
	}

	private static ParsedDuration parseCombinedTokens(String lower, TimeUnit defaultUnit) {
		Matcher seg = SEGMENT.matcher(lower);
		int count = 0;
		int pos = 0;
		long totalMillis = 0L;

		while (seg.find()) {
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

		if (!onlyWhitespaceBetween(lower, pos, lower.length())) {
			return null;
		}

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
		return ofMillis(unit.toMillis(value));
	}

	private static ParsedDuration applySuffix(long value, String suffixRaw, TimeUnit defaultUnit) {
		String suffix = suffixRaw.toLowerCase(Locale.ROOT);

		if (equalsAny(suffix, "ms", "msec", "msecs", "millisecond", "milliseconds")) {
			return ofMillis(value);
		}

		if (equalsAny(suffix, "s", "sec", "secs", "second", "seconds")) {
			return ofMillis(TimeUnit.SECONDS.toMillis(value));
		}

		if (equalsAny(suffix, "m", "min", "mins", "minute", "minutes")) {
			return ofMillis(TimeUnit.MINUTES.toMillis(value));
		}

		if (equalsAny(suffix, "h", "hr", "hrs", "hour", "hours")) {
			return ofMillis(TimeUnit.HOURS.toMillis(value));
		}

		if (equalsAny(suffix, "d", "day", "days")) {
			return ofMillis(TimeUnit.DAYS.toMillis(value));
		}

		if (equalsAny(suffix, "w", "wk", "wks", "week", "weeks")) {
			return ofMillis(safeMul(TimeUnit.DAYS.toMillis(7L), value));
		}

		if (equalsAny(suffix, "mo", "mon", "mons", "month", "months")) {
			return ofMillis(safeMul(TimeUnit.DAYS.toMillis(30L), value));
		}

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

	private static void appendPart(StringBuilder sb, long value, String label) {
		if (value <= 0) {
			return;
		}
		if (sb.length() > 0) {
			sb.append(' ');
		}
		sb.append(value);

		if (label != null && !label.isEmpty()) {
			if (label.length() > 1) {
				sb.append(' ');
			}
			sb.append(label);
		}
	}

	private static String sanitizeLabel(String value, String def) {
		if (value == null) {
			return def;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? def : trimmed;
	}

	@Override
	public String toString() {
		if (isEmpty()) {
			return "ParsedDuration{empty}";
		}
		return "ParsedDuration{millis=" + millis + "}";
	}
}