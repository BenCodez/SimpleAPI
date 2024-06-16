package com.bencodez.simpleapi.player;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import com.bencodez.simpleapi.serverhandle.CraftBukkitHandle;
import com.bencodez.simpleapi.serverhandle.IServerHandle;
import com.bencodez.simpleapi.serverhandle.SpigotHandle;
import com.google.common.collect.Iterables;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import lombok.Getter;

public class PlayerUtils {
	@Getter
	private static IServerHandle serverHandle = loadHandle();

	public static IServerHandle loadHandle() {
		try {
			Class.forName("org.spigotmc.SpigotConfig");
			return new SpigotHandle();
		} catch (Exception ex) {
			return serverHandle = new CraftBukkitHandle();
		}
	}

	private static final BlockFace[] axis = { BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST };

	private static final BlockFace[] radial = { BlockFace.NORTH, BlockFace.NORTH_EAST, BlockFace.EAST,
			BlockFace.SOUTH_EAST, BlockFace.SOUTH, BlockFace.SOUTH_WEST, BlockFace.WEST, BlockFace.NORTH_WEST };

	public static boolean canBreakBlock(Player p, Block b) {
		BlockBreakEvent block = new BlockBreakEvent(b, p);
		Bukkit.getPluginManager().callEvent(block);
		if (!block.isCancelled()) {
			return true;
		}
		return false;
	}

	public static Inventory getTopInventory(Player player) {
		InventoryView oInv = player.getOpenInventory();
		if (oInv == null) {
			return null;
		}
		Method method;
		try {
			method = InventoryView.class.getDeclaredMethod("getTopInventory");
			return (Inventory) method.invoke(oInv);
		} catch (NoSuchMethodException | SecurityException | IllegalAccessException | IllegalArgumentException
				| InvocationTargetException e) {
			e.printStackTrace();
		}
		return oInv.getTopInventory();
	}

	public static boolean canInteract(Player p, Block clickedBlock, Action action, ItemStack item,
			BlockFace clickedFace) {
		PlayerInteractEvent event = new PlayerInteractEvent(p, action, item, clickedBlock, clickedFace);
		Bukkit.getPluginManager().callEvent(event);
		if (event.useItemInHand().equals(Event.Result.DENY)) {
			return false;
		}
		return true;
	}

	public static java.util.UUID fetchUUID(String playerName) throws Exception {
		// Get response from Mojang API
		URL url = new URL("https://api.mojang.com/users/profiles/minecraft/" + playerName);
		HttpURLConnection connection = (HttpURLConnection) url.openConnection();
		connection.connect();

		if (connection.getResponseCode() == 400) {
			// plugin.debug("There is no player with the name \"" + playerName + "\"!");
			return null;
		}

		InputStream inputStream = connection.getInputStream();
		BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream));

		// Parse JSON response and get UUID

		JsonElement element = JsonParser.parseReader(bufferedReader);
		JsonObject object = element.getAsJsonObject();
		String uuidAsString = object.get("id").getAsString();

		// Return UUID
		return parseUUIDFromString(uuidAsString);
	}

	public static boolean hasEitherPermission(CommandSender sender, String perm) {
		if (sender instanceof Player) {

			if (perm.equals("")) {
				return true;
			}

			boolean hasPerm = false;

			for (String permission : perm.split("\\|")) {
				if (sender.hasPermission(permission)) {
					hasPerm = true;
				}
			}

			return hasPerm;

		} else {
			return true;
		}
	}

	/**
	 * Gets the player meta.
	 *
	 * @param player the player
	 * @param str    the str
	 * @return the player meta
	 */
	public static Object getPlayerMeta(JavaPlugin plugin, Player player, String str) {
		for (MetadataValue meta : player.getMetadata(str)) {
			if (meta.getOwningPlugin().equals(plugin)) {
				return meta.value();
			}
		}
		return null;
	}

	public static Player getRandomOnlinePlayer() {
		for (Player player : Bukkit.getOnlinePlayers()) {
			return player;
		}

		return null;
	}

	public static Player getRandomPlayer() {
		return Iterables.getFirst(Bukkit.getOnlinePlayers(), null);
	}

	/**
	 * Checks if is player.
	 *
	 * @param sender the sender
	 * @return true, if is player
	 */
	public static boolean isPlayer(CommandSender sender) {
		if (sender instanceof Player) {
			return true;
		}
		return false;
	}

	/**
	 * Checks if is player online.
	 *
	 * @param playerName the player name
	 * @return true, if is player online
	 */
	public static boolean isPlayerOnline(String playerName) {
		if (playerName == null) {
			return false;
		}
		Player player = Bukkit.getPlayerExact(playerName);
		if (player != null) {
			return true;
		}
		return false;
	}

	private static java.util.UUID parseUUIDFromString(String uuidAsString) {
		String[] parts = { "0x" + uuidAsString.substring(0, 8), "0x" + uuidAsString.substring(8, 12),
				"0x" + uuidAsString.substring(12, 16), "0x" + uuidAsString.substring(16, 20),
				"0x" + uuidAsString.substring(20, 32) };

		long mostSigBits = Long.decode(parts[0]).longValue();
		mostSigBits <<= 16;
		mostSigBits |= Long.decode(parts[1]).longValue();
		mostSigBits <<= 16;
		mostSigBits |= Long.decode(parts[2]).longValue();

		long leastSigBits = Long.decode(parts[3]).longValue();
		leastSigBits <<= 48;
		leastSigBits |= Long.decode(parts[4]).longValue();

		return new java.util.UUID(mostSigBits, leastSigBits);
	}

	/**
	 * Sets the player meta.
	 *
	 * @param player the player
	 * @param str    the str
	 * @param value  the value
	 */
	public static void setPlayerMeta(JavaPlugin plugin, Player player, String str, Object value) {
		player.removeMetadata(str, plugin);
		player.setMetadata(str, new MetadataValue() {

			@Override
			public boolean asBoolean() {

				return false;
			}

			@Override
			public byte asByte() {

				return 0;
			}

			@Override
			public double asDouble() {

				return 0;
			}

			@Override
			public float asFloat() {

				return 0;
			}

			@Override
			public int asInt() {

				return 0;
			}

			@Override
			public long asLong() {

				return 0;
			}

			@Override
			public short asShort() {

				return 0;
			}

			@Override
			public String asString() {

				return null;
			}

			@Override
			public Plugin getOwningPlugin() {
				return plugin;
			}

			@Override
			public void invalidate() {
			}

			@Override
			public Object value() {
				return value;
			}

		});
	}

	public static BlockFace yawToFace(float yaw, boolean useSubCardinalDirections) {
		if (useSubCardinalDirections) {
			return radial[Math.round(yaw / 45f) & 0x7].getOppositeFace();
		}

		return axis[Math.round(yaw / 90f) & 0x3].getOppositeFace();
	}
}
