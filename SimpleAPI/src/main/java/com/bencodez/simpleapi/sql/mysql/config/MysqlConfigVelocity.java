package com.bencodez.simpleapi.sql.mysql.config;

import com.bencodez.simpleapi.file.velocity.VelocityYMLFile;
import com.bencodez.simpleapi.sql.mysql.DbType;

public class MysqlConfigVelocity extends MysqlConfig {

	public MysqlConfigVelocity(String prePath, VelocityYMLFile config) {
		load(prePath, config);
	}

	public MysqlConfigVelocity(VelocityYMLFile config) {
		load("", config);
	}

	private void load(String prePath, VelocityYMLFile config) {
		// --- Basic Table Info ---
		setTablePrefix(config.getString(config.getNode(prePath, "Prefix"), ""));
		String name = config.getString(config.getNode(prePath, "Name"), "");
		if (name != null && !name.isEmpty()) {
			setTableName(name);
		}

		// --- Connection Info ---
		setHostName(config.getString(config.getNode(prePath, "Host"), ""));
		setPort(config.getInt(config.getNode(prePath, "Port"), 0));
		setUser(config.getString(config.getNode(prePath, "Username"), ""));
		setPass(config.getString(config.getNode(prePath, "Password"), ""));
		setDatabase(config.getString(config.getNode(prePath, "Database"), ""));

		// --- Pool Settings ---
		setLifeTime(config.getLong(config.getNode(prePath, "MaxLifeTime"), -1));
		setMaxThreads(config.getInt(config.getNode(prePath, "MaxConnections"), 1));
		if (getMaxThreads() < 1) {
			setMaxThreads(1);
		}

		// Recommended tuning defaults
		setMinimumIdle(config.getInt(config.getNode(prePath, "MinimumIdle"), 2)); // 2
		setIdleTimeoutMs(config.getLong(config.getNode(prePath, "IdleTimeoutMs"), 10 * 60_000L)); // 10 min
		setKeepaliveMs(config.getLong(config.getNode(prePath, "KeepaliveMs"), 5 * 60_000L)); // 5 min
		setValidationMs(config.getLong(config.getNode(prePath, "ValidationMs"), 5_000L)); // 5 s
		setLeakDetectMs(config.getLong(config.getNode(prePath, "LeakDetectMs"), 20_000L)); // 20 s
		setConnectionTimeout(config.getInt(config.getNode(prePath, "ConnectionTimeout"), 50_000)); // 50 s

		// --- Driver / DB Selection ---
		String dbTypeStr = config.getString(config.getNode(prePath, "DbType"), "");
		if (dbTypeStr != null && !dbTypeStr.isEmpty()) {
			setDbType(DbType.fromString(dbTypeStr));
		} else {
			boolean maria = config.getBoolean(config.getNode(prePath, "UseMariaDB"), false);
			setDbType(maria ? DbType.MARIADB : DbType.MYSQL);
		}

		// Optional explicit JDBC driver override
		setDriver(config.getString(config.getNode(prePath, "Driver"), ""));

		// --- Driver / Behavior Options ---
		setUseSSL(config.getBoolean(config.getNode(prePath, "UseSSL"), false));
		setPublicKeyRetrieval(config.getBoolean(config.getNode(prePath, "PublicKeyRetrieval"), false));
		setUseMariaDB(config.getBoolean(config.getNode(prePath, "UseMariaDB"), false));

		// --- Additional Settings ---
		setLine(config.getString(config.getNode(prePath, "Line"), ""));
		setDebug(config.getBoolean(config.getNode(prePath, "Debug"), false));
		setPoolName(config.getString(config.getNode(prePath, "PoolName"), ""));
	}
}
