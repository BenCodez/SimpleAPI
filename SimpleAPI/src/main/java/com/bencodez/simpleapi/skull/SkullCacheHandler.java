package com.bencodez.simpleapi.skull;

import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import lombok.Getter;
import lombok.Setter;

public abstract class SkullCacheHandler {

	@Getter
	private volatile int skullDelayTime = 4000;

	private volatile int currentDelayMs;

	private final Queue<String> skullsToLoad = new ConcurrentLinkedQueue<>();
	private final Set<String> queuedOrLoading = ConcurrentHashMap.newKeySet();

	private volatile boolean paused = false;

	@Getter
	@Setter
	private String bedrockPrefix = ".";

	private final ScheduledExecutorService timer = Executors.newScheduledThreadPool(1);
	private volatile ScheduledFuture<?> workerFuture;

	private final int minDelayMs = 250;
	private final int maxDelayMs = 30_000;
	private final int pauseMinSeconds = 120;
	private final int pauseMaxSeconds = 240;

	private volatile int backoffMultiplier = 1;

	private final long successDecayEveryMs = 60_000;
	private final AtomicLong lastErrorAt = new AtomicLong(0);
	private final AtomicLong lastSuccessAt = new AtomicLong(0);

	private final AtomicInteger rateLimitHitCount = new AtomicInteger(0);

	public SkullCacheHandler() {
		this.currentDelayMs = clamp(skullDelayTime, minDelayMs, maxDelayMs);
	}

	public SkullCacheHandler(int skullDelayTime) {
		this.skullDelayTime = skullDelayTime;
		this.currentDelayMs = clamp(skullDelayTime, minDelayMs, maxDelayMs);
	}

	public void addToCache(UUID uuid, String name) {
		if (uuid == null || name == null) {
			return;
		}

		String uuidStr = uuid.toString();

		if (uuidStr.length() > 14 && uuidStr.charAt(14) == '3') {
			return;
		}

		if (name.startsWith(bedrockPrefix)) {
			return;
		}

		if (name.length() > 16) {
			return;
		}

		if (SkullCache.isLoaded(uuid)) {
			return;
		}

		String key = uuidStr + "/" + name;

		if (queuedOrLoading.add(key)) {
			skullsToLoad.add(key);
		}
	}

	public void changeApiProfileURL(String url) {
		SkullCache.setApi_profile_link(url);
	}

	public void close() {
		try {
			if (workerFuture != null) {
				workerFuture.cancel(false);
			}
		} finally {
			timer.shutdownNow();
		}
	}

	public abstract void debugException(Exception e);

	public abstract void debugLog(String debug);

	public void flushCache() {
		SkullCache.flushWeek();
	}

	@SuppressWarnings("deprecation")
	public ItemStack getSkull(UUID uuid, String playerName) {
		Material skullMaterial;
		ItemStack skullItem;

		try {
			skullMaterial = Material.valueOf("PLAYER_HEAD");
			skullItem = new ItemStack(skullMaterial);
		} catch (IllegalArgumentException e) {
			skullMaterial = Material.valueOf("SKULL_ITEM");
			skullItem = new ItemStack(skullMaterial, 1, (short) 3);
		}

		if (uuid == null || playerName == null) {
			return skullItem;
		}

		String uuidStr = uuid.toString();

		if (playerName.length() > 16) {
			return skullItem;
		}

		if (paused && !SkullCache.isLoaded(uuid)) {
			return skullItem;
		}

		if (uuidStr.length() > 14 && uuidStr.charAt(14) == '3') {
			return skullItem;
		}

		try {
			return SkullCache.getSkull(uuid, playerName);
		} catch (Exception e) {
			onCacheError(e);
			return skullItem;
		}
	}

	public abstract void log(String log);

	public void pauseCaching() {
		onCacheError(null);
	}

	public void startTimer() {
		scheduleWorker(60_000, currentDelayMs);
	}

	private void scheduleWorker(long initialDelayMs, int delayMs) {
		int clamped = clamp(delayMs, minDelayMs, maxDelayMs);
		this.currentDelayMs = clamped;

		if (workerFuture != null) {
			workerFuture.cancel(false);
		}

		workerFuture = timer.scheduleWithFixedDelay(() -> {
			try {
				workerTick();
			} catch (Exception e) {
				debugException(e);
			}
		}, initialDelayMs, this.currentDelayMs, TimeUnit.MILLISECONDS);
	}

	private void workerTick() {
		if (paused) {
			tryDecayBackoff();
			return;
		}

		String text = skullsToLoad.poll();
		if (text == null) {
			tryDecayBackoff();
			return;
		}

		try {
			String[] data = text.split("/", 2);
			if (data.length != 2) {
				return;
			}

			SkullCache.cacheSkull(UUID.fromString(data[0]), data[1]);
			debugLog("Skull cached: " + data[0] + "/" + data[1] + " (queue=" + skullsToLoad.size() + ")");
			lastSuccessAt.set(System.currentTimeMillis());
			tryDecayBackoff();

		} catch (Exception e) {
			debugException(e);
			onCacheError(e);
		} finally {
			queuedOrLoading.remove(text);
		}
	}

	private void onCacheError(Exception e) {
		long now = System.currentTimeMillis();
		lastErrorAt.set(now);

		int hits = rateLimitHitCount.incrementAndGet();

		backoffMultiplier = Math.min(backoffMultiplier * 2, 64);

		int base = clamp(skullDelayTime, minDelayMs, maxDelayMs);
		int newDelay = clamp(base * backoffMultiplier, minDelayMs, maxDelayMs);
		newDelay = addJitter(newDelay, 0.15);

		int pauseSeconds = ThreadLocalRandom.current().nextInt(pauseMinSeconds, pauseMaxSeconds + 1);
		paused = true;

		if (hits == 1) {
			debugLog("Skull caching rate limit detected. Pausing for ~" + pauseSeconds + "s.");
		} else {
			log("Skull caching still rate limited (" + hits + " hits). Delay now " + newDelay + "ms.");
		}

		scheduleWorker(0, newDelay);

		timer.schedule(() -> paused = false, pauseSeconds, TimeUnit.SECONDS);
	}

	private void tryDecayBackoff() {
		long now = System.currentTimeMillis();

		long lastErr = lastErrorAt.get();
		if (lastErr != 0 && (now - lastErr) < successDecayEveryMs) {
			return;
		}

		long lastOk = lastSuccessAt.get();
		if (lastOk == 0 || (now - lastOk) < successDecayEveryMs) {
			return;
		}

		if (backoffMultiplier > 1) {
			backoffMultiplier = Math.max(1, backoffMultiplier / 2);

			int base = clamp(skullDelayTime, minDelayMs, maxDelayMs);
			int newDelay = clamp(base * backoffMultiplier, minDelayMs, maxDelayMs);
			newDelay = addJitter(newDelay, 0.10);

			if (Math.abs(newDelay - currentDelayMs) >= 50) {
				scheduleWorker(0, newDelay);
			}

			lastSuccessAt.set(now);
		}
	}

	private static int clamp(int v, int min, int max) {
		return Math.max(min, Math.min(max, v));
	}

	private static int addJitter(int valueMs, double pct) {
		int spread = (int) Math.max(1, Math.round(valueMs * pct));
		int delta = ThreadLocalRandom.current().nextInt(-spread, spread + 1);
		return Math.max(1, valueMs + delta);
	}
}
