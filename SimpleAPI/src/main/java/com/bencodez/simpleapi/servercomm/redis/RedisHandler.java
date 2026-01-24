package com.bencodez.simpleapi.servercomm.redis;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

import com.bencodez.simpleapi.servercomm.codec.JsonEnvelope;
import com.bencodez.simpleapi.servercomm.codec.JsonEnvelopeCodec;

import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisClientConfig;

public abstract class RedisHandler {

	private final HostAndPort endpoint;
	private final JedisClientConfig clientConfig;

	private final Map<RedisListener, Thread> listenerThreads = new ConcurrentHashMap<>();

	public RedisHandler(String host, int port, String username, String password, int dbIndex) {
		Objects.requireNonNull(host, "host");

		this.endpoint = new HostAndPort(host, port);

		DefaultJedisClientConfig.Builder cfg = DefaultJedisClientConfig.builder()
				.database(dbIndex)
				.connectionTimeoutMillis(2000)
				.socketTimeoutMillis(2000);

		if (username != null && !username.isEmpty()) {
			cfg.user(username);
		}
		if (password != null && !password.isEmpty()) {
			cfg.password(password);
		}

		this.clientConfig = cfg.build();
	}

	public void close() {
		for (Map.Entry<RedisListener, Thread> entry : listenerThreads.entrySet()) {
			try {
				entry.getKey().unsubscribe();
			} catch (Exception ignored) {
			}
		}
		listenerThreads.clear();
	}

	public void loadListener(RedisListener listener) {
		Objects.requireNonNull(listener, "listener");

		if (listenerThreads.containsKey(listener)) {
			return;
		}

		Thread thread = new Thread(() -> {
			try (Jedis jedis = new Jedis(endpoint, clientConfig)) {
				debug("Starting Redis subscription for channel: " + listener.getChannel());
				// 🔥 RedisListener is directly used here
				jedis.subscribe(listener, listener.getChannel());
			} catch (Exception e) {
				debug("Redis subscribe error on channel " + listener.getChannel() + ": " + e.getMessage());
			} finally {
				listenerThreads.remove(listener);
			}
		}, "RedisSubscribeThread-" + listener.getChannel());

		thread.setDaemon(true);
		listenerThreads.put(listener, thread);
		thread.start();
	}

	/** Publish an envelope as a single JSON string. */
	public void publishEnvelope(String channel, JsonEnvelope envelope) {
		String payload = JsonEnvelopeCodec.encode(envelope);
		try (Jedis jedis = new Jedis(endpoint, clientConfig)) {
			debug("Redis Send: " + channel + ", " + payload);
			jedis.publish(channel, payload);
		} catch (Exception e) {
			debug("Redis publish error on channel " + channel + ": " + e.getMessage());
		}
	}

	/** Subscribe and decode envelopes, forwarding to your callback. */
	public RedisListener createEnvelopeListener(String channel,
			BiConsumer<String, JsonEnvelope> onEnvelope) {

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
