package com.bencodez.simpleapi.skull;

import java.util.HashMap;
import java.util.Map.Entry;
import java.util.UUID;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

public class SkullCache {

	/**
	 * Skulls and time are stored by uuid regardless of how they're cached or
	 * accessed.
	 */
	private static final HashMap<UUID, ItemStack> skullMap = new HashMap<>();
	private static final HashMap<UUID, Long> timeMap = new HashMap<>();

	/**
	 * Cache a skull from a uuid.
	 * 
	 * @param uuid The player's uuid.
	 */
	public static void cacheSkull(UUID uuid, String name) {
		skullMap.put(uuid, skullFromUuid(uuid, name));
		timeMap.put(uuid, System.currentTimeMillis());
	}

	/**
	 * Cache a skull from an offline player.
	 * 
	 * @param offlinePlayer The offline player.
	 */
	public static void cacheSkull(OfflinePlayer offlinePlayer) {
		cacheSkull(offlinePlayer.getUniqueId(), offlinePlayer.getName());
	}

	/**
	 * Cache a skull from an online player.
	 * 
	 * @param player The online player.
	 */
	public static void cacheSkull(Player player) {
		cacheSkull(player.getUniqueId(), player.getName());
	}

	/**
	 * Cache an array of skulls from uuids. Task will run asynchronously in an
	 * attempt to prevent server lag.
	 * 
	 * @param uuids Array of uuids.
	 */
	public static void cacheSkulls(HashMap<UUID, String> uuids) {
		new Thread(() -> {
			long start = System.currentTimeMillis();
			for (Entry<UUID, String> entry : uuids.entrySet()) {
				skullMap.put(entry.getKey(), skullFromUuid(entry.getKey(), entry.getValue()));
				timeMap.put(entry.getKey(), System.currentTimeMillis());
			}
			// Generate an inventory off the rip to try to fix the hashmap
			Inventory inventory = Bukkit.createInventory(null, 54, "Skull Cache Test");
			int i = 0;
			for (Entry<UUID, String> entry : uuids.entrySet()) {
				if (i < Math.min(54, uuids.size())) {
					inventory.setItem(i, SkullCache.getSkull(entry.getKey(), entry.getValue()));
					i++;
				}
			}
			inventory.clear();
			Bukkit.getLogger().log(Level.INFO, ChatColor.GREEN + "[SkullCache] Cached " + uuids.size() + " skulls in "
					+ (System.currentTimeMillis() - start) + "ms.");
		}).start();
	}

	/**
	 * Cache an array of skulls from offline players.
	 * 
	 * @param offlinePlayers Array of offline players.
	 */
	public static void cacheSkulls(OfflinePlayer[] offlinePlayers) {
		HashMap<UUID, String> map = new HashMap<UUID, String>();
		for (OfflinePlayer p : offlinePlayers) {
			map.put(p.getUniqueId(), p.getName());
		}
		cacheSkulls(map);
	}

	/**
	 * Cache an array of skulls from online players.
	 * 
	 * @param players Array of online players.
	 */
	public static void cacheSkulls(Player[] players) {
		HashMap<UUID, String> map = new HashMap<UUID, String>();
		for (Player p : players) {
			map.put(p.getUniqueId(), p.getName());
		}
		cacheSkulls(map);
	}

	/**
	 * Get a skull from a uuid. If the skull is not saved in memory it will be
	 * fetched from Mojang and then cached for future use.
	 * 
	 * @param uuid The player's uuid.
	 * @return ItemStack of the player's skull.
	 */
	public static ItemStack getSkull(UUID uuid, String name) {
		timeMap.put(uuid, System.currentTimeMillis());
		ItemStack skull = skullMap.get(uuid);
		if (skull == null) {
			skull = skullFromUuid(uuid, name);
			cacheSkull(uuid, name);
		}
		return skull;
	}

	public static boolean isLoaded(UUID uuid) {
		return skullMap.containsKey(uuid);
	}

	/**
	 * Get a skull from an offline player. If the skull is not saved in memory it
	 * will be fetched from Mojang and then cached for future use.
	 * 
	 * @param offlinePlayer The offline player.
	 * @return ItemStack of the offline player's skull.
	 */
	public static ItemStack getSkull(OfflinePlayer offlinePlayer) {
		return getSkull(offlinePlayer.getUniqueId(), offlinePlayer.getName());
	}

