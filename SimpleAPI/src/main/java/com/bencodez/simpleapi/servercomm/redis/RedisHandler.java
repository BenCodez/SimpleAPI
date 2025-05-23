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
		publishPool.close();
		subscribePool.close();
	}

	public abstract void debug(String message);

	public void loadListener(RedisListener listener) {
		try (Jedis jedis = subscribePool.getResource()) {
			jedis.subscribe(listener, listener.getChannel());
		}
	}

	protected abstract void onMessage(String channel, String[] message);

	public void sendMessage(String channel, String... message) {
		String str = String.join(":", message);
		try (Jedis jedis = publishPool.getResource()) {
			debug("Redis Send: " + channel + ", " + str);
			jedis.publish(channel, str);
		}
	}
}
