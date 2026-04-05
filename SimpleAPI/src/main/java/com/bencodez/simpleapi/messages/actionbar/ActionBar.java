package com.bencodez.simpleapi.messages.actionbar;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import lombok.Getter;
import lombok.Setter;

/**
 * Compatibility wrapper for sending action bars.
 */
@Getter
@Setter
public class ActionBar {

    /**
     * Action bar manager.
     */
    private static ActionBarManager manager;

    /**
     * Message.
     */
    private String msg;

    /**
     * Duration in ticks.
     */
    private long duration;

    /**
     * Repeat interval in ticks.
     */
    private long repeatInterval;

    /**
     * Priority.
     */
    private ActionBarPriority priority;

    /**
     * Creates a new action bar.
     *
     * @param msg the message
     */
    public ActionBar(String msg) {
        this(msg, -1L, -1L, ActionBarPriority.NORMAL);
    }

    /**
     * Creates a new action bar.
     *
     * @param msg the message
     * @param duration the duration in ticks
     */
    public ActionBar(String msg, long duration) {
        this(msg, duration, 20L, ActionBarPriority.NORMAL);
    }

    /**
     * Creates a new action bar.
     *
     * @param msg the message
     * @param duration the duration in ticks
     * @param repeatInterval the repeat interval in ticks
     */
    public ActionBar(String msg, long duration, long repeatInterval) {
        this(msg, duration, repeatInterval, ActionBarPriority.NORMAL);
    }

    /**
     * Creates a new action bar.
     *
     * @param msg the message
     * @param duration the duration in ticks
     * @param repeatInterval the repeat interval in ticks
     * @param priority the priority
     */
    public ActionBar(String msg, long duration, long repeatInterval, ActionBarPriority priority) {
        this.msg = msg;
        this.duration = duration;
        this.repeatInterval = repeatInterval;
        this.priority = priority;
    }

    /**
     * Sets the manager used by this API.
     *
     * @param manager the manager
     */
    public static void setManager(ActionBarManager manager) {
        ActionBar.manager = manager;
    }

    /**
     * Gets the current manager.
     *
     * @return the manager
     */
    public static ActionBarManager getManager() {
        return manager;
    }

    /**
     * Sends this action bar to players.
     *
     * @param players the players
     */
    public void send(Player... players) {
        if (manager == null || players == null) {
            return;
        }

        ActionBarMessage message = new ActionBarMessage(msg, duration, repeatInterval, true, priority);
        for (Player player : players) {
            manager.send(player, message);
        }
    }

    /**
     * Sends an action bar immediately.
     *
     * @param player the player
     * @param message the message
     */
    public void sendActionBar(Player player, String message) {
        if (manager == null) {
            return;
        }

        manager.send(player, new ActionBarMessage(message));
    }

    /**
     * Sends an action bar with duration.
     *
     * @param player the player
     * @param message the message
     * @param duration the duration in ticks
     */
    public void sendActionBar(Player player, String message, int duration) {
        if (manager == null) {
            return;
        }

        manager.send(player, new ActionBarMessage(message, duration, 20L, true, ActionBarPriority.NORMAL));
    }

    /**
     * Sends an action bar to all online players.
     *
     * @param message the message
     */
    public void sendActionBarToAllPlayers(String message) {
        sendActionBarToAllPlayers(message, -1);
    }

    /**
     * Sends an action bar to all online players.
     *
     * @param message the message
     * @param duration the duration in ticks
     */
    public void sendActionBarToAllPlayers(String message, int duration) {
        if (manager == null) {
            return;
        }

        ActionBarMessage actionBarMessage = new ActionBarMessage(message, duration, 20L, true,
                ActionBarPriority.NORMAL);
        for (Player player : Bukkit.getOnlinePlayers()) {
            manager.send(player, actionBarMessage);
        }
    }

    /**
     * Clears an action bar from a player.
     *
     * @param player the player
     */
    public static void clear(Player player) {
        if (manager == null) {
            return;
        }

        manager.clear(player);
    }
}