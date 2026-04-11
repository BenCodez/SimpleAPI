package com.bencodez.simpleapi.valuerequest;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.plugin.Plugin;

import lombok.Getter;
import lombok.Setter;

/**
 * Handles value requests via the in-game sign editor. When a value is requested
 * through the sign input method a temporary sign is placed at the player's
 * location and the sign editing interface is opened. The player's input is
 * captured when the sign text is changed and passed to a callback.
 */
public class SignInputManager implements Listener {

    /**
     * The plugin used to register event listeners. This must be set prior to
     * requesting sign input.
     */
    @Getter
    @Setter
    private static Plugin plugin;

    /**
     * Flag indicating whether this manager has been registered with the
     * server's plugin manager. Registration occurs once per plugin.
     */
    @Getter
    @Setter
    private static boolean initialized = false;

    /**
     * Map of players currently waiting to provide sign input. The key is the
     * player's unique identifier and the value is the callback to invoke once
     * the sign text is submitted.
     */
    @Getter
    private static final Map<UUID, Consumer<String>> callbacks = new ConcurrentHashMap<>();

    /**
     * Register this manager as an event listener if it has not already been
     * registered. The provided plugin will be stored so that subsequent
     * sign input requests have a reference to the plugin.
     *
     * @param p the plugin used for event registration
     */
    public static void initialize(Plugin p) {
        if (!initialized && p != null) {
            plugin = p;
            Bukkit.getPluginManager().registerEvents(new SignInputManager(), p);
            initialized = true;
        }
    }

    /**
     * Request a value from a player using a sign editor. A temporary sign
     * will be placed at the player's current block location. The player will
     * immediately see the sign editing interface and can type their input.
     * When the sign text is changed the callback is invoked with the
     * concatenated text of all lines. The temporary sign is removed after
     * submission.
     *
     * @param player   the player to prompt
     * @param start    optional starting text to display on the first line of the sign
     * @param callback the callback to invoke with the submitted value
     */
    @SuppressWarnings("deprecation")
	public static void requestSignInput(Player player, String start, Consumer<String> callback) {
        if (!initialized && plugin != null) {
            // ensure we are registered
            initialize(plugin);
        }
        if (player == null || callback == null) {
            return;
        }
        callbacks.put(player.getUniqueId(), callback);
        // place a sign at the player's current block
        Block block = player.getLocation().getBlock();
        block.setType(Material.OAK_SIGN);
        Sign signState = (Sign) block.getState();
        if (start != null && !start.isEmpty()) {
            // populate first line with starting value
            signState.setLine(0, start);
        }
        signState.update(true);
        // open the sign editor for this sign
        player.openSign(signState);
    }

    /**
     * Event handler for when a player edits a sign. When the sign text is
     * changed this handler checks if the player has a pending callback. If
     * so the concatenated text from all lines is passed to the callback
     * and the temporary sign is removed.
     *
     * @param event the sign change event
     */
    @EventHandler
    public void onSignChange(SignChangeEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        Consumer<String> callback = callbacks.remove(uuid);
        if (callback != null) {
            StringBuilder sb = new StringBuilder();
            String[] lines = event.getLines();
            if (lines != null) {
                for (String line : lines) {
                    if (line != null) {
                        sb.append(line);
                    }
                }
            }
            // remove the temporary sign from the world
            event.getBlock().setType(Material.AIR);
            callback.accept(sb.toString());
        }
    }
}