package com.bencodez.simpleapi.tests.skull;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.bencodez.simpleapi.skull.SkullCache;
import com.bencodez.simpleapi.skull.SkullCacheHandler;

/**
 * Unit tests for {@link SkullCacheHandler}.
 *
 * <p>
 * These tests focus on:
 * <ul>
 * <li>Constructor parsing/clamping behavior</li>
 * <li>Queueing + filtering rules in
 * {@link SkullCacheHandler#addToCache(UUID, String)}</li>
 * <li>Rate-limit pause/backoff toggling via
 * {@link SkullCacheHandler#pauseCaching()}</li>
 * </ul>
 *
 * <p>
 * Tests are written to avoid invoking {@code SkullCache.getSkull(uuid,name)}
 * (which can hit network).
 */
public class SkullCacheHandlerTest {

	private TestHandler handler;

	@AfterEach
	public void cleanup() throws Exception {
		if (handler != null) {
			handler.close();
		}
		// Ensure SkullCache static map doesn't leak between tests
		clearSkullCacheLoadedMap();
	}

	@Test
	public void testConstructor_stringDuration_parsesMillis() {
		handler = new TestHandler("4s");
		assertEquals(4000, handler.getSkullDelayTime());
	}

	@Test
	public void testConstructor_stringDuration_invalid_clampsToMinDelay() {
		handler = new TestHandler("this-is-not-a-duration");
		// Invalid duration parses to 0ms, then is clamped to minDelayMs (250)
		assertEquals(250, handler.getSkullDelayTime());
	}

	@Test
	public void testConstructor_stringDuration_clampsToMax() {
		handler = new TestHandler("1000000ms"); // 1,000,000ms -> clamp to 30,000ms
		assertEquals(30_000, handler.getSkullDelayTime());
	}

	@Test
	public void testAddToCache_nulls_noQueue() throws Exception {
		handler = new TestHandler(4000);

		handler.addToCache(null, "Steve");
		handler.addToCache(UUID.randomUUID(), null);

		assertEquals(0, getQueue(handler).size());
		assertEquals(0, getQueuedSet(handler).size());
	}

	@Test
	public void testAddToCache_filtersBedrockPrefix_defaultDot() throws Exception {
		handler = new TestHandler(4000);

		handler.addToCache(UUID.randomUUID(), ".BedrockUser");

		assertEquals(0, getQueue(handler).size());
		assertEquals(0, getQueuedSet(handler).size());
	}

	@Test
	public void testAddToCache_filtersLongNameOver16() throws Exception {
		handler = new TestHandler(4000);

		handler.addToCache(UUID.randomUUID(), "ThisNameIsWayTooLong");

		assertEquals(0, getQueue(handler).size());
		assertEquals(0, getQueuedSet(handler).size());
	}

	@Test
	public void testAddToCache_filtersUuidVersion3Like() throws Exception {
		handler = new TestHandler(4000);

		// addToCache checks: uuidStr.length > 14 && uuidStr.charAt(14) == '3'
		// UUID string format: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
		// Position 14 (0-based) is inside the 3rd group; we just need it to be '3'
		UUID v3Uuid = UUID.fromString("12345678-1234-3abc-9def-1234567890ab");

		handler.addToCache(v3Uuid, "Steve");

		assertEquals(0, getQueue(handler).size());
		assertEquals(0, getQueuedSet(handler).size());
	}

	@Test
	public void testAddToCache_filtersAlreadyLoaded() throws Exception {
		handler = new TestHandler(4000);

		UUID uuid = UUID.randomUUID();
		setSkullCacheLoaded(uuid, new ItemStack(Material.STONE));

		handler.addToCache(uuid, "Steve");

		assertEquals(0, getQueue(handler).size());
		assertEquals(0, getQueuedSet(handler).size());
	}

	@Test
	public void testAddToCache_queuesOnce_andDedupes() throws Exception {
		handler = new TestHandler(4000);

		UUID uuid = UUID.randomUUID();
		handler.addToCache(uuid, "Steve");
		handler.addToCache(uuid, "Steve"); // should dedupe based on uuid/name key

		Queue<String> q = getQueue(handler);
		Set<String> s = getQueuedSet(handler);

		assertEquals(1, q.size());
		assertEquals(1, s.size());
		assertEquals(uuid.toString() + "/Steve", q.peek());
	}

