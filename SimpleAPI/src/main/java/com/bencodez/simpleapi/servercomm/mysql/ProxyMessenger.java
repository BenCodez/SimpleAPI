package com.bencodez.simpleapi.servercomm.mysql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javax.sql.DataSource;

public class ProxyMessenger {
	private static final String PROXY_CHANNEL = "proxy-channel";
	private final DataSource ds;
	private Connection lockConn;
	private Connection workConn;
	private Connection pubConn;
	private volatile boolean running = true;
	private long lastSeenId = 0;

	private final Consumer<ProxyMessage> onMessage;
	private String tableName;

	public ProxyMessenger(String tableName, DataSource dataSource, Consumer<ProxyMessage> onMessage)
			throws SQLException {
		this.ds = dataSource;
		this.onMessage = onMessage;
		this.tableName = tableName;
		ensureSchema(tableName);
		initConnections();
		startListener();
	}

	private void ensureSchema(String tableName) throws SQLException {
		String ddl = "CREATE TABLE IF NOT EXISTS " + tableName + "_message_queue ("
				+ "id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, " + "source VARCHAR(36) NOT NULL, "
				+ "destination VARCHAR(36) NOT NULL, " + "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
				+ "payload LONGTEXT NOT NULL, " + "PRIMARY KEY (id), " + "INDEX idx_dest_id (destination, id)"
				+ ") ENGINE=InnoDB;";
		try (Connection conn = ds.getConnection(); Statement stmt = conn.createStatement()) {
			stmt.execute(ddl);
		}
	}

	private void initConnections() throws SQLException {
		// Open three long-lived connections
		this.lockConn = ds.getConnection();
		this.workConn = ds.getConnection();
		this.pubConn = ds.getConnection();
		// Prime the lock to enter wait-loop
		if (!acquireLock(lockConn, PROXY_CHANNEL, 10)) {
			throw new IllegalStateException("Could not acquire proxy-channel lock on startup");
		}
	}

	private void startListener() {
		Thread t = new Thread(() -> {
			while (running) {
				try {
					if (acquireLock(lockConn, PROXY_CHANNEL, 300)) {
						List<ProxyMessage> batch = fetchBatch();
						batch.forEach(onMessage);
					}
				} catch (SQLException e) {
					e.printStackTrace();
					reconnectOnError();
				}
			}
		}, "ProxyMessenger-Listener");
		t.setDaemon(true);
		t.start();
	}

	private void deleteMessageById(long id) throws SQLException {
		String delSql = "DELETE FROM " + tableName + "_message_queue WHERE id = ?";
		try (PreparedStatement del = workConn.prepareStatement(delSql)) {
			del.setLong(1, id);
			del.executeUpdate();
		}
	}

	private List<ProxyMessage> fetchBatch() throws SQLException {
		String sql = "SELECT id, source, payload FROM " + tableName + "_message_queue "
				+ "WHERE destination='proxy' AND id > ? ORDER BY id";
		try (PreparedStatement ps = workConn.prepareStatement(sql)) {
			ps.setLong(1, lastSeenId);
			try (ResultSet rs = ps.executeQuery()) {
				List<ProxyMessage> results = new ArrayList<>();
				while (rs.next()) {
					long id = rs.getLong("id");
					String source = rs.getString("source");
					String payload = rs.getString("payload");
					results.add(new ProxyMessage(id, source, payload));
					lastSeenId = id;
					// Delete the message after processing
					deleteMessageById(id);
				}
				return results;
			}
		}
	}

	/**
	 * Sends a message from the proxy to a specific backend server.
	 */
	public synchronized void sendToBackend(String targetServerId, String payload) throws SQLException {
		String insertSql = "INSERT INTO " + tableName
				+ "_message_queue (source, destination, payload) VALUES ('proxy', ?, ?)";
		try (PreparedStatement ins = pubConn.prepareStatement(insertSql)) {
			ins.setString(1, targetServerId);
			ins.setString(2, payload);
			ins.executeUpdate();
		}
		String channel = "backend-channel-" + targetServerId;
		try (PreparedStatement rel = pubConn.prepareStatement("SELECT RELEASE_LOCK(?)")) {
			rel.setString(1, channel);
			rel.executeQuery();
		}
	}

	public synchronized void sendToProxy(String fromServerId, String payload) throws SQLException {
		String insertSql = "INSERT INTO " + tableName
				+ "_message_queue (source, destination, payload) VALUES (?, 'proxy', ?)";
		try (PreparedStatement ins = pubConn.prepareStatement(insertSql)) {
			ins.setString(1, fromServerId);
			ins.setString(2, payload);
			ins.executeUpdate();
		}
		try (PreparedStatement rel = pubConn.prepareStatement("SELECT RELEASE_LOCK(?)")) {
			rel.setString(1, PROXY_CHANNEL);
			rel.executeQuery();
		}
	}

	private boolean acquireLock(Connection conn, String name, int timeout) throws SQLException {
		try (PreparedStatement ps = conn.prepareStatement("SELECT GET_LOCK(?, ?)")) {
			ps.setString(1, name);
			ps.setInt(2, timeout);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() && rs.getInt(1) == 1;
			}
		}
	}

	private void reconnectOnError() {
		try {
			closeQuiet(lockConn);
			closeQuiet(workConn);
			closeQuiet(pubConn);
			initConnections();
		} catch (SQLException e) {
			e.printStackTrace();
		}
	}

	public void shutdown() {
		running = false;
		closeQuiet(lockConn);
		closeQuiet(workConn);
		closeQuiet(pubConn);
	}

	private void closeQuiet(Connection c) {
		if (c != null)
			try {
				c.close();
			} catch (SQLException ignored) {
			}
	}

	public static class ProxyMessage {
		public final long id;
		public final String sourceServerId;
		public final String payload;

		public ProxyMessage(long id, String sourceServerId, String payload) {
			this.id = id;
			this.sourceServerId = sourceServerId;
			this.payload = payload;
		}
	}
}