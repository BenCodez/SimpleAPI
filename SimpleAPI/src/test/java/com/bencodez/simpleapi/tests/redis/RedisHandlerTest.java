package com.bencodez.simpleapi.tests.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.bencodez.simpleapi.servercomm.codec.JsonEnvelope;
import com.bencodez.simpleapi.servercomm.redis.RedisHandler;

public class RedisHandlerTest {

	@Test
	public void publishRunsOffCallerThreadAndPreservesOrder() throws Exception {
		CountDownLatch firstStarted = new CountDownLatch(1);
		CountDownLatch releaseFirst = new CountDownLatch(1);
		CountDownLatch completed = new CountDownLatch(2);
		List<String> channels = new CopyOnWriteArrayList<>();
		List<String> threadNames = new CopyOnWriteArrayList<>();

		RedisHandler handler = new RedisHandler("127.0.0.1", 6379, "", "", 0) {
			@Override
			public void debug(String message) {
				// no-op
			}

			@Override
			protected void publishNow(String channel, String payload) {
				threadNames.add(Thread.currentThread().getName());
				channels.add(channel);
				if (channels.size() == 1) {
					firstStarted.countDown();
					try {
						releaseFirst.await(2, TimeUnit.SECONDS);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
				}
				completed.countDown();
			}
		};

		try {
			String callerThread = Thread.currentThread().getName();
			JsonEnvelope envelope = JsonEnvelope.builder("Presence").put("server", "survival").build();

			handler.publishEnvelope("first", envelope);
			assertTrue(firstStarted.await(1, TimeUnit.SECONDS));

			long startNanos = System.nanoTime();
			handler.publishEnvelope("second", envelope);
			long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

			assertTrue(elapsedMillis < 250, "publishEnvelope should only enqueue work");
			releaseFirst.countDown();
			assertTrue(completed.await(2, TimeUnit.SECONDS));
			assertEquals(List.of("first", "second"), channels);
			assertEquals(2, threadNames.size());
			assertNotEquals(callerThread, threadNames.get(0));
			assertEquals(threadNames.get(0), threadNames.get(1));
		} finally {
			releaseFirst.countDown();
			handler.close();
		}
	}
}
