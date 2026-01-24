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

/**
 * Single MySQL-backed message queue messenger.
 *
 * Option A implementation:
 * - NO long-lived pooled Connections.
 * - Each loop borrows a Connection, tries GET_LOCK with a small timeout,
 *   processes a batch, RELEASE_LOCK, then closes Connection.
 *
 * Table schema: {tableName}_message_queue
 */
public class MySqlMessenger {

	public enum Mode {
		BACKEND, PROXY
	}

	private static final String PROXY_DESTINATION = "proxy";
	private static final String PROXY_CHANNEL = "proxy-channel";

	private final Mode mode;
	private final String myServerId; // required for BACKEND, null for PROXY
	private final String channelName;

	private final DataSource ds;
	private final Consumer<QueueMessage> onMessage;
	private final String tableName;

	private volatile boolean running = true;
	private volatile Thread listenerThread;

	// Tracks the last processed ID per messenger instance
	private long lastSeenId = 0;

	/**
	 * @param tableName  base name, table will be {tableName}_message_queue
	 * @param dataSource datasource
	 * @param mode       BACKEND or PROXY
	 * @param serverId   required when mode == BACKEND, ignored for PROXY
	 * @param onMessage  callback for each message received
	 */
	public MySqlMessenger(String tableName, DataSource dataSource, Mode mode, String serverId,
			Consumer<QueueMessage> onMessage) throws SQLException {
		this.ds = dataSource;
		this.mode = mode;
		this.tableName = tableName;
		this.onMessage = onMessage;

		if (mode == Mode.BACKEND) {
			if (serverId == null || serverId.isEmpty()) {
				throw new IllegalArgumentException("serverId required for BACKEND mode");
			}
			this.myServerId = serverId;
			this.channelName = "backend-channel-" + serverId;
		} else {
			this.myServerId = null;
			this.channelName = PROXY_CHANNEL;
		}

		ensureSchema(tableName);
		startListener();
	}

	public Mode getMode() {
		return mode;
	}

	public String getServerId() {
		return myServerId;
	}

	public String getChannelName() {
		return channelName;
	}

	private void ensureSchema(String tableName) throws SQLException {
		String ddl = "CREATE TABLE IF NOT EXISTS " + tableName + "_message_queue ("
				+ "id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, "
				+ "source VARCHAR(36) NOT NULL, "
				+ "destination VARCHAR(36) NOT NULL, "
				+ "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
				+ "payload LONGTEXT NOT NULL, "
				+ "PRIMARY KEY (id), "
				+ "INDEX idx_dest_id (destination, id)"
				+ ") ENGINE=InnoDB;";
		try (Connection conn = ds.getConnection(); Statement stmt = conn.createStatement()) {
			stmt.execute(ddl);
		}
	}

	private void startListener() {
		listenerThread = new Thread(() -> {
			// Tuning knobs:
			final int lockTimeoutSeconds = 1; // keep <= leak detection threshold (and prevents long checkouts)
			final long idleSleepMs = 25;      // small delay when no lock obtained
			final long errorBackoffMs = 250;  // avoid tight loop on errors

			while (running) {
				boolean gotLock = false;

				try (Connection conn = ds.getConnection()) {
					// Important: lock lifetime is tied to THIS connection
					gotLock = acquireLock(conn, channelName, lockTimeoutSeconds);
					if (!gotLock) {
						sleepQuiet(idleSleepMs);
						continue;
					}

					try {
						List<QueueMessage> batch = fetchBatchAndDelete(conn);
						for (QueueMessage msg : batch) {
							onMessage.accept(msg);
						}
					} finally {
						// Always release lock before returning the connection to the pool
						try {
							releaseLock(conn, channelName);
						} catch (SQLException ignored) {
							// If release fails, connection close will drop it anyway
						}
					}

				} catch (SQLException e) {
					e.printStackTrace();
					sleepQuiet(errorBackoffMs);
				}
			}
		}, mode == Mode.BACKEND ? "MySqlMessenger-Backend-" + myServerId : "MySqlMessenger-Proxy");

		listenerThread.setDaemon(true);
		listenerThread.start();
	}

