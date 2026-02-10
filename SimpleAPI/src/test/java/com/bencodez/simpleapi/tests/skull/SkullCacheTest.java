package com.bencodez.simpleapi.tests.skull;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.bencodez.simpleapi.skull.SkullCache;

/**
 * Unit tests for {@link SkullCache}.
 *
 * <p>
 * Notes:
 * <ul>
 *   <li>These tests avoid calling methods that can hit the network (Mojang profile lookup).</li>
 *   <li>Private caches are manipulated via reflection for deterministic behavior.</li>
 * </ul>
 */
public class SkullCacheTest {

	@AfterEach
	public void cleanup() throws Exception {
		clearAllSkullCacheMaps();
	}

	@Test
	public void testGetUrlFromBase64_valid() {
		String url = "http://textures.minecraft.net/texture/abc123";
		String json = "{\"textures\":{\"SKIN\":{\"url\":\"" + url + "\"}}}";
		String base64 = Base64.getEncoder().encodeToString(json.getBytes());

		assertEquals(url, SkullCache.getUrlFromBase64(base64));
	}

	@Test
	public void testGetUrlFromBase64_invalidBase64_throws() {
		assertThrows(IllegalArgumentException.class, () -> SkullCache.getUrlFromBase64("not-base64!!!"));
	}

	@Test
	public void testIsLoaded_falseWhenNotPresent() throws Exception {
		assertFalse(SkullCache.isLoaded(UUID.randomUUID()));
	}

	@Test
	public void testIsLoaded_trueWhenPresent() throws Exception {
		UUID uuid = UUID.randomUUID();

		@SuppressWarnings("unchecked")
		ConcurrentHashMap<UUID, ItemStack> skullMap = (ConcurrentHashMap<UUID, ItemStack>) getFieldValue(SkullCache.class,
				"skullMap");

		skullMap.put(uuid, new ItemStack(Material.STONE));

		assertTrue(SkullCache.isLoaded(uuid));
	}

	@Test
	public void testFlush_removesOldEntries_keepsNewEntries() throws Exception {
		long now = System.currentTimeMillis();

		// UUID cache
		UUID oldUuid = UUID.randomUUID();
		UUID newUuid = UUID.randomUUID();

		@SuppressWarnings("unchecked")
		ConcurrentHashMap<UUID, ItemStack> skullMap = (ConcurrentHashMap<UUID, ItemStack>) getFieldValue(SkullCache.class,
				"skullMap");
		@SuppressWarnings("unchecked")
		ConcurrentHashMap<UUID, Long> timeMap = (ConcurrentHashMap<UUID, Long>) getFieldValue(SkullCache.class, "timeMap");

		skullMap.put(oldUuid, new ItemStack(Material.STONE));
		timeMap.put(oldUuid, now - 10_000);

		skullMap.put(newUuid, new ItemStack(Material.DIRT));
		timeMap.put(newUuid, now);

		// Base64 cache
		String oldB64 = "oldb64";
		String newB64 = "newb64";

		@SuppressWarnings("unchecked")
		ConcurrentHashMap<String, ItemStack> skullBase64Map = (ConcurrentHashMap<String, ItemStack>) getFieldValue(
				SkullCache.class, "skullBase64Map");
		@SuppressWarnings("unchecked")
		ConcurrentHashMap<String, Long> timeBase64Map = (ConcurrentHashMap<String, Long>) getFieldValue(SkullCache.class,
				"timeBase64Map");

		skullBase64Map.put(oldB64, new ItemStack(Material.STONE));
		timeBase64Map.put(oldB64, now - 10_000);

		skullBase64Map.put(newB64, new ItemStack(Material.DIRT));
		timeBase64Map.put(newB64, now);

		// URL cache
		String oldUrl = "http://old";
		String newUrl = "http://new";

		@SuppressWarnings("unchecked")
		ConcurrentHashMap<String, ItemStack> skullURLMap = (ConcurrentHashMap<String, ItemStack>) getFieldValue(
				SkullCache.class, "skullURLMap");
		@SuppressWarnings("unchecked")
		ConcurrentHashMap<String, Long> timeURLMap = (ConcurrentHashMap<String, Long>) getFieldValue(SkullCache.class,
				"timeURLMap");

		skullURLMap.put(oldUrl, new ItemStack(Material.STONE));
		timeURLMap.put(oldUrl, now - 10_000);

		skullURLMap.put(newUrl, new ItemStack(Material.DIRT));
		timeURLMap.put(newUrl, now);

		// Flush anything older than 2 seconds
		SkullCache.flush(2_000);

		assertFalse(skullMap.containsKey(oldUuid));
		assertFalse(timeMap.containsKey(oldUuid));

		assertTrue(skullMap.containsKey(newUuid));
		assertTrue(timeMap.containsKey(newUuid));

		assertFalse(skullBase64Map.containsKey(oldB64));
		assertFalse(timeBase64Map.containsKey(oldB64));

		assertTrue(skullBase64Map.containsKey(newB64));
		assertTrue(timeBase64Map.containsKey(newB64));

		assertFalse(skullURLMap.containsKey(oldUrl));
		assertFalse(timeURLMap.containsKey(oldUrl));

		assertTrue(skullURLMap.containsKey(newUrl));
		assertTrue(timeURLMap.containsKey(newUrl));
	}

	@Test
	public void testNotNull_throwsNpeWithMessage() throws Exception {
		// private static void notNull(Object o, String name)
		Method m = SkullCache.class.getDeclaredMethod("notNull", Object.class, String.class);
		m.setAccessible(true);

		Exception ex = assertThrows(Exception.class, () -> m.invoke(null, new Object[] { null, "id" }));

		// InvocationTargetException wraps the NPE
		Throwable root = ex.getCause();
		assertNotNull(root);
		assertTrue(root instanceof NullPointerException);
		assertEquals("id should not be null!", root.getMessage());
	}

	private static void clearAllSkullCacheMaps() throws Exception {
		clearMapField(SkullCache.class, "skullMap");
		clearMapField(SkullCache.class, "timeMap");
		clearMapField(SkullCache.class, "skullBase64Map");
		clearMapField(SkullCache.class, "timeBase64Map");
		clearMapField(SkullCache.class, "skullURLMap");
		clearMapField(SkullCache.class, "timeURLMap");
	}

	private static void clearMapField(Class<?> clazz, String fieldName) throws Exception {
		Object value = getFieldValue(clazz, fieldName);
		assertNotNull(value, "Expected field " + fieldName + " to exist");
		if (value instanceof Map) {
			((Map<?, ?>) value).clear();
		} else {
			fail("Field " + fieldName + " was not a Map");
		}
	}

	private static Object getFieldValue(Class<?> clazz, String fieldName) throws Exception {
		Field f = clazz.getDeclaredField(fieldName);
		f.setAccessible(true);
		return f.get(null);
	}
}
