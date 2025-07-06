package com.bencodez.simpleapi.servercomm.redis;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

public abstract class RedisHandler {
	private final JedisPool publishPool;
	private final JedisPool subscribePool;

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
		try {
			if (subscribeThread != null && subscribeThread.isAlive()) {
				subscribeThread.interrupt(); // Signal the thread to stop
			}
		} catch (Exception e) {
			debug("Failed to interrupt Redis subscribe thread: " + e.getMessage());
		}
		
		
		publishPool.close();
		subscribePool.close();
	}

	private Thread subscribeThread;

	public void loadListener(RedisListener listener) {
		subscribeThread = new Thread(() -> {
			try (Jedis jedis = subscribePool.getResource()) {
				jedis.subscribe(listener, listener.getChannel());
			} catch (Exception e) {
				debug("Redis subscribe error: " + e.getMessage());
			}
		}, "RedisSubscribeThread");

		subscribeThread.start();
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
