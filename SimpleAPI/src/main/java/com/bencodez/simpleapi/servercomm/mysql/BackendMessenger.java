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

import com.bencodez.simpleapi.servercomm.codec.JsonEnvelope;
import com.bencodez.simpleapi.servercomm.codec.JsonEnvelopeCodec;

public class BackendMessenger {
	private final String myServerId;
	private final String CHANNEL;
	private final DataSource ds;
	private Connection lockConn;
	private Connection workConn;
	private Connection pubConn;
	private volatile boolean running = true;
	private long lastSeenId = 0;

	private final Consumer<BackendMessage> onMessage;
	private final String tableName;

	public BackendMessenger(String tableName, DataSource dataSource, String serverId, Consumer<BackendMessage> onMessage)
			throws SQLException {
		this.ds = dataSource;
		this.myServerId = serverId;
		this.CHANNEL = "backend-channel-" + serverId;
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
		this.lockConn = ds.getConnection();
		this.workConn = ds.getConnection();
		this.pubConn = ds.getConnection();
		if (!acquireLock(lockConn, CHANNEL, 10)) {
			throw new IllegalStateException("Could not acquire " + CHANNEL + " lock on startup");
		}
	}

	private void startListener() {
		Thread t = new Thread(() -> {
			while (running) {
				try {
					if (acquireLock(lockConn, CHANNEL, 300)) {
						fetchBatch().forEach(onMessage);
					}
				} catch (SQLException e) {
					e.printStackTrace();
					reconnectOnError();
				}
			}
		}, "BackendMessenger-Listener-" + myServerId);
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

	private List<BackendMessage> fetchBatch() throws SQLException {
		String sql = "SELECT id, source, payload FROM " + tableName
				+ "_message_queue WHERE destination = ? AND id > ? ORDER BY id";
		List<BackendMessage> results = new ArrayList<>();
		try (PreparedStatement ps = workConn.prepareStatement(sql)) {
			ps.setString(1, myServerId);
			ps.setLong(2, lastSeenId);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					long id = rs.getLong("id");
					String from = rs.getString("source");
					String payload = rs.getString("payload");

					JsonEnvelope env = JsonEnvelopeCodec.decode(payload);
					results.add(new BackendMessage(id, from, env));

					lastSeenId = id;
					deleteMessageById(id);
				}
			}
		}
		return results;
	}

	public synchronized void sendToProxy(JsonEnvelope envelope) throws SQLException {
		String payload = JsonEnvelopeCodec.encode(envelope);

		String insertSql = "INSERT INTO " + tableName
				+ "_message_queue (source, destination, payload) VALUES (?, 'proxy', ?)";
		try (PreparedStatement ins = pubConn.prepareStatement(insertSql)) {
			ins.setString(1, myServerId);
			ins.setString(2, payload);
			ins.executeUpdate();
		}

		try (PreparedStatement rel = pubConn.prepareStatement("SELECT RELEASE_LOCK(?)")) {
			rel.setString(1, "proxy-channel");
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

	public static class BackendMessage {
		public final long id;
		public final String fromServerId;
		public final JsonEnvelope envelope;

		public BackendMessage(long id, String fromServerId, JsonEnvelope envelope) {
			this.id = id;
			this.fromServerId = fromServerId;
			this.envelope = envelope;
		}
	}
}
