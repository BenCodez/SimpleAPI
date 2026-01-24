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

	@Getter
	@Setter
	private DbType dbType = DbType.MYSQL;

	@Getter
	@Setter
	private String driver;

	@Getter
	@Setter
	private boolean useSSL;

	@Getter
	@Setter
	private boolean publicKeyRetrieval;

	@Getter
	@Setter
	private boolean useMariaDB;

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