	private List<QueueMessage> fetchBatchAndDelete(Connection conn) throws SQLException {
		List<QueueMessage> results = new ArrayList<>();

		if (mode == Mode.BACKEND) {
			String sql = "SELECT id, source, destination, payload FROM " + tableName
					+ "_message_queue WHERE destination = ? AND id > ? ORDER BY id";
			try (PreparedStatement ps = conn.prepareStatement(sql)) {
				ps.setString(1, myServerId);
				ps.setLong(2, lastSeenId);
				try (ResultSet rs = ps.executeQuery()) {
					while (rs.next()) {
						long id = rs.getLong("id");
						String source = rs.getString("source");
						String destination = rs.getString("destination");
						String payload = rs.getString("payload");

						JsonEnvelope env = JsonEnvelopeCodec.decode(payload);
						results.add(new QueueMessage(id, source, destination, env));

						lastSeenId = id;
						deleteMessageById(conn, id);
					}
				}
			}
			return results;
		}

		// PROXY mode
		String sql = "SELECT id, source, destination, payload FROM " + tableName + "_message_queue "
				+ "WHERE destination='proxy' AND id > ? ORDER BY id";
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setLong(1, lastSeenId);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					long id = rs.getLong("id");
					String source = rs.getString("source");
					String destination = rs.getString("destination");
					String payload = rs.getString("payload");

					JsonEnvelope env = JsonEnvelopeCodec.decode(payload);
					results.add(new QueueMessage(id, source, destination, env));

					lastSeenId = id;
					deleteMessageById(conn, id);
				}
			}
		}
		return results;
	}

	private void deleteMessageById(Connection conn, long id) throws SQLException {
		String delSql = "DELETE FROM " + tableName + "_message_queue WHERE id = ?";
		try (PreparedStatement del = conn.prepareStatement(delSql)) {
			del.setLong(1, id);
			del.executeUpdate();
		}
	}

	/**
	 * BACKEND -> PROXY send (uses this backend's serverId as source).
	 */
	public synchronized void sendToProxy(JsonEnvelope envelope) throws SQLException {
		if (mode != Mode.BACKEND) {
			throw new IllegalStateException("sendToProxy() is intended for BACKEND mode");
		}
		sendToProxy(myServerId, envelope);
	}

	/**
	 * Any sender -> PROXY send (explicit source id).
	 */
	public synchronized void sendToProxy(String fromServerId, JsonEnvelope envelope) throws SQLException {
		String payload = JsonEnvelopeCodec.encode(envelope);

		try (Connection conn = ds.getConnection()) {
			String insertSql = "INSERT INTO " + tableName
					+ "_message_queue (source, destination, payload) VALUES (?, 'proxy', ?)";
			try (PreparedStatement ins = conn.prepareStatement(insertSql)) {
				ins.setString(1, fromServerId);
				ins.setString(2, payload);
				ins.executeUpdate();
			}

			// Wake proxy listener (best-effort). If lock isn't held, nothing happens—still OK (poll will pick it up).
			try (PreparedStatement rel = conn.prepareStatement("SELECT RELEASE_LOCK(?)")) {
				rel.setString(1, PROXY_CHANNEL);
				rel.executeQuery();
			}
		}
	}

	/**
	 * PROXY -> BACKEND send (source is fixed to 'proxy' to match existing behavior).
	 */
	public synchronized void sendToBackend(String targetServerId, JsonEnvelope envelope) throws SQLException {
		if (targetServerId == null || targetServerId.isEmpty()) {
			throw new IllegalArgumentException("targetServerId required");
		}
		String payload = JsonEnvelopeCodec.encode(envelope);

		try (Connection conn = ds.getConnection()) {
			String insertSql = "INSERT INTO " + tableName
					+ "_message_queue (source, destination, payload) VALUES ('proxy', ?, ?)";
			try (PreparedStatement ins = conn.prepareStatement(insertSql)) {
				ins.setString(1, targetServerId);
				ins.setString(2, payload);
				ins.executeUpdate();
			}

			// Wake backend listener (best-effort)
			String backendChannel = "backend-channel-" + targetServerId;
			try (PreparedStatement rel = conn.prepareStatement("SELECT RELEASE_LOCK(?)")) {
				rel.setString(1, backendChannel);
				rel.executeQuery();
			}
		}
	}

	private boolean acquireLock(Connection conn, String name, int timeoutSeconds) throws SQLException {
		try (PreparedStatement ps = conn.prepareStatement("SELECT GET_LOCK(?, ?)")) {
			ps.setString(1, name);
			ps.setInt(2, timeoutSeconds);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() && rs.getInt(1) == 1;
			}
		}
	}

	private void releaseLock(Connection conn, String name) throws SQLException {
		try (PreparedStatement ps = conn.prepareStatement("SELECT RELEASE_LOCK(?)")) {
			ps.setString(1, name);
			ps.executeQuery();
		}
	}

	public void shutdown() {
		running = false;
		Thread t = listenerThread;
		if (t != null) {
			t.interrupt();
		}
	}

	private void sleepQuiet(long ms) {
		if (ms <= 0) {
			return;
		}
		try {
			Thread.sleep(ms);
		} catch (InterruptedException ignored) {
			// allow shutdown to break sleeps
		}
	}

	public static class QueueMessage {
		public final long id;
		public final String source;
		public final String destination;
		public final JsonEnvelope envelope;

		public QueueMessage(long id, String source, String destination, JsonEnvelope envelope) {
			this.id = id;
			this.source = source;
			this.destination = destination;
			this.envelope = envelope;
		}

		public boolean isToProxy() {
			return PROXY_DESTINATION.equalsIgnoreCase(destination);
		}
	}
}
