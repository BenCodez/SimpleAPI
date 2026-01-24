package com.bencodez.simpleapi.servercomm.redis;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

import com.bencodez.simpleapi.servercomm.codec.JsonEnvelope;
import com.bencodez.simpleapi.servercomm.codec.JsonEnvelopeCodec;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

public abstract class RedisHandler {
	private final JedisPool publishPool;
	private final JedisPool subscribePool;

	private final Map<RedisListener, Thread> listenerThreads = new ConcurrentHashMap<>();

	public RedisHandler(String host, int port, String username, String password, int dbIndex) {
		int timeout = 2000;
		JedisPoolConfig config = new JedisPoolConfig();

		boolean userBlank = username == null || username.isEmpty();
		boolean passBlank = password == null || password.isEmpty();

		if (userBlank && passBlank) {
			publishPool = new JedisPool(config, host, port, timeout, null, dbIndex);
			subscribePool = new JedisPool(config, host, port, timeout, null, dbIndex);
		} else if (userBlank) {
			publishPool = new JedisPool(config, host, port, timeout, password, dbIndex);
			subscribePool = new JedisPool(config, host, port, timeout, password, dbIndex);
		} else {
			publishPool = new JedisPool(config, host, port, timeout, username, password, dbIndex);
			subscribePool = new JedisPool(config, host, port, timeout, username, password, dbIndex);
		}
	}

	public void close() {
		for (Map.Entry<RedisListener, Thread> entry : listenerThreads.entrySet()) {
			try {
				entry.getKey().unsubscribe();
			} catch (Exception ignored) {
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

	/** Publish an envelope as a single JSON string. */
	public void publishEnvelope(String channel, JsonEnvelope envelope) {
		String payload = JsonEnvelopeCodec.encode(envelope);
		try (Jedis jedis = publishPool.getResource()) {
			debug("Redis Send: " + channel + ", " + payload);
			jedis.publish(channel, payload);
		}
	}

	/** Subscribe and decode envelopes, forwarding to your callback (external wiring). */
	public RedisListener createEnvelopeListener(String channel, BiConsumer<String, JsonEnvelope> onEnvelope) {
		return new RedisListener(this, channel, (ch, payload) -> {
			try {
				onEnvelope.accept(ch, JsonEnvelopeCodec.decode(payload));
			} catch (Exception e) {
				debug("Redis decode failed: " + e.getMessage());
			}
		});
	}

	public abstract void debug(String message);
}
