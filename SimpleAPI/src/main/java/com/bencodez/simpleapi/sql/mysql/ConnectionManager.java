package com.bencodez.simpleapi.sql.mysql;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Objects;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import lombok.Getter;
import lombok.Setter;

/**
 * Manages a HikariCP-backed SQL connection pool for MySQL/MariaDB/PostgreSQL.
 *
 * Supports a failover mode where if {@link DbType#MARIADB} is selected but the MariaDB JDBC
 * driver is not present, it will automatically fall back to the MySQL driver.
 */
public class ConnectionManager {

	@Getter
	@Setter
	private int connectionTimeout = 50_000;

	@Getter
	@Setter
	private String database;

	@Getter
	private volatile HikariDataSource dataSource;

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
	private PostgreSqlTlsMode postgreSqlTlsMode = PostgreSqlTlsMode.LEGACY;

	public void setPostgreSqlTlsMode(PostgreSqlTlsMode mode) {
		postgreSqlTlsMode = Objects.requireNonNull(mode, "mode");
	}

	/**
	 * Legacy flag still supported; only used if dbType isn't explicitly set.
	 */
	@Getter
	@Setter
	private boolean useMariaDB = false;

	@Getter
	@Setter
	private String mysqlDriver = "";

	@Getter
	@Setter
	private String poolName = "SimpleAPI-Hikari";

	@Getter
	@Setter
	private DbType dbType = DbType.MYSQL;

