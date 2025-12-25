package com.bencodez.simpleapi.sql.mysql.config;

import com.bencodez.simpleapi.sql.mysql.DbType;

import net.md_5.bungee.config.Configuration;

public class MysqlConfigBungee extends MysqlConfig {

	public MysqlConfigBungee(Configuration section) {
		// --- Basic Table Info ---
		setTablePrefix(section.getString("Prefix"));
		if (!section.getString("Name", "").isEmpty()) {
			setTableName(section.getString("Name", ""));
		}

		// --- Connection Info ---
		setHostName(section.getString("Host"));
		setPort(section.getInt("Port"));
		setUser(section.getString("Username"));
		setPass(section.getString("Password"));
		setDatabase(section.getString("Database"));

		// --- Pool Settings ---
		setLifeTime(section.getLong("MaxLifeTime", -1));
		setMaxThreads(section.getInt("MaxConnections", 1));
		if (getMaxThreads() < 1) {
			setMaxThreads(1);
		}

		// Recommended tuning defaults
		setMinimumIdle(section.getInt("MinimumIdle", 2)); // 2
		setIdleTimeoutMs(section.getLong("IdleTimeoutMs", 10 * 60_000L)); // 10 min
		setKeepaliveMs(section.getLong("KeepaliveMs", 5 * 60_000L)); // 5 min
		setValidationMs(section.getLong("ValidationMs", 5_000L)); // 5 s
		setLeakDetectMs(section.getLong("LeakDetectMs", 20_000L)); // 20 s
		setConnectionTimeout(section.getInt("ConnectionTimeout", 50_000)); // 50 s

		// --- Driver / DB Selection ---
		// DbType: MYSQL | MARIADB | POSTGRESQL (also accepts mysql/mariadb/postgres/postgresql/pg)
		String dbTypeStr = section.getString("DbType", "");
		if (dbTypeStr != null && !dbTypeStr.isEmpty()) {
			setDbType(DbType.fromString(dbTypeStr));
		} else {
			// fallback to legacy flag default
			setDbType(section.getBoolean("UseMariaDB", false) ? DbType.MARIADB : DbType.MYSQL);
		}

		// Optional explicit JDBC driver override
		setDriver(section.getString("Driver", ""));

		// --- Driver / Behavior Options ---
		setUseSSL(section.getBoolean("UseSSL", false));
		setPublicKeyRetrieval(section.getBoolean("PublicKeyRetrieval", false));
		setUseMariaDB(section.getBoolean("UseMariaDB", false));

		// --- Additional Settings ---
		setLine(section.getString("Line", ""));
		setDebug(section.getBoolean("Debug", false));
		setPoolName(section.getString("PoolName", ""));
	}
}
