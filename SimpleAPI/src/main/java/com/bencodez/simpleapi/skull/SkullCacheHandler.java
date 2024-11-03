package com.bencodez.simpleapi.skull;

import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

public abstract class SkullCacheHandler {
	public SkullCacheHandler() {

	}

	public abstract void debugLog(String debug);

	public abstract void debugException(Exception e);

	Queue<String> skullsToLoad = new ConcurrentLinkedQueue<String>();

	private ScheduledExecutorService timer = Executors.newScheduledThreadPool(1);

	public void addToCache(UUID uuid, String name) {
		String text = uuid.toString() + "/" + name;
		if (!skullsToLoad.contains(text) && !SkullCache.isLoaded(uuid)) {
			if (name.length() <= 16) {
				skullsToLoad.add(text);
			} else {
				debugLog("Player name too long to preload skull: " + uuid.toString() + "/" + name);
			}
		}
	}

	public ItemStack getSkull(UUID uuid, String playerName) {
		if (playerName.length() > 16) {
			return new ItemStack(Material.PLAYER_HEAD);
		}
		try {
			return SkullCache.getSkull(uuid, playerName);
		} catch (Exception e) {
			return new ItemStack(Material.PLAYER_HEAD);
		}
	}

	public void close() {
		timer.shutdownNow();
	}

	public void startTimer() {
		timer.scheduleWithFixedDelay(new Runnable() {

			@Override
			public void run() {
				if (!skullsToLoad.isEmpty()) {
					String text = skullsToLoad.remove();
					try {
						String[] data = text.split("/");
						String uuid = data[0];
						String name = data[1];
						SkullCache.cacheSkull(UUID.fromString(uuid), name);
						debugLog("Loaded skull: " + uuid + "/" + name);
					} catch (Exception e) {
						debugException(e);
					}
				}
			}
		}, 30, 3, TimeUnit.SECONDS);
	}
}
