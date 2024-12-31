package com.bencodez.simpleapi.skull;

import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import lombok.Getter;

public abstract class SkullCacheHandler {
	@Getter
	private int skullDelayTime = 4000;

	Queue<String> skullsToLoad = new ConcurrentLinkedQueue<>();

	private boolean pause = false;

	private ScheduledExecutorService timer = Executors.newScheduledThreadPool(1);

	public SkullCacheHandler() {

	}

	public SkullCacheHandler(int skullDelayTime) {
		this.skullDelayTime = skullDelayTime;
	}

	public void addToCache(UUID uuid, String name) {
		String text = uuid.toString() + "/" + name;
		if (uuid.toString().charAt(14) == '3') {
			debugLog("Can't cache skull of offline player uuid: " + uuid.toString() + "/" + name);
			return;
		}
		if (!skullsToLoad.contains(text) && !SkullCache.isLoaded(uuid)) {
			if (name.length() <= 16) {
				skullsToLoad.add(text);
			} else {
				debugLog("Player name too long to preload skull: " + uuid.toString() + "/" + name);
			}
		}
	}

	public void changeApiProfileURL(String url) {
		SkullCache.setApi_profile_link(url);
	}

	public void close() {
		timer.shutdownNow();
	}

	public abstract void debugException(Exception e);

	public abstract void debugLog(String debug);

	public void flushCache() {
		SkullCache.flushWeek();
	}

	public ItemStack getSkull(UUID uuid, String playerName) {
		if (playerName.length() > 16 || (pause && !SkullCache.isLoaded(uuid)) || uuid.toString().charAt(14) == '3') {
			return new ItemStack(Material.PLAYER_HEAD);
		}
		try {
			return SkullCache.getSkull(uuid, playerName);
		} catch (Exception e) {
			pauseCaching();
			return new ItemStack(Material.PLAYER_HEAD);
		}
	}

	public abstract void log(String log);

	public void pauseCaching() {
		log("Pausing skull caching due to hitting rate limit or an error, increasing delay for caching");
		pause = true;
		skullDelayTime += 3000;
		timer.schedule(new Runnable() {

			@Override
			public void run() {
				unPuase();
			}
		}, 15, TimeUnit.MINUTES);
	}

	public void startTimer() {
		timer.scheduleWithFixedDelay(new Runnable() {

			@Override
			public void run() {
				if (!skullsToLoad.isEmpty() && !pause) {
					String text = skullsToLoad.remove();
					try {
						String[] data = text.split("/");
						String uuid = data[0];
						String name = data[1];
						SkullCache.cacheSkull(UUID.fromString(uuid), name);
						debugLog("Loaded skull: " + uuid + "/" + name);
					} catch (Exception e) {
						debugLog("Failed to load skull: " + text);
						debugException(e);
						pauseCaching();
					}
				}
			}
		}, 20000, skullDelayTime, TimeUnit.MILLISECONDS);
	}

	private void unPuase() {
		pause = false;
	}
}
