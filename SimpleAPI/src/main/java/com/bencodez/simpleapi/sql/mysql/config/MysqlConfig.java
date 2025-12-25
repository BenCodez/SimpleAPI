package com.bencodez.simpleapi.sql.mysql.config;

import com.bencodez.simpleapi.sql.mysql.DbType;

import lombok.Getter;
import lombok.Setter;

public class MysqlConfig {

	// --- Basic Table Info ---
	@Getter
	@Setter
	private String tablePrefix;

	@Getter
	@Setter
	private String tableName;

	// --- Connection Info ---
	@Getter
	@Setter
	private String hostName;

	@Getter
	@Setter
	private int port;

	@Getter
	@Setter
	private String user;

	@Getter
	@Setter
	private String pass;

	@Getter
	@Setter
	private String database;

	// --- Pool Settings ---
	@Getter
	@Setter
	private int maxThreads;

	@Getter
	@Setter
	private int minimumIdle;

	@Getter
	@Setter
	private long lifeTime;

	@Getter
	@Setter
	private long idleTimeoutMs;

	@Getter
	@Setter
	private long keepaliveMs;

	@Getter
	@Setter
	private long validationMs;

	@Getter
	@Setter
	private long leakDetectMs;

	@Getter
	@Setter
	private int connectionTimeout;

	// --- Driver / DB Selection ---
	/**
	 * Database type to use. MYSQL | MARIADB | POSTGRESQL
	 */
	@Getter
	@Setter
	private DbType dbType = DbType.MYSQL;

	/**
	 * Optional explicit JDBC driver override. Examples: - com.mysql.cj.jdbc.Driver
	 * - org.mariadb.jdbc.Driver - org.postgresql.Driver
	 */
	@Getter
	@Setter
	private String driver;

	/**
	 * Whether to use SSL for the DB connection.
	 */
	@Getter
	@Setter
	private boolean useSSL;

	/**
	 * MySQL-only option (ignored for PostgreSQL).
	 */
	@Getter
	@Setter
	private boolean publicKeyRetrieval;

	/**
	 * Legacy convenience flag. If set and dbType is MYSQL, ConnectionManager will
	 * treat it as MARIADB.
	 */
	@Getter
	@Setter
	private boolean useMariaDB;

	// --- Additional Settings ---
	/**
	 * Extra JDBC parameters appended to the URL. Example: - MySQL: &useUnicode=true
	 * - Postgres: &sslmode=require
	 */
	@Getter
	@Setter
	private String line;

	@Getter
	@Setter
	private boolean debug;

	@Getter
	@Setter
	private String poolName;

	// --- Utility ---
	public boolean hasTableNameSet() {
		return tableName != null && !tableName.isEmpty();
	}
}