	@Test
	public void testPauseCaching_setsPausedAndIncrementsRateLimitHitCount() throws Exception {
		handler = new TestHandler(4000);

		assertFalse(getPaused(handler));
		assertEquals(0, getRateLimitHitCount(handler));

		handler.pauseCaching();

		assertTrue(getPaused(handler));
		assertEquals(1, getRateLimitHitCount(handler));
	}

	@Test
	public void testGetSkull_returnsDefaultWhenNullParams() {
		handler = new TestHandler(4000);

		ItemStack result = handler.getSkull(null, null);
		assertNotNull(result);
	}

	@Test
	public void testGetSkull_whenPausedAndNotLoaded_returnsDefaultAndDoesNotThrow() throws Exception {
		handler = new TestHandler(4000);

		UUID uuid = UUID.randomUUID();
		handler.pauseCaching();

		// When paused and SkullCache.isLoaded(uuid) == false, handler.getSkull returns
		// the default skullItem early
		ItemStack result = handler.getSkull(uuid, "Steve");
		assertNotNull(result);
	}

	/**
	 * Minimal concrete test implementation for {@link SkullCacheHandler}.
	 */
	private static final class TestHandler extends SkullCacheHandler {

		private volatile String lastLog;
		private volatile String lastDebug;

		/**
		 * Creates handler with ms delay.
		 *
		 * @param skullDelayTime delay in milliseconds
		 */
		public TestHandler(int skullDelayTime) {
			super(skullDelayTime);
		}

		/**
		 * Creates handler with duration string.
		 *
		 * @param skullDelayTime duration string (e.g. 4s, 4000ms)
		 */
		public TestHandler(String skullDelayTime) {
			super(skullDelayTime);
		}

		@Override
		public void debugException(Exception e) {
			// swallow for tests
		}

		@Override
		public void debugLog(String debug) {
			lastDebug = debug;
		}

		@Override
		public void log(String log) {
			lastLog = log;
		}

		@SuppressWarnings("unused")
		public String getLastLog() {
			return lastLog;
		}

		@SuppressWarnings("unused")
		public String getLastDebug() {
			return lastDebug;
		}
	}

	@SuppressWarnings("unchecked")
	private static Queue<String> getQueue(SkullCacheHandler h) throws Exception {
		Field f = SkullCacheHandler.class.getDeclaredField("skullsToLoad");
		f.setAccessible(true);
		return (Queue<String>) f.get(h);
	}

	@SuppressWarnings("unchecked")
	private static Set<String> getQueuedSet(SkullCacheHandler h) throws Exception {
		Field f = SkullCacheHandler.class.getDeclaredField("queuedOrLoading");
		f.setAccessible(true);
		return (Set<String>) f.get(h);
	}

	private static boolean getPaused(SkullCacheHandler h) throws Exception {
		Field f = SkullCacheHandler.class.getDeclaredField("paused");
		f.setAccessible(true);
		return (boolean) f.get(h);
	}

	private static int getRateLimitHitCount(SkullCacheHandler h) throws Exception {
		Field f = SkullCacheHandler.class.getDeclaredField("rateLimitHitCount");
		f.setAccessible(true);
		Object atomicInt = f.get(h);
		return ((java.util.concurrent.atomic.AtomicInteger) atomicInt).get();
	}

	private static void clearSkullCacheLoadedMap() throws Exception {
		Field f = SkullCache.class.getDeclaredField("skullMap");
		f.setAccessible(true);

		@SuppressWarnings("unchecked")
		ConcurrentHashMap<UUID, ItemStack> skullMap = (ConcurrentHashMap<UUID, ItemStack>) f.get(null);
		skullMap.clear();
	}

	private static void setSkullCacheLoaded(UUID uuid, ItemStack item) throws Exception {
		Field f = SkullCache.class.getDeclaredField("skullMap");
		f.setAccessible(true);

		@SuppressWarnings("unchecked")
		ConcurrentHashMap<UUID, ItemStack> skullMap = (ConcurrentHashMap<UUID, ItemStack>) f.get(null);
		skullMap.put(uuid, item);
	}
}
