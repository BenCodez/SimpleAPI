package com.bencodez.simpleapi.sql.mysql.config;

import com.bencodez.simpleapi.file.velocity.VelocityYMLFile;

public class MysqlConfigVelocity extends MysqlConfig {

	public MysqlConfigVelocity(String prePath, VelocityYMLFile config) {
		// --- Basic Table Info ---
		setTablePrefix(config.getString(config.getNode(prePath, "Prefix"), ""));
		String name = config.getString(config.getNode(prePath, "Name"), "");
		if (name != null && !name.isEmpty())
			setTableName(name);

		// --- Connection Info ---
		setHostName(config.getString(config.getNode(prePath, "Host"), ""));
		setPort(config.getInt(config.getNode(prePath, "Port"), 0));
		setUser(config.getString(config.getNode(prePath, "Username"), ""));
		setPass(config.getString(config.getNode(prePath, "Password"), ""));
		setDatabase(config.getString(config.getNode(prePath, "Database"), ""));

		// --- Pool Settings ---
		setLifeTime(config.getLong(config.getNode(prePath, "MaxLifeTime"), -1));
		setMaxThreads(config.getInt(config.getNode(prePath, "MaxConnections"), 1));
		if (getMaxThreads() < 1)
			setMaxThreads(1);

		// Recommended tuning defaults
		setMinimumIdle(config.getInt(config.getNode(prePath, "MinimumIdle"), 2)); // 2
		setIdleTimeoutMs(config.getLong(config.getNode(prePath, "IdleTimeoutMs"), 10 * 60_000L)); // 10 min
		setKeepaliveMs(config.getLong(config.getNode(prePath, "KeepaliveMs"), 5 * 60_000L)); // 5 min
		setValidationMs(config.getLong(config.getNode(prePath, "ValidationMs"), 5_000L)); // 5 s
		setLeakDetectMs(config.getLong(config.getNode(prePath, "LeakDetectMs"), 20_000L)); // 20 s
		setConnectionTimeout(config.getInt(config.getNode(prePath, "ConnectionTimeout"), 50_000)); // 50 s

		// --- Driver / Behavior Options ---
		setUseSSL(config.getBoolean(config.getNode(prePath, "UseSSL"), false));
		setPublicKeyRetrieval(config.getBoolean(config.getNode(prePath, "PublicKeyRetrieval"), false));
		setUseMariaDB(config.getBoolean(config.getNode(prePath, "UseMariaDB"), false));

		// --- Additional Settings ---
		setLine(config.getString(config.getNode(prePath, "Line"), ""));
		setDebug(config.getBoolean(config.getNode(prePath, "Debug"), false));
		setPoolName(config.getString(config.getNode(prePath, "PoolName"), ""));
	}

	public MysqlConfigVelocity(VelocityYMLFile config) {
		// --- Basic Table Info ---
		setTablePrefix(config.getString(config.getNode("Prefix"), ""));
		String name = config.getString(config.getNode("Name"), "");
		if (name != null && !name.isEmpty())
			setTableName(name);

		// --- Connection Info ---
		setHostName(config.getString(config.getNode("Host"), ""));
		setPort(config.getInt(config.getNode("Port"), 0));
		setUser(config.getString(config.getNode("Username"), ""));
		setPass(config.getString(config.getNode("Password"), ""));
		setDatabase(config.getString(config.getNode("Database"), ""));

		// --- Pool Settings ---
		setLifeTime(config.getLong(config.getNode("MaxLifeTime"), -1));
		setMaxThreads(config.getInt(config.getNode("MaxConnections"), 1));
		if (getMaxThreads() < 1)
			setMaxThreads(1);

		// Recommended tuning defaults
		setMinimumIdle(config.getInt(config.getNode("MinimumIdle"), 2)); // 2
		setIdleTimeoutMs(config.getLong(config.getNode("IdleTimeoutMs"), 10 * 60_000L)); // 10 min
		setKeepaliveMs(config.getLong(config.getNode("KeepaliveMs"), 5 * 60_000L)); // 5 min
		setValidationMs(config.getLong(config.getNode("ValidationMs"), 5_000L)); // 5 s
		setLeakDetectMs(config.getLong(config.getNode("LeakDetectMs"), 20_000L)); // 20 s
		setConnectionTimeout(config.getInt(config.getNode("ConnectionTimeout"), 50_000)); // 50 s

		// --- Driver / Behavior Options ---
		setUseSSL(config.getBoolean(config.getNode("UseSSL"), false));
		setPublicKeyRetrieval(config.getBoolean(config.getNode("PublicKeyRetrieval"), false));
		setUseMariaDB(config.getBoolean(config.getNode("UseMariaDB"), false));

		// --- Additional Settings ---
		setLine(config.getString(config.getNode("Line"), ""));
		setDebug(config.getBoolean(config.getNode("Debug"), false));
		setPoolName(config.getString(config.getNode("PoolName"), ""));
	}
}
