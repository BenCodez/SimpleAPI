package com.bencodez.simpleapi.skull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map.Entry;
import java.util.UUID;
import java.util.logging.Level;

import javax.net.ssl.HttpsURLConnection;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerProfile;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class SkullCache {

	/**
	 * Skulls and time are stored by uuid regardless of how they're cached or
	 * accessed.
	 */
	private static final HashMap<UUID, ItemStack> skullMap = new HashMap<>();
	private static final HashMap<UUID, Long> timeMap = new HashMap<>();

	private static final HashMap<String, ItemStack> skullBase64Map = new HashMap<>();
	private static final HashMap<String, Long> timeBase64Map = new HashMap<>();

	private static final HashMap<String, ItemStack> skullURLMap = new HashMap<>();
	private static final HashMap<String, Long> timeURLMap = new HashMap<>();

	/**
	 * Cache a skull from a uuid.
	 * 
	 * @param uuid The player's uuid.
	 */
	public static void cacheSkull(UUID uuid, String name) {
		skullMap.put(uuid, itemWithUuid(uuid, name));
		timeMap.put(uuid, System.currentTimeMillis());
	}

	public static void cacheSkullBase64(String base64) {
		skullBase64Map.put(base64, itemWithBase64(base64));
		timeBase64Map.put(base64, System.currentTimeMillis());
	}

	public static void cacheSkullURL(String url) {
		skullURLMap.put(url, itemWithURL(url));
		timeURLMap.put(url, System.currentTimeMillis());
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
				skullMap.put(entry.getKey(), itemWithUuid(entry.getKey(), entry.getValue()));
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
			skull = itemWithUuid(uuid, name);
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

	public static ItemStack getSkullBase64(String base64) {
		timeBase64Map.put(base64, System.currentTimeMillis());
		ItemStack skull = skullBase64Map.get(base64);
		if (skull == null) {
			skull = itemWithBase64(base64);
			cacheSkullBase64(base64);
		}
		return skull;
	}

	public static ItemStack getSkullURL(String url) {
		timeURLMap.put(url, System.currentTimeMillis());
		ItemStack skull = skullURLMap.get(url);
		if (skull == null) {
			skull = itemWithURL(url);
			cacheSkullURL(url);
		}
		return skull;
	}

	/**
	 * Get an array of player skulls from uuids.
	 * 
	 * @param players Array of uuids.
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
		for (String base64 : skullBase64Map.keySet()) {
			if (System.currentTimeMillis() - timeBase64Map.get(base64) > milliseconds) {
				skullBase64Map.remove(base64);
				timeBase64Map.remove(base64);
			}
		}
		for (String url : skullURLMap.keySet()) {
			if (System.currentTimeMillis() - timeURLMap.get(url) > milliseconds) {
				skullURLMap.remove(url);
				timeURLMap.remove(url);
			}
		}
	}

	/**
	 * Remove skulls from memory that haven't been cached or accessed within a week.
	 */
	public static void flushWeek() {
		flush(604800000);
	}

	@SuppressWarnings("deprecation")
	static private JsonParser parser = new JsonParser();
	static private String API_PROFILE_LINK = "https://sessionserver.mojang.com/session/minecraft/profile/";

	@SuppressWarnings("deprecation")
	public static String getSkinUrl(String uuid) {
		String json = getContent(API_PROFILE_LINK + uuid);
		JsonObject o = parser.parse(json).getAsJsonObject();
		String jsonBase64 = o.get("properties").getAsJsonArray().get(0).getAsJsonObject().get("value").getAsString();

		o = parser.parse(new String(Base64.getDecoder().decode(jsonBase64))).getAsJsonObject();
		String skinUrl = o.get("textures").getAsJsonObject().get("SKIN").getAsJsonObject().get("url").getAsString();
		return skinUrl;
	}

	public static String getContent(String link) {
		try {
			URL url = new URL(link);
			HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
			BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
			String outputLine = "";

			String inputLine;
			while ((inputLine = br.readLine()) != null) {
				outputLine += inputLine;
			}
			br.close();
			return outputLine;

		} catch (MalformedURLException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
		return null;
	}

	public static ItemStack getSkull(String url, UUID uuid) {
		ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
		if (url == null || url.isEmpty())
			return skull;
		SkullMeta skullMeta = (SkullMeta) skull.getItemMeta();
		PlayerProfile profile = Bukkit.getServer().createPlayerProfile(uuid);
		try {
			profile.getTextures().setSkin(new URL(getSkinUrl(uuid.toString())));
		} catch (MalformedURLException e) {
			e.printStackTrace();
		}
		skullMeta.setOwnerProfile(profile);
		skull.setItemMeta(skullMeta);
		return skull;
	}

	public static ItemStack getSkull(String url) {
		ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
		if (url == null || url.isEmpty())
			return skull;
		SkullMeta skullMeta = (SkullMeta) skull.getItemMeta();
		PlayerProfile profile = Bukkit.getServer().createPlayerProfile(UUID.randomUUID());
		try {
			profile.getTextures().setSkin(new URL(url));
		} catch (MalformedURLException e) {
			e.printStackTrace();
		}
		skullMeta.setOwnerProfile(profile);
		skull.setItemMeta(skullMeta);
		return skull;
	}

	public static String getUrlFromBase64(String base64) {
		String decoded = new String(Base64.getDecoder().decode(base64));
		// We simply remove the "beginning" and "ending" part of the JSON, so we're left
		// with only the URL. You could use a proper
		// JSON parser for this, but that's not worth it. The String will always start
		// exactly with this stuff anyway
		return decoded.substring("{\"textures\":{\"SKIN\":{\"url\":\"".length(), decoded.length() - "\"}}}".length());
	}

	public static ItemStack itemWithBase64(String base64) {
		return getSkull(getUrlFromBase64(base64));
	}

	public static ItemStack itemWithURL(String url) {
		return getSkull(url);
	}

	public static ItemStack itemWithUuid(UUID id, String playerName) {
		notNull(id, "id");

		return getSkull(getSkinUrl(id.toString()), id);
	}

	private static void notNull(Object o, String name) {
		if (o == null)
			throw new NullPointerException(name + " should not be null!");
	}
}
