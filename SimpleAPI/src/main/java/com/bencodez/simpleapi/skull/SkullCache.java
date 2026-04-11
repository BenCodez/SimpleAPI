package com.bencodez.simpleapi.skull;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
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
	private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	public static void cacheSkull(OfflinePlayer offlinePlayer) throws IOException {
		cacheSkull(offlinePlayer.getUniqueId(), offlinePlayer.getName());
	}

	public static void cacheSkull(Player player) throws IOException {
		cacheSkull(player.getUniqueId(), player.getName());
	}

	public static void cacheSkull(UUID uuid, String name) throws IOException {
		skullMap.put(uuid, itemWithUuid(uuid, name));
		timeMap.put(uuid, System.currentTimeMillis());
	}

	public static void cacheSkullBase64(String base64) {
		skullBase64Map.put(base64, itemWithBase64(base64));
		timeBase64Map.put(base64, System.currentTimeMillis());
	}

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

	public static void cacheSkulls(OfflinePlayer[] offlinePlayers) {
		HashMap<UUID, String> map = new HashMap<>();
		for (OfflinePlayer p : offlinePlayers) {
			map.put(p.getUniqueId(), p.getName());
		}
		cacheSkulls(map);
	}

	public static void cacheSkulls(Player[] players) {
		HashMap<UUID, String> map = new HashMap<>();
		for (Player p : players) {
			map.put(p.getUniqueId(), p.getName());
		}
		cacheSkulls(map);
	}

	public static void cacheSkullURL(String url) {
		skullURLMap.put(url, itemWithURL(url));
		timeURLMap.put(url, System.currentTimeMillis());
	}

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

	public static void flushWeek() {
		flush(604800000);
	}

	public static String getContent(String link) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder().uri(URI.create(link)).GET().timeout(Duration.ofSeconds(5))
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

		return o.get("textures").getAsJsonObject().get("SKIN").getAsJsonObject().get("url").getAsString();
	}

	@SuppressWarnings("deprecation")
	public static ItemStack getSkull(String url) {
		ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
		if (url == null || url.isEmpty()) {
			return skull;
		}
		SkullMeta skullMeta = (SkullMeta) skull.getItemMeta();
		PlayerProfile profile = Bukkit.getServer().createPlayerProfile(UUID.randomUUID());
		try {
			profile.getTextures().setSkin(new URL(url));
		} catch (Exception e) {
			e.printStackTrace();
		}
		skullMeta.setOwnerProfile(profile);
		skull.setItemMeta(skullMeta);
		return skull;
	}

	@SuppressWarnings("deprecation")
	public static ItemStack getSkull(String url, UUID uuid) throws IOException {
		ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
		if (url == null || url.isEmpty()) {
			return skull;
		}
		SkullMeta skullMeta = (SkullMeta) skull.getItemMeta();
		PlayerProfile profile = Bukkit.getServer().createPlayerProfile(uuid);
		profile.getTextures().setSkin(new URL(url));
		skullMeta.setOwnerProfile(profile);
		skull.setItemMeta(skullMeta);
		return skull;
	}

	public static ItemStack getSkull(UUID uuid, String name) throws IOException {
		timeMap.put(uuid, System.currentTimeMillis());
		ItemStack skull = skullMap.get(uuid);
		if (skull == null) {
			skull = itemWithUuid(uuid, name);
			cacheSkull(uuid, name);
		}
		return skull;
	}

	public static String getUrlFromBase64(String base64) {
		String decoded = new String(Base64.getDecoder().decode(base64));
		return decoded.substring("{\"textures\":{\"SKIN\":{\"url\":\"".length(), decoded.length() - "\"}}}".length());
	}

	public static ItemStack itemWithBase64(String base64) {
		return getSkull(getUrlFromBase64(base64));
	}

	/**
	 * Checks if a skull for the given UUID is already cached.
	 *
	 * @param uuid player UUID
	 * @return true if cached
	 */
	public static boolean isLoaded(UUID uuid) {
		return skullMap.containsKey(uuid);
	}

	public static ItemStack itemWithURL(String url) {
		return getSkull(url);
	}

	public static ItemStack itemWithUuid(UUID id, String playerName) throws IOException {
		return getSkull(getSkinUrl(id.toString()), id);
	}
}