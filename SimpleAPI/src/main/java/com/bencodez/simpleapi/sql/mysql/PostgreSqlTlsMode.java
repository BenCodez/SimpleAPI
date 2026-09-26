package com.bencodez.simpleapi.sql.mysql;

import java.util.Locale;

/** PostgreSQL JDBC TLS policy. LEGACY preserves the existing UseSSL setting. */
public enum PostgreSqlTlsMode {
	LEGACY(null),
	DISABLE("disable"),
	REQUIRE("require"),
	VERIFY_FULL("verify-full");

	private final String jdbcValue;

	PostgreSqlTlsMode(String jdbcValue) {
		this.jdbcValue = jdbcValue;
	}

	public String getJdbcValue() {
		return jdbcValue;
	}

	public static PostgreSqlTlsMode fromString(String raw) {
		if (raw == null || raw.trim().isEmpty()) {
			return LEGACY;
		}
		String normalized = raw.trim().replace('-', '_').toUpperCase(Locale.ROOT);
		try {
			return valueOf(normalized);
		} catch (IllegalArgumentException ex) {
			throw new IllegalArgumentException("Invalid PostgreSqlTlsMode; expected LEGACY, DISABLE, REQUIRE, or VERIFY_FULL", ex);
		}
	}
}
