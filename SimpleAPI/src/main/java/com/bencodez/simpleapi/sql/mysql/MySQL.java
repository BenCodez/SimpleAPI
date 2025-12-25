package com.bencodez.simpleapi.sql.mysql;

import java.sql.SQLException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.bencodez.simpleapi.sql.mysql.config.MysqlConfig;

public abstract class MySQL {

	private ConnectionManager connectionManager;
	private int maxConnections;
	private ExecutorService threadPool;

	/**
	 * Create a new MySQL object with a default of 10 maximum threads.
	 */
	public MySQL() {
		this.threadPool = Executors.newFixedThreadPool(10);
		this.maxConnections = 1;
	}

	/**
	 * Create a new MySQL object.
	 *
	 * @param maxConnections Maximum number of connections
	 */
	public MySQL(int maxConnections) {
		this.maxConnections = maxConnections;
		this.threadPool = Executors.newFixedThreadPool(Math.max(1, maxConnections));
	}

	public boolean connect(MysqlConfig config) {
		// (Re)size thread pool to match MaxConnections from config
		this.maxConnections = Math.max(1, config.getMaxThreads());
		if (threadPool != null) {
			threadPool.shutdown();
		}
		threadPool = Executors.newFixedThreadPool(maxConnections);

		// Create manager with core options
		connectionManager = new ConnectionManager(config.getHostName(), String.valueOf(config.getPort()),
				config.getUser(), config.getPass(), config.getDatabase(), maxConnections, config.isUseSSL(),
				config.getLifeTime(), // may be <=0 (manager will default)
				config.getLine() == null ? "" : config.getLine(), config.isPublicKeyRetrieval(), config.isUseMariaDB());

		// NEW: set DB type + optional driver override
		// MysqlConfig should expose:
		// - DbType getDbType()
		// - String getDriver() (can be empty)
		DbType type = config.getDbType();
		if (type != null) {
			connectionManager.setDbType(type);
		} else {
			connectionManager.setDbType(config.isUseMariaDB() ? DbType.MARIADB : DbType.MYSQL);
		}

		String driver = config.getDriver();
		if (driver != null && !driver.isEmpty()) {
			connectionManager.setMysqlDriver(driver); // field name kept as mysqlDriver
		}

		// Apply optional tunables if provided (>0 or explicitly set)
		if (config.getMinimumIdle() > 0) {
			connectionManager.setMinimumIdle(config.getMinimumIdle());
		}
		if (config.getIdleTimeoutMs() > 0) {
			connectionManager.setIdleTimeoutMs(config.getIdleTimeoutMs());
		}
		if (config.getKeepaliveMs() > 0) {
			connectionManager.setKeepaliveMs(config.getKeepaliveMs());
		}
		if (config.getValidationMs() > 0) {
			connectionManager.setValidationMs(config.getValidationMs());
		}
		if (config.getLeakDetectMs() > 0) {
			connectionManager.setLeakDetectMs(config.getLeakDetectMs());
		}
		if (config.getConnectionTimeout() > 0) {
			connectionManager.setConnectionTimeout(config.getConnectionTimeout());
		}
		if (config.getLifeTime() > 0) {
			connectionManager.setMaxLifetimeMs(config.getLifeTime());
		}

		if (config.getPoolName() != null && !config.getPoolName().isEmpty()) {
			connectionManager.setPoolName(config.getPoolName());
		}

		boolean ok = connectionManager.open();

		// Optional debug output
		if (config.isDebug()) {
			if (ok) {
				debug("DB connected. type=" + connectionManager.getDbType() + " host=" + config.getHostName() + " db="
						+ config.getDatabase() + " maxPool=" + maxConnections);
			} else {
				debug("DB connection failed. type=" + connectionManager.getDbType()
						+ " Check host/port/credentials and timeouts.");
			}
		}

		return ok;
	}

	public abstract void debug(SQLException e);

	public abstract void debug(String msg);

	/**
	 * Close all connections and the data source.
	 */
	public void disconnect() {
		if (connectionManager != null) {
			connectionManager.close();
		}
		if (threadPool != null) {
			threadPool.shutdown();
		}
	}

	/**
	 * Get the connection manager.
	 *
	 * @return the connection manager
	 */
	public ConnectionManager getConnectionManager() {
		return connectionManager;
	}

	/**
	 * Get the thread pool.
	 *
	 * @return the thread pool
	 */
	public ExecutorService getThreadPool() {
		return threadPool;
	}

	public abstract void severe(String string);
}
