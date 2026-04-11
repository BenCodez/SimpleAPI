package com.bencodez.simpleapi.skull;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map.Entry;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

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

import lombok.Getter;
import lombok.Setter;

public class SkullCache {

	/**
	 * Skulls and time are stored by uuid regardless of how they're cached or
	 * accessed.
	 */
	private static final ConcurrentHashMap<UUID, ItemStack> skullMap = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<UUID, Long> timeMap = new ConcurrentHashMap<>();

	private static final ConcurrentHashMap<String, ItemStack> skullBase64Map = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<String, Long> timeBase64Map = new ConcurrentHashMap<>();

	private static final ConcurrentHashMap<String, ItemStack> skullURLMap = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<String, Long> timeURLMap = new ConcurrentHashMap<>();

	@Getter
	@Setter
	private static String api_profile_link = "https://sessionserver.mojang.com/session/minecraft/profile/";

	/**
	 * Shared HTTP client for Mojang requests.
	 */
	private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5))
			.build();

	/**
	 * Cache a skull from an offline player.
	 *
	 * @param offlinePlayer The offline player.
	 * @throws IOException If an I/O error occurs
	 */
	public static void cacheSkull(OfflinePlayer offlinePlayer) throws IOException {
		cacheSkull(offlinePlayer.getUniqueId(), offlinePlayer.getName());
	}

	/**
	 * Cache a skull from an online player.
	 *
	 * @param player The online player.
	 * @throws IOException If an I/O error occurs
	 */
	public static void cacheSkull(Player player) throws IOException {
		cacheSkull(player.getUniqueId(), player.getName());
	}

	/**
	 * Cache a skull from a uuid.
	 *
	 * @param uuid The player's uuid.
	 * @param name The player's name.
	 * @throws IOException If an I/O error occurs
	 */
	public static void cacheSkull(UUID uuid, String name) throws IOException {
		skullMap.put(uuid, itemWithUuid(uuid, name));
		timeMap.put(uuid, System.currentTimeMillis());
	}

	/**
	 * Cache a skull from a base64 texture string.
	 *
	 * @param base64 Base64 texture value
	 */
	public static void cacheSkullBase64(String base64) {
		skullBase64Map.put(base64, itemWithBase64(base64));
		timeBase64Map.put(base64, System.currentTimeMillis());
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
				try {
					skullMap.put(entry.getKey(), itemWithUuid(entry.getKey(), entry.getValue()));
					timeMap.put(entry.getKey(), System.currentTimeMillis());
				} catch (IOException e) {
					e.printStackTrace();
				}
			}

			Inventory inventory = Bukkit.createInventory(null, 54, "Skull Cache Test");
			int i = 0;
			for (Entry<UUID, String> entry : uuids.entrySet()) {
				if (i < Math.min(54, uuids.size())) {
					try {
						inventory.setItem(i, SkullCache.getSkull(entry.getKey(), entry.getValue()));
					} catch (IOException e) {
						e.printStackTrace();
					}
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
		HashMap<UUID, String> map = new HashMap<>();
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
		HashMap<UUID, String> map = new HashMap<>();
		for (Player p : players) {
			map.put(p.getUniqueId(), p.getName());
		}
		cacheSkulls(map);
	}

	/**
	 * Cache a skull from a texture URL.
	 *
	 * @param url Texture URL
	 */
	public static void cacheSkullURL(String url) {
		skullURLMap.put(url, itemWithURL(url));
		timeURLMap.put(url, System.currentTimeMillis());
	}

	/**
	 * Remove skulls from memory if they haven't been cached or accessed within the
	 * specified amount of time.
	 *
	 * @param milliseconds Duration of time given in milliseconds.
	 */
	public static void flush(long milliseconds) {
		for (UUID uuid : skullMap.keySet()) {
			Long time = timeMap.get(uuid);
			if (time != null && System.currentTimeMillis() - time > milliseconds) {
				skullMap.remove(uuid);
				timeMap.remove(uuid);
			}
		}
		for (String base64 : skullBase64Map.keySet()) {
			Long time = timeBase64Map.get(base64);
			if (time != null && System.currentTimeMillis() - time > milliseconds) {
				skullBase64Map.remove(base64);
				timeBase64Map.remove(base64);
			}
		}
		for (String url : skullURLMap.keySet()) {
			Long time = timeURLMap.get(url);
			if (time != null && System.currentTimeMillis() - time > milliseconds) {
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

	/**
	 * Gets raw content from a link.
	 *
	 * @param link The link
	 * @return Response body or null if 204
	 * @throws IOException If an I/O error occurs
	 * @throws InterruptedException If interrupted
	 */
	public static String getContent(String link) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(link))
				.GET()
				.timeout(Duration.ofSeconds(5))
				.build();

		HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

		if (response.statusCode() == 204) {
			return null;
		}

		if (response.statusCode() < 200 || response.statusCode() >= 300) {
			throw new IOException("HTTP " + response.statusCode());
		}

		return response.body();
	}

	/**
	 * Gets a skin URL from a Mojang profile UUID string.
	 *
	 * @param uuid Mojang UUID without changes to formatting logic
	 * @return Skin texture URL or null if unavailable
	 * @throws IOException If an I/O error occurs
	 */
	public static String getSkinUrl(String uuid) throws IOException {
		String json;
		try {
			json = getContent(api_profile_link + uuid);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		}

		if (json == null) {
			return null;
		}

		JsonObject o = JsonParser.parseString(json).getAsJsonObject();
		String jsonBase64 = o.get("properties").getAsJsonArray().get(0).getAsJsonObject().get("value").getAsString();

		o = JsonParser.parseString(new String(Base64.getDecoder().decode(jsonBase64))).getAsJsonObject();
		String skinUrl = o.get("textures").getAsJsonObject().get("SKIN").getAsJsonObject().get("url").getAsString();
		return skinUrl;
	}

	/**
	 * Get a skull from an offline player. If the skull is not saved in memory it
	 * will be fetched from Mojang and then cached for future use.
	 *
	 * @param offlinePlayer The offline player.
	 * @return ItemStack of the offline player's skull.
	 * @throws IOException If an I/O error occurs
	 */
	public static ItemStack getSkull(OfflinePlayer offlinePlayer) throws IOException {
		return getSkull(offlinePlayer.getUniqueId(), offlinePlayer.getName());
	}

	/**
	 * Get a skull from an online player. If the skull is not saved in memory it
	 * will be fetched from Mojang and then cached for future use.
	 *
	 * @param player The online player.
	 * @return ItemStack of the online player's skull.
	 * @throws IOException If an I/O error occurs
	 */
	public static ItemStack getSkull(Player player) throws IOException {
		return getSkull(player.getUniqueId(), player.getName());
	}

	/**
	 * Creates a skull from a texture URL.
	 *
	 * @param url Texture URL
	 * @return Skull item
	 */
	public static ItemStack getSkull(String url) {
		ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
		if (url == null || url.isEmpty()) {
			return skull;
		}
		SkullMeta skullMeta = (SkullMeta) skull.getItemMeta();
		PlayerProfile profile = Bukkit.getServer().createPlayerProfile(UUID.randomUUID());
		try {
			profile.getTextures().setSkin(URI.create(url).toURL());
		} catch (Exception e) {
			e.printStackTrace();
		}
		skullMeta.setOwnerProfile(profile);
		skull.setItemMeta(skullMeta);
		return skull;
	}

	/**
	 * Creates a skull from a texture URL and UUID.
	 *
	 * @param url Texture URL
	 * @param uuid UUID to apply to the profile
	 * @return Skull item
	 * @throws IOException If URL conversion fails
	 */
	public static ItemStack getSkull(String url, UUID uuid) throws IOException {
		ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
		if (url == null || url.isEmpty()) {
			return skull;
		}
		SkullMeta skullMeta = (SkullMeta) skull.getItemMeta();
		PlayerProfile profile = Bukkit.getServer().createPlayerProfile(uuid);
		profile.getTextures().setSkin(URI.create(url).toURL());
		skullMeta.setOwnerProfile(profile);
		skull.setItemMeta(skullMeta);
		return skull;
	}

	/**
	 * Get a skull from a uuid. If the skull is not saved in memory it will be
	 * fetched from Mojang and then cached for future use.
	 *
	 * @param uuid The player's uuid.
	 * @param name The player's name.
	 * @return ItemStack of the player's skull.
	 * @throws IOException If an I/O error occurs
	 */
	public static ItemStack getSkull(UUID uuid, String name) throws IOException {
		timeMap.put(uuid, System.currentTimeMillis());
		ItemStack skull = skullMap.get(uuid);
		if (skull == null) {
			skull = itemWithUuid(uuid, name);
			cacheSkull(uuid, name);
		}
		return skull;
	}

	/**
	 * Gets a skull from a base64 texture string.
	 *
	 * @param base64 Base64 texture value
	 * @return Skull item
	 */
	public static ItemStack getSkullBase64(String base64) {
		timeBase64Map.put(base64, System.currentTimeMillis());
		ItemStack skull = skullBase64Map.get(base64);
		if (skull == null) {
			skull = itemWithBase64(base64);
			cacheSkullBase64(base64);
		}
		return skull;
	}

	/**
	 * Get an array of player skulls from uuids.
	 *
	 * @param players Array of uuids.
	 * @return ItemStack array of skulls.
	 * @throws IOException If an I/O error occurs
	 */
	public static ItemStack[] getSkulls(HashMap<UUID, String> players) throws IOException {
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
	 * @throws IOException If an I/O error occurs
	 */
	public static ItemStack[] getSkulls(OfflinePlayer[] offlinePlayers) throws IOException {
		HashMap<UUID, String> map = new HashMap<>();
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
	 * @throws IOException If an I/O error occurs
	 */
	public static ItemStack[] getSkulls(Player[] players) throws IOException {
		HashMap<UUID, String> map = new HashMap<>();
		for (Player p : players) {
			map.put(p.getUniqueId(), p.getName());
		}
		return getSkulls(map);
	}

	/**
	 * Gets a skull from a texture URL cache.
	 *
	 * @param url Texture URL
	 * @return Skull item
	 */
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
	 * Converts a base64 texture property into the URL inside it.
	 *
	 * @param base64 Base64 texture value
	 * @return Texture URL
	 */
	public static String getUrlFromBase64(String base64) {
		String decoded = new String(Base64.getDecoder().decode(base64));
		return decoded.substring("{\"textures\":{\"SKIN\":{\"url\":\"".length(), decoded.length() - "\"}}}".length());
	}

	/**
	 * Checks if a skull for the given UUID is already cached.
	 *
	 * @param uuid Player UUID
	 * @return true if cached
	 */
	public static boolean isLoaded(UUID uuid) {
		return uuid != null && skullMap.containsKey(uuid);
	}

	/**
	 * Builds a skull from a base64 texture value.
	 *
	 * @param base64 Base64 texture value
	 * @return Skull item
	 */
	public static ItemStack itemWithBase64(String base64) {
		return getSkull(getUrlFromBase64(base64));
	}

	/**
	 * Builds a skull from a texture URL.
	 *
	 * @param url Texture URL
	 * @return Skull item
	 */
	public static ItemStack itemWithURL(String url) {
		return getSkull(url);
	}

	/**
	 * Builds a skull from a UUID.
	 *
	 * @param id UUID
	 * @param playerName Player name
	 * @return Skull item
	 * @throws IOException If an I/O error occurs
	 */
	public static ItemStack itemWithUuid(UUID id, String playerName) throws IOException {
		notNull(id, "id");
		return getSkull(getSkinUrl(id.toString()), id);
	}

	/**
	 * Null guard helper.
	 *
	 * @param o Object to check
	 * @param name Object name
	 */
	private static void notNull(Object o, String name) {
		if (o == null) {
			throw new NullPointerException(name + " should not be null!");
		}
	}
}