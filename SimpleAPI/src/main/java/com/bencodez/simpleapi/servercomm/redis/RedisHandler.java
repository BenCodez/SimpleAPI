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
	private volatile boolean shuttingDown = false;

	// Reconnect backoff (ms)
	private static final long RECONNECT_INITIAL_MS = 1000L;
	private static final long RECONNECT_MAX_MS = 30000L;

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
		shuttingDown = true;

		for (Map.Entry<RedisListener, Thread> entry : listenerThreads.entrySet()) {
			try {
				entry.getKey().unsubscribe(); // breaks jedis.subscribe()
			} catch (Exception ignored) {
			}
			try {
				entry.getValue().interrupt(); // breaks backoff sleep if currently sleeping
			} catch (Exception ignored) {
			}
		}
		listenerThreads.clear();
	}

	public void loadListener(RedisListener listener) {
		Objects.requireNonNull(listener, "listener");

		// Avoid duplicates
		if (listenerThreads.containsKey(listener)) {
			return;
		}

		Thread thread = new Thread(() -> {
			long backoff = RECONNECT_INITIAL_MS;

			while (!shuttingDown) {
				try (Jedis jedis = new Jedis(endpoint, clientConfig)) {
					debug("Starting Redis subscription for channel: " + listener.getChannel());

					// Blocking call. Returns when unsubscribe() called or connection drops.
					jedis.subscribe(listener, listener.getChannel());

					// If we returned because we're shutting down, stop. Otherwise loop and reconnect.
					if (shuttingDown) {
						break;
					}

					debug("Redis subscription ended for channel " + listener.getChannel()
							+ " (will reconnect).");

				} catch (Exception e) {
					if (shuttingDown) {
						break;
					}
					debug("Redis subscribe error on channel " + listener.getChannel() + ": " + e.getMessage());
				}

				// Backoff before reconnecting
				if (!shuttingDown) {
					try {
						debug("Redis reconnect in " + backoff + "ms for channel: " + listener.getChannel());
						Thread.sleep(backoff);
					} catch (InterruptedException ie) {
						// If we're shutting down, exit; otherwise continue loop and try reconnect sooner.
						if (shuttingDown) {
							break;
						}
					}
					backoff = Math.min(RECONNECT_MAX_MS, backoff * 2);
				}
			}

			listenerThreads.remove(listener);
			debug("Redis subscription thread stopped for channel: " + listener.getChannel());
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