	/**
	 * If true and {@link DbType#MARIADB} is selected, but the MariaDB driver is not present,
	 * fall back to using the MySQL driver automatically.
	 */
	@Getter
	@Setter
	private boolean mariadbFallbackToMysqlDriver = true;

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
		// temporary backward compatibility
		this.dbType = useMariaDB ? DbType.MARIADB : DbType.MYSQL;
	}

	/** Transfers ownership of a supplied pool and retires the previous one. */
	public synchronized void setDataSource(HikariDataSource replacement) {
		replaceDataSource(replacement);
	}

	private void replaceDataSource(HikariDataSource replacement) {
		HikariDataSource predecessor = dataSource;
		if (predecessor == replacement) return;
		dataSource = replacement;
		if (predecessor != null) {
			try {
				predecessor.close();
			} catch (RuntimeException cleanupFailure) {
				// Publication succeeded; cleanup failure must not misreport initialization failure.
				System.err.println("Unable to close predecessor SQL pool after replacement");
			}
		}
	}

	public boolean isClosed() {
		HikariDataSource current = dataSource;
		return current == null || current.isClosed();
	}

	public synchronized void close() {
		if (!isClosed()) {
			dataSource.close();
		}
	}

	/**
	 * Legacy no-checked-exception entry point. Acquisition failure is explicit,
	 * never a null connection; SQL callers should use getConnectionChecked().
	 */
	public Connection getConnection() {
		try {
			return getConnectionChecked();
		} catch (SQLException failure) {
			throw new IllegalStateException("Unable to obtain a SQL pool connection", failure);
		}
	}

	/**
	 * Borrows from the current pool without replacing it on timeout/exhaustion.
	 * The lifecycle monitor is released before waiting for a lease, so shutdown
	 * does not wait for an exhausted borrow's connection timeout.
	 * @throws SQLException when initialization or acquisition fails
	 */
	public Connection getConnectionChecked() throws SQLException {
		HikariDataSource current;
		synchronized (this) {
			if (isClosed() && !open()) {
				throw new SQLException("Unable to initialize SQL connection pool");
			}
			current = dataSource;
		}
		return current.getConnection();
	}

	private void ensureDriverPresent(String className) throws ClassNotFoundException {
		Class.forName(className);
	}

	/**
	 * Resolves the JDBC driver class to use.
	 *
	 * Behavior:
	 * - If {@link #mysqlDriver} is explicitly set, that driver must be present and is used.
	 * - Otherwise, uses the driver implied by {@link #dbType}.
	 * - If {@link DbType#MARIADB} is selected and the MariaDB driver isn't present, optionally falls back to the MySQL driver.
	 *
	 * @return The resolved driver class name.
	 * @throws ClassNotFoundException if no suitable driver is available.
	 */
	private String resolveDriver() throws ClassNotFoundException {
		// Allow explicit override (keeps field name mysqlDriver for minimal churn)
		if (mysqlDriver != null && !mysqlDriver.isEmpty()) {
			ensureDriverPresent(mysqlDriver);
			return mysqlDriver;
		}

		switch (dbType) {
		case POSTGRESQL:
			ensureDriverPresent("org.postgresql.Driver");
			return "org.postgresql.Driver";
		case MARIADB:
			return resolveMariaDbWithFallback();
		case MYSQL:
		default:
			ensureDriverPresent("com.mysql.cj.jdbc.Driver");
			return "com.mysql.cj.jdbc.Driver";
		}
	}

	/**
	 * Resolves MariaDB driver with optional fallback to MySQL driver if MariaDB driver is missing.
	 *
	 * @return Driver class name to use.
	 * @throws ClassNotFoundException if neither MariaDB nor fallback MySQL driver is present.
	 */
	private String resolveMariaDbWithFallback() throws ClassNotFoundException {
		try {
			ensureDriverPresent("org.mariadb.jdbc.Driver");
			return "org.mariadb.jdbc.Driver";
		} catch (ClassNotFoundException e) {
			if (!mariadbFallbackToMysqlDriver) {
				throw e;
			}
			ensureDriverPresent("com.mysql.cj.jdbc.Driver");
			return "com.mysql.cj.jdbc.Driver";
		}
	}

	String buildJdbcUrl(String driverClassName) {
		String extra = (str == null ? "" : str);

		// Postgres
		if (dbType == DbType.POSTGRESQL || "org.postgresql.Driver".equals(driverClassName)) {
			String base = String.format("jdbc:postgresql://%s:%s/%s", host, port, database);

			// Defaults:
			// - reWriteBatchedInserts improves batch perf
			// - absent explicit mode preserves the legacy UseSSL behavior
			String defaults = "reWriteBatchedInserts=true";
			if (postgreSqlTlsMode == PostgreSqlTlsMode.LEGACY && useSSL) {
				defaults += "&sslmode=require";
			} else if (postgreSqlTlsMode != PostgreSqlTlsMode.LEGACY) {
				rejectConflictingPostgreSqlTlsOptions(extra);
				defaults += "&sslmode=" + postgreSqlTlsMode.getJdbcValue();
				if (postgreSqlTlsMode == PostgreSqlTlsMode.REQUIRE
						|| postgreSqlTlsMode == PostgreSqlTlsMode.VERIFY_FULL) {
					defaults += "&gssEncMode=disable";
				}
			}

			if (extra.isEmpty()) {
				return base + "?" + defaults;
			}

			// Normalize user extras:
			// - "?a=b" => base + "?a=b&defaults"
			// - "&a=b" => base + "?defaults&a=b"
			// - "a=b" => base + "?defaults&a=b"
			if (extra.startsWith("?")) {
				return base + extra + (extra.endsWith("?") ? "" : "&") + defaults;
			}
			if (extra.startsWith("&")) {
				return base + "?" + defaults + extra;
			}
			return base + "?" + defaults + "&" + extra;
		}

		// MySQL / MariaDB
		if (postgreSqlTlsMode != PostgreSqlTlsMode.LEGACY) {
			throw new IllegalArgumentException("PostgreSqlTlsMode applies only to PostgreSQL connections");
		}
		boolean maria = (dbType == DbType.MARIADB) || "org.mariadb.jdbc.Driver".equals(driverClassName);
		String base = maria ? String.format("jdbc:mariadb://%s:%s/%s", host, port, database)
				: String.format("jdbc:mysql://%s:%s/%s", host, port, database);

		return base + "?useSSL=" + useSSL + "&allowMultiQueries=true" + "&rewriteBatchedStatements=true"
				+ "&useDynamicCharsetInfo=false" + "&allowPublicKeyRetrieval=" + publicKeyRetrieval
				+ "&tcpKeepAlive=true" + "&connectTimeout=10000" + "&socketTimeout=30000" + "&serverTimezone=UTC"
				+ extra;
	}

	private void rejectConflictingPostgreSqlTlsOptions(String extra) {
		String options = extra.startsWith("?") || extra.startsWith("&") ? extra.substring(1) : extra;
		for (String parameter : options.split("&", -1)) {
			String rawKey = parameter.split("=", 2)[0];
			String key;
			try {
				key = URLDecoder.decode(rawKey, StandardCharsets.UTF_8).trim().toLowerCase(Locale.ROOT);
			} catch (IllegalArgumentException ex) {
				throw new IllegalArgumentException("Invalid encoded parameter name in PostgreSQL Line", ex);
			}
			if (key.equals("sslmode") || key.equals("ssl") || key.equals("sslfactory")
					|| key.equals("sslhostnameverifier")
					|| (postgreSqlTlsMode != PostgreSqlTlsMode.DISABLE && key.equals("gssencmode"))) {
				throw new IllegalArgumentException("PostgreSqlTlsMode conflicts with a TLS parameter in Line");
			}
		}
	}

	// --- Pool Configuration ---

	public synchronized boolean open() {
		try {
			// If someone only set legacy flag but not dbType explicitly, keep it consistent
			if (dbType == null) {
				dbType = useMariaDB ? DbType.MARIADB : DbType.MYSQL;
			}

			String driverClassName = resolveDriver();

			HikariConfig cfg = new HikariConfig();
			cfg.setDriverClassName(driverClassName);
			cfg.setUsername(username);
			cfg.setPassword(password);

			cfg.setJdbcUrl(buildJdbcUrl(driverClassName));

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

			if (keepaliveMs > 0) {
				cfg.setKeepaliveTime(keepaliveMs);
			}
			if (validationMs > 0) {
				cfg.setValidationTimeout(validationMs);
			}
			if (leakDetectMs > 0) {
				cfg.setLeakDetectionThreshold(leakDetectMs);
			}

			cfg.setConnectionTestQuery("SELECT 1");

			// Safe common settings; ignored by some drivers (fine)
			cfg.addDataSourceProperty("cachePrepStmts", true);
			cfg.addDataSourceProperty("prepStmtCacheSize", 500);
			cfg.addDataSourceProperty("prepStmtCacheSqlLimit", 2048);
			cfg.addDataSourceProperty("useServerPrepStmts", true);

			cfg.setAutoCommit(true);
			cfg.setPoolName(poolName != null && !poolName.isEmpty() ? poolName : "SimpleAPI-Hikari");

			HikariDataSource replacement = new HikariDataSource(cfg);
			replaceDataSource(replacement);
			return true;
		} catch (Exception e) {
			e.printStackTrace();
			return false;
		}
	}
}
