package com.bencodez.simpleapi.sql.mysql.config;

import java.util.Objects;
import com.bencodez.simpleapi.file.config.ConfigView;
import com.bencodez.simpleapi.sql.mysql.DbType;

/** Platform-neutral snapshot of the existing MySQL section keys and defaults. */
public final class MysqlConfigView extends MysqlConfig {
    public MysqlConfigView(ConfigView section) {
        Objects.requireNonNull(section, "section");
        setTablePrefix(section.getString("Prefix", null));
        String tableName = section.getString("Name", "");
        if (tableName != null && !tableName.isEmpty()) setTableName(tableName);
        setHostName(section.getString("Host", null));
        setPort(section.getInt("Port", 0));
        setUser(section.getString("Username", null));
        setPass(section.getString("Password", null));
        setDatabase(section.getString("Database", null));
        setLifeTime(section.getLong("MaxLifeTime", -1));
        setMaxThreads(Math.max(1, section.getInt("MaxConnections", 1)));
        setMinimumIdle(section.getInt("MinimumIdle", 2));
        setIdleTimeoutMs(section.getLong("IdleTimeoutMs", 10 * 60_000L));
        setKeepaliveMs(section.getLong("KeepaliveMs", 5 * 60_000L));
        setValidationMs(section.getLong("ValidationMs", 5_000L));
        setLeakDetectMs(section.getLong("LeakDetectMs", 20_000L));
        setConnectionTimeout(section.getInt("ConnectionTimeout", 50_000));
        String type = section.getString("DbType", "");
        setDbType(type != null && !type.isEmpty() ? DbType.fromString(type)
                : section.getBoolean("UseMariaDB", false) ? DbType.MARIADB : DbType.MYSQL);
        setDriver(section.getString("Driver", ""));
        setUseSSL(section.getBoolean("UseSSL", false));
        setPublicKeyRetrieval(section.getBoolean("PublicKeyRetrieval", false));
        setUseMariaDB(section.getBoolean("UseMariaDB", false));
        setLine(section.getString("Line", ""));
        setDebug(section.getBoolean("Debug", false));
        setPoolName(section.getString("PoolName", ""));
    }
}