	/**
	 * Get a skull from an online player. If the skull is not saved in memory it
	 * will be fetched from Mojang and then cached for future use.
	 * 
	 * @param player The online player.
	 * @return ItemStack of the online player's skull.
	 */
	public static ItemStack getSkull(Player player) {
		return getSkull(player.getUniqueId(), player.getName());
	}

	/**
	 * Get an array of player skulls from uuids.
	 * 
	 * @param uuids Array of uuids.
	 * @return ItemStack array of skulls.
	 */
	public static ItemStack[] getSkulls(HashMap<UUID, String> players) {
		ItemStack[] itemStacks = new ItemStack[players.size()];
		int i = 0;
		for (Entry<UUID, String> entry : players.entrySet()) {
			timeMap.put(entry.getKey(), System.currentTimeMillis());
			itemStacks[i] = getSkull(entry.getKey(), entry.getValue());
			i++;
		}
		return itemStacks;
	}

	/**
	 * Get an array of offline player skulls from offline players.
	 * 
	 * @param offlinePlayers Array of offline players.
	 * @return ItemStack array of skulls.
	 */
	public static ItemStack[] getSkulls(OfflinePlayer[] offlinePlayers) {
		HashMap<UUID, String> map = new HashMap<UUID, String>();
		for (OfflinePlayer p : offlinePlayers) {
			map.put(p.getUniqueId(), p.getName());
		}
		return getSkulls(map);
	}

	/**
	 * Get an array of online player skulls from online players.
	 * 
	 * @param players Array of online players.
	 * @return ItemStack array of skulls.
	 */
	public static ItemStack[] getSkulls(Player[] players) {
		HashMap<UUID, String> map = new HashMap<UUID, String>();
		for (Player p : players) {
			map.put(p.getUniqueId(), p.getName());
		}
		return getSkulls(map);
	}

	/**
	 * Remove skulls from memory if they haven't been cached or accessed within the
	 * specified amount of time.
	 * 
	 * @param milliseconds Duration of time given in milliseconds.
	 */
	public static void flush(long milliseconds) {
		for (UUID uuid : skullMap.keySet()) {
			if (System.currentTimeMillis() - timeMap.get(uuid) > milliseconds) {
				skullMap.remove(uuid);
				timeMap.remove(uuid);
			}
		}
	}

	/**
	 * Remove skulls from memory that haven't been cached or accessed within a week.
	 */
	public static void flushWeek() {
		flush(604800000);
	}

	/**
	 * Creates a new player head item stack.
	 * 
	 * @return Player head.
	 */
	public static ItemStack createSkull() {
		return new ItemStack(Material.PLAYER_HEAD);
	}

	/**
	 * Creates a player skull item with the skin based on a player's UUID.
	 *
	 * @param id The player's UUID.
	 * @return The head of the player.
	 */
	public static ItemStack skullFromUuid(UUID id, String name) {
		try {
			return itemWithUuid(createSkull(), id, name);
		} catch (Exception exception) {
			exception.printStackTrace();
		}
		return null;
	}

	/**
	 * Modifies a skull to use the skin of the player with a given uuid.
	 *
	 * @param item The item to apply the name to. Must be a player skull.
	 * @param id   The player's uuid.
	 * @return The head of the player.
	 */
	@SuppressWarnings("deprecation")
	public static ItemStack itemWithUuid(ItemStack item, UUID id, String playerName) throws Exception {
		notNull(item, "item");
		notNull(id, "id");
		SkullMeta meta = (SkullMeta) item.getItemMeta();
		if (Bukkit.getOfflinePlayer(id).hasPlayedBefore()) {
			// Bukkit.getLogger().log(Level.INFO, ChatColor.GREEN + "[SkullCache] Cached " +
			// id + "/" + playerName);
			meta.setOwningPlayer(Bukkit.getOfflinePlayer(id));

		} else {
			meta.setOwningPlayer(Bukkit.getOfflinePlayer(playerName));
		}

		item.setItemMeta(meta);

		return item;
	}

	private static void notNull(Object o, String name) {
		if (o == null)
			throw new NullPointerException(name + " should not be null!");
	}
}
