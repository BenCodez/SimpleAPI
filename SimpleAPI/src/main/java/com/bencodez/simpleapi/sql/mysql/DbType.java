package com.bencodez.simpleapi.sql.mysql;

import java.util.Locale;

public enum DbType {
	MYSQL("mysql"),
	MARIADB("mariadb"),
	POSTGRESQL("postgresql");

	private final String id;

	DbType(String id) {
		this.id = id;
	}

	public String getId() {
		return id;
	}

	public boolean isMySqlFamily() {
		return this == MYSQL || this == MARIADB;
	}

	public static DbType fromString(String value) {
		if (value == null) {
			return MYSQL;
		}

		String v = value.trim().toLowerCase(Locale.ROOT);

		switch (v) {
		case "mysql":
			return MYSQL;
		case "mariadb":
		case "maria":
			return MARIADB;
		case "postgres":
		case "postgresql":
		case "pg":
			return POSTGRESQL;
		default:
			throw new IllegalArgumentException("Unknown DbType: " + value);
		}
	}
}
