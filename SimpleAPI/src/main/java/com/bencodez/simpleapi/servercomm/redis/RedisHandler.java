package com.bencodez.simpleapi.servercomm.redis;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

public abstract class RedisHandler {
	private final JedisPool publishPool;
	private final JedisPool subscribePool;

	private final Map<RedisListener, Thread> listenerThreads = new ConcurrentHashMap<>();

	/**
	 * Old constructor — defaults DB index to 0
	 */
	public RedisHandler(String host, int port, String username, String password) {
		this(host, port, username, password, 0);
	}

	/**
	 * New constructor with DB index
	 */
	public RedisHandler(String host, int port, String username, String password, int dbIndex) {
		int timeout = 2000;

		JedisPoolConfig config = new JedisPoolConfig();

		if (username.isEmpty() && password.isEmpty()) {
			// No auth
			publishPool = new JedisPool(config, host, port, timeout, null, dbIndex);
			subscribePool = new JedisPool(config, host, port, timeout, null, dbIndex);
		} else if (username.isEmpty()) {
			// Legacy auth (password only)
			publishPool = new JedisPool(config, host, port, timeout, password, dbIndex);
			subscribePool = new JedisPool(config, host, port, timeout, password, dbIndex);
		} else {
			// Username + password
			publishPool = new JedisPool(config, host, port, timeout, username, password, dbIndex);
			subscribePool = new JedisPool(config, host, port, timeout, username, password, dbIndex);
		}
	}

	public void close() {
		debug("Shutting down RedisHandler");

		for (Map.Entry<RedisListener, Thread> entry : listenerThreads.entrySet()) {
			RedisListener listener = entry.getKey();
			Thread thread = entry.getValue();

			try {
				debug("Unsubscribing Redis listener on channel: " + listener.getChannel());
				listener.unsubscribe();
			} catch (Exception e) {
				debug("Failed to unsubscribe listener: " + e.getMessage());
			}

			try {
				if (thread != null && thread.isAlive()) {
					thread.join(2000);
				}
			} catch (InterruptedException e) {
				debug("Interrupted while waiting for Redis listener thread to finish: " + e.getMessage());
			}
		}

		listenerThreads.clear();

		publishPool.close();
		subscribePool.close();
	}

	public void loadListener(RedisListener listener) {
		Thread thread = new Thread(() -> {
			try (Jedis jedis = subscribePool.getResource()) {
				debug("Starting Redis subscription for channel: " + listener.getChannel());
				jedis.subscribe(listener, listener.getChannel());
			} catch (Exception e) {
				debug("Redis subscribe error on channel " + listener.getChannel() + ": " + e.getMessage());
			}
		}, "RedisSubscribeThread-" + listener.getChannel());

		thread.setDaemon(true);
		listenerThreads.put(listener, thread);
		thread.start();
	}

	public abstract void debug(String message);

	protected abstract void onMessage(String channel, String[] message);

	public void sendMessage(String channel, String... message) {
		String str = String.join(":", message);
		try (Jedis jedis = publishPool.getResource()) {
			debug("Redis Send: " + channel + ", " + str);
			jedis.publish(channel, str);
		}
	}
}
