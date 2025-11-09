package com.bencodez.simpleapi.sql.mysql;

import java.sql.Connection;
import java.sql.SQLException;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import lombok.Getter;
import lombok.Setter;

public class ConnectionManager {

	@Getter
	@Setter
	private int connectionTimeout = 50_000;
	@Getter
	@Setter
	private String database;
	@Getter
	@Setter
	private HikariDataSource dataSource;
	@Getter
	@Setter
	private String host;
	@Getter
	@Setter
	private int maximumPoolsize = 5;

	// Tunable timings
	@Getter
	@Setter
	private long maxLifetimeMs = 0L; // <=0 -> default 25m
	@Getter
	@Setter
	private long idleTimeoutMs = 10 * 60_000L; // 10m
	@Getter
	@Setter
	private long keepaliveMs = 5 * 60_000L; // 5m
	@Getter
	@Setter
	private long validationMs = 5_000L; // 5s
	@Getter
	@Setter
	private long leakDetectMs = 20_000L; // 20s
	@Getter
	@Setter
	private int minimumIdle = -1; // -1 = auto

	@Getter
	@Setter
	private String password;
	@Getter
	@Setter
	private String port;
	@Getter
	@Setter
	private boolean publicKeyRetrieval;
	@Getter
	@Setter
	private String str = "";
	@Getter
	@Setter
	private String username;
	@Getter
	@Setter
	private boolean useSSL = false;
	@Getter
	@Setter
	private boolean useMariaDB = false;
	@Getter
	@Setter
	private String mysqlDriver = "";

	@Getter
	@Setter
	private String poolName = "SimpleAPI-Hikari";

	public ConnectionManager(String host, String port, String username, String password, String database) {
		this.host = host;
		this.port = port;
		this.username = username;
		this.password = password;
		this.database = database;
	}

	public ConnectionManager(String host, String port, String username, String password, String database,
			int maxConnections, boolean useSSL, long lifeTime, String str, boolean publicKeyRetrieval,
			boolean useMariaDB) {
		this(host, port, username, password, database);
		this.maximumPoolsize = maxConnections;
		this.useSSL = useSSL;
		this.maxLifetimeMs = lifeTime;
		this.str = (str == null ? "" : str);
		this.publicKeyRetrieval = publicKeyRetrieval;
		this.useMariaDB = useMariaDB;
	}

	public boolean isClosed() {
		return dataSource == null || dataSource.isClosed();
	}

	public void close() {
		if (!isClosed()) {
			dataSource.close();
		}
	}

	public Connection getConnection() {
		try {
			if (isClosed()) {
				open();
			}
			return dataSource.getConnection();
		} catch (SQLException e) {
			e.printStackTrace();
			open();
			return null;
		}
	}

	private String getMysqlDriverName() {
		String className = useMariaDB ? "org.mariadb.jdbc.Driver" : "com.mysql.cj.jdbc.Driver";
		try {
			Class.forName(className);
		} catch (ClassNotFoundException ignored) {
			className = "com.mysql.cj.jdbc.Driver";
			try {
				Class.forName(className);
			} catch (ClassNotFoundException ignored1) {
				try {
					className = "com.mysql.jdbc.Driver";
					Class.forName(className);
				} catch (ClassNotFoundException ignored2) {
				}
			}
		}
		return className;
	}

	// --- Pool Configuration ---

	public boolean open() {
		if (mysqlDriver.isEmpty())
			mysqlDriver = getMysqlDriverName();

		try {
			HikariConfig cfg = new HikariConfig();
			cfg.setDriverClassName(mysqlDriver);
			cfg.setUsername(username);
			cfg.setPassword(password);

			String base = (mysqlDriver.equals("org.mariadb.jdbc.Driver"))
					? String.format("jdbc:mariadb://%s:%s/%s", host, port, database)
					: String.format("jdbc:mysql://%s:%s/%s", host, port, database);

			String url = base + "?useSSL=" + useSSL + "&allowMultiQueries=true" + "&rewriteBatchedStatements=true"
					+ "&useDynamicCharsetInfo=false" + "&allowPublicKeyRetrieval=" + publicKeyRetrieval
					+ "&tcpKeepAlive=true" + "&connectTimeout=10000" + "&socketTimeout=30000" + "&serverTimezone=UTC"
					+ (str == null ? "" : str);
			cfg.setJdbcUrl(url);

			// Pool sizing
			int maxPool = Math.max(1, maximumPoolsize);
			cfg.setMaximumPoolSize(maxPool);
			int minIdle = (minimumIdle >= 0) ? Math.min(minimumIdle, maxPool) : Math.min(2, maxPool);
			cfg.setMinimumIdle(Math.max(0, minIdle));

			// Lifecycle
			cfg.setConnectionTimeout(Math.max(1000L, connectionTimeout));
			long effectiveMaxLife = (maxLifetimeMs > 0) ? maxLifetimeMs : 25 * 60_000L;
			cfg.setMaxLifetime(effectiveMaxLife);

			long effectiveIdle = Math.min(idleTimeoutMs, Math.max(1000L, effectiveMaxLife - 60_000L));
			cfg.setIdleTimeout(effectiveIdle);

			if (keepaliveMs > 0)
				cfg.setKeepaliveTime(keepaliveMs);
			if (validationMs > 0)
				cfg.setValidationTimeout(validationMs);
			if (leakDetectMs > 0)
				cfg.setLeakDetectionThreshold(leakDetectMs);

			cfg.setConnectionTestQuery("SELECT 1");

			cfg.addDataSourceProperty("cachePrepStmts", true);
			cfg.addDataSourceProperty("prepStmtCacheSize", 500);
			cfg.addDataSourceProperty("prepStmtCacheSqlLimit", 2048);
			cfg.addDataSourceProperty("useServerPrepStmts", true);

			cfg.setAutoCommit(true);
			cfg.setPoolName("SimpleAPI-Hikari");

			dataSource = new HikariDataSource(cfg);
			return true;
		} catch (Exception e) {
			e.printStackTrace();
			return false;
		}
	}
}
