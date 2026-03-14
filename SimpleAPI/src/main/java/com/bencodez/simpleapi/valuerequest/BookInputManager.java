package com.bencodez.simpleapi.valuerequest;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * Handles book based value requests. When a player is asked to provide input via
 * a book they are given a writable book. Once the book is signed the input is
 * read and passed to the provided callback.
 */
public class BookInputManager implements Listener {

    /**
     * Flag indicating whether the listener has been registered with the
     * server's plugin manager.
     */
    private static boolean registered = false;

    /**
     * The plugin responsible for registering this listener. Stored for future
     * requests to ensure correct registration.
     */
    private static Plugin plugin;

    /**
     * Map of player unique identifiers to their pending input callbacks.
     */
    private static final Map<UUID, Consumer<String>> callbacks = new ConcurrentHashMap<>();

    /**
     * Initialise the book input manager. Registers the event listener with the
     * plugin manager if it has not already been registered.
     *
     * @param p the plugin instance used for registration
     */
    public static void initialize(Plugin p) {
        if (!registered) {
            plugin = p;
            Bukkit.getPluginManager().registerEvents(new BookInputManager(), p);
            registered = true;
        }
    }

    /**
     * Prompt a player to provide a value via a writable book. The player will
     * receive a book in their inventory. Once the book is edited and signed the
     * contents of all pages will be concatenated and delivered to the provided
     * callback.
     *
     * @param player the player who will receive the book and provide input
     * @param start  the starting value to display in the book (unused in this
     *               implementation but reserved for future use)
     * @param callback the callback to invoke with the input once the player
     *                 finishes editing the book
     */
    public static void requestBookInput(Player player, String start, Consumer<String> callback) {
        // Ensure the listener is registered with the current plugin
        if (!registered && plugin != null) {
            initialize(plugin);
        }
        callbacks.put(player.getUniqueId(), callback);
        ItemStack book = new ItemStack(Material.WRITABLE_BOOK);
        player.getInventory().addItem(book);
        player.sendMessage("Please write your value in the book and sign it to submit.");
    }

    /**
     * Event handler for when a player edits and signs a book. If the player has
     * a pending callback in the callbacks map the contents of the book are
     * concatenated and supplied to the callback. The callback is then removed.
     *
     * @param event the book edit event
     */
    @EventHandler
    public void onPlayerEditBook(PlayerEditBookEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (!callbacks.containsKey(uuid)) {
            return;
        }
        Consumer<String> callback = callbacks.remove(uuid);
        if (callback == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String page : event.getNewBookMeta().getPages()) {
            if (page != null) {
                sb.append(page);
            }
        }
        callback.accept(sb.toString());
    }
}