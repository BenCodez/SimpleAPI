package com.bencodez.simpleapi.sql.mysql.config;

import org.bukkit.configuration.ConfigurationSection;

public class MysqlConfigSpigot extends MysqlConfig {

	public MysqlConfigSpigot(ConfigurationSection section) {
		// --- Basic Table Info ---
		setTablePrefix(section.getString("Prefix"));
		String tableName = section.getString("Name", "");
		if (tableName != null && !tableName.isEmpty()) {
			setTableName(tableName);
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

		// Recommended defaults (same as Bungee)
		setMinimumIdle(section.getInt("MinimumIdle", 2));             // 2 idle connections
		setIdleTimeoutMs(section.getLong("IdleTimeoutMs", 10 * 60_000L)); // 10 minutes
		setKeepaliveMs(section.getLong("KeepaliveMs", 5 * 60_000L));      // 5 minutes
		setValidationMs(section.getLong("ValidationMs", 5_000L));         // 5 seconds
		setLeakDetectMs(section.getLong("LeakDetectMs", 20_000L));        // 20 seconds
		setConnectionTimeout(section.getInt("ConnectionTimeout", 50_000)); // 50 seconds

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
