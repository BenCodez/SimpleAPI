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

	public RedisHandler(String host, int port, String username, String password) {
		int timeout = 2000; // Set a reasonable timeout
		if (username.isEmpty() && password.isEmpty()) {
			publishPool = new JedisPool(new JedisPoolConfig(), host, port, timeout);
			subscribePool = new JedisPool(new JedisPoolConfig(), host, port, timeout);
		} else if (username.isEmpty()) {
			publishPool = new JedisPool(new JedisPoolConfig(), host, port, timeout, password);
			subscribePool = new JedisPool(new JedisPoolConfig(), host, port, timeout, password);
		} else {
			publishPool = new JedisPool(new JedisPoolConfig(), host, port, timeout, username, password);
			subscribePool = new JedisPool(new JedisPoolConfig(), host, port, timeout, username, password);
		}
	}

	public void close() {
		debug("Shutting down RedisHandler");

		// Unsubscribe all listeners and stop threads
		for (Map.Entry<RedisListener, Thread> entry : listenerThreads.entrySet()) {
			RedisListener listener = entry.getKey();
			Thread thread = entry.getValue();

			try {
				debug("Unsubscribing Redis listener on channel: " + listener.getChannel());
				listener.unsubscribe(); // Gracefully signal jedis.subscribe() to exit
			} catch (Exception e) {
				debug("Failed to unsubscribe listener: " + e.getMessage());
			}

			try {
				if (thread != null && thread.isAlive()) {
					thread.join(2000); // Give up to 2 seconds for it to shut down
				}
			} catch (InterruptedException e) {
				debug("Interrupted while waiting for Redis listener thread to finish: " + e.getMessage());
			}
		}

		listenerThreads.clear();

		// Close Redis pools
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
