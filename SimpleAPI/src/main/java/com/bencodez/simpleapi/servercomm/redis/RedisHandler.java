package com.bencodez.simpleapi.servercomm.redis;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import com.bencodez.simpleapi.servercomm.codec.JsonEnvelope;
import com.bencodez.simpleapi.servercomm.codec.JsonEnvelopeCodec;

import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.JedisPool;

public abstract class RedisHandler {

	private static final int PUBLISH_QUEUE_CAPACITY = 1024;
	private static final long PUBLISHER_SHUTDOWN_TIMEOUT_SECONDS = 3L;

	private final HostAndPort endpoint;
	private final JedisClientConfig clientConfig;
	private final JedisPool publisherPool;
	private final ThreadPoolExecutor publisherExecutor;

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
		this.publisherPool = new JedisPool(endpoint, clientConfig);
		this.publisherExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
				new ArrayBlockingQueue<>(PUBLISH_QUEUE_CAPACITY), runnable -> {
					Thread thread = new Thread(runnable, "RedisPublishThread-" + endpoint);
					thread.setDaemon(true);
					return thread;
				}, new ThreadPoolExecutor.AbortPolicy());
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

		publisherExecutor.shutdown();
		boolean interrupted = false;
		try {
			if (!publisherExecutor.awaitTermination(PUBLISHER_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
				publisherExecutor.shutdownNow();
			}
		} catch (InterruptedException e) {
			publisherExecutor.shutdownNow();
			interrupted = true;
		} finally {
			publisherPool.close();
			if (interrupted) {
				Thread.currentThread().interrupt();
			}
		}
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

	/**
	 * Queues an envelope for ordered asynchronous publishing. Network connection,
	 * authentication and publish I/O are never performed on the caller thread.
	 */
	public void publishEnvelope(String channel, JsonEnvelope envelope) {
		if (shuttingDown) {
			return;
		}

		String payload = JsonEnvelopeCodec.encode(envelope);
		try {
			publisherExecutor.execute(() -> publishNow(channel, payload));
		} catch (RejectedExecutionException e) {
			if (!shuttingDown) {
				debug("Redis publish queue is full; dropping message for channel " + channel);
			}
		}
	}

	/**
	 * Performs one publish using the pooled publisher connection. Kept protected so
	 * transport scheduling can be regression-tested without a live Redis server.
	 */
	protected void publishNow(String channel, String payload) {
		try (Jedis jedis = publisherPool.getResource()) {
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
