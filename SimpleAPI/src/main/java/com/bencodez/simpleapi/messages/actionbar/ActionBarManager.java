package com.bencodez.simpleapi.messages.actionbar;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import lombok.Getter;
import lombok.Setter;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;

/**
 * Manages action bars for players.
 */
@Getter
@Setter
public class ActionBarManager {

    /**
     * Plugin used for scheduling.
     */
    private JavaPlugin plugin;

    /**
     * Active action bars by player uuid.
     */
    private Map<UUID, ActionBarEntry> activeActionBars = new ConcurrentHashMap<UUID, ActionBarEntry>();

    /**
     * Sequence generator for action bar ownership.
     */
    private AtomicLong sequenceCounter = new AtomicLong();

    /**
     * Whether lower priority messages should be ignored while a higher priority
     * message is active.
     */
    private boolean usePriority = true;

    /**
     * Creates the manager.
     *
     * @param plugin the plugin
     */
    public ActionBarManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Sends an action bar once.
     *
     * @param player the player
     * @param message the message
     */
    public void send(Player player, String message) {
        send(player, new ActionBarMessage(message));
    }

    /**
     * Sends an action bar message.
     *
     * @param player the player
     * @param message the message
     * @return true if the message was accepted
     */
    public boolean send(Player player, ActionBarMessage message) {
        if (player == null || !player.isOnline() || message == null) {
            return false;
        }

        UUID uuid = player.getUniqueId();
        ActionBarEntry current = activeActionBars.get(uuid);

        if (!canReplace(current, message)) {
            return false;
        }

        clear(uuid);

        final long sequence = sequenceCounter.incrementAndGet();
        final String processedMessage = message.getProcessedMessage();

        ActionBarEntry entry = new ActionBarEntry();
        entry.setPlayerUuid(uuid);
        entry.setSequence(sequence);
        entry.setMessage(message);

        activeActionBars.put(uuid, entry);

        sendNow(player, processedMessage);

        if (message.shouldRepeat()) {
            BukkitTask repeatingTask = plugin.getServer().getScheduler().runTaskTimer(plugin, new Runnable() {
                @Override
                public void run() {
                    ActionBarEntry active = activeActionBars.get(uuid);
                    if (active == null || active.getSequence() != sequence) {
                        ActionBarEntry staleEntry = entry;
                        if (staleEntry.getRepeatingTask() != null) {
                            staleEntry.getRepeatingTask().cancel();
                            staleEntry.setRepeatingTask(null);
                        }
                        return;
                    }

                    Player currentPlayer = plugin.getServer().getPlayer(uuid);
                    if (currentPlayer == null || !currentPlayer.isOnline()) {
                        clear(uuid);
                        return;
                    }

                    sendNow(currentPlayer, processedMessage);
                }
            }, message.getRepeatIntervalTicks(), message.getRepeatIntervalTicks());
            entry.setRepeatingTask(repeatingTask);
        }

        if (message.shouldExpire()) {
            BukkitTask expireTask = plugin.getServer().getScheduler().runTaskLater(plugin, new Runnable() {
                @Override
                public void run() {
                    ActionBarEntry active = activeActionBars.get(uuid);
                    if (active == null || active.getSequence() != sequence) {
                        return;
                    }

                    Player currentPlayer = plugin.getServer().getPlayer(uuid);
                    if (currentPlayer != null && currentPlayer.isOnline()) {
                        sendNow(currentPlayer, "");
                    }

                    clear(uuid);
                }
            }, message.getDurationTicks() + 1L);
            entry.setExpireTask(expireTask);
        }

        return true;
    }

    /**
     * Sends a temporary action bar.
     *
     * @param player the player
     * @param message the message
     * @param durationTicks the duration in ticks
     * @return true if the message was accepted
     */
    public boolean sendTemporary(Player player, String message, long durationTicks) {
        return send(player, new ActionBarMessage(message, durationTicks, 20L, true, ActionBarPriority.NORMAL));
    }

    /**
     * Sends a repeating action bar.
     *
     * @param player the player
     * @param message the message
     * @param durationTicks the duration in ticks
     * @param repeatIntervalTicks the repeat interval in ticks
     * @return true if the message was accepted
     */
    public boolean sendRepeating(Player player, String message, long durationTicks, long repeatIntervalTicks) {
        return send(player,
                new ActionBarMessage(message, durationTicks, repeatIntervalTicks, true, ActionBarPriority.NORMAL));
    }

    /**
     * Sends a repeating action bar with a priority.
     *
     * @param player the player
     * @param message the message
     * @param durationTicks the duration in ticks
     * @param repeatIntervalTicks the repeat interval in ticks
     * @param priority the priority
     * @return true if the message was accepted
     */
    public boolean sendRepeating(Player player, String message, long durationTicks, long repeatIntervalTicks,
            ActionBarPriority priority) {
        return send(player, new ActionBarMessage(message, durationTicks, repeatIntervalTicks, true, priority));
    }

    /**
     * Sends an action bar to all online players.
     *
     * @param message the message
     */
    public void sendToAll(String message) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            send(player, message);
        }
    }

    /**
     * Sends an action bar message to all online players.
     *
     * @param message the message
     */
    public void sendToAll(ActionBarMessage message) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            send(player, message);
        }
    }

    /**
     * Clears the active action bar for a player.
     *
     * @param player the player
     */
    public void clear(Player player) {
        if (player == null) {
            return;
        }

        clear(player.getUniqueId());
    }

    /**
     * Clears the active action bar for a player by uuid.
     *
     * @param uuid the player uuid
     */
    public void clear(UUID uuid) {
        ActionBarEntry entry = activeActionBars.remove(uuid);
        if (entry != null) {
            entry.cancel();
        }

        Player player = plugin.getServer().getPlayer(uuid);
        if (player != null && player.isOnline()) {
            sendNow(player, "");
        }
    }

    /**
     * Clears all active action bars.
     */
    public void clearAll() {
        for (UUID uuid : activeActionBars.keySet()) {
            clear(uuid);
        }
        activeActionBars.clear();
    }

    /**
     * Checks if a player has an active action bar.
     *
     * @param player the player
     * @return true if active
     */
    public boolean hasActiveActionBar(Player player) {
        if (player == null) {
            return false;
        }

        return activeActionBars.containsKey(player.getUniqueId());
    }

    /**
     * Checks if a new message can replace the current one.
     *
     * @param current the current entry
     * @param nextMessage the next message
     * @return true if replacement is allowed
     */
    protected boolean canReplace(ActionBarEntry current, ActionBarMessage nextMessage) {
        if (current == null) {
            return true;
        }

        if (!usePriority) {
            return true;
        }

        ActionBarMessage currentMessage = current.getMessage();
        if (currentMessage == null || currentMessage.getPriority() == null) {
            return true;
        }

        if (nextMessage == null || nextMessage.getPriority() == null) {
            return true;
        }

        return nextMessage.getPriority().ordinal() >= currentMessage.getPriority().ordinal();
    }

    /**
     * Sends an action bar immediately.
     *
     * @param player the player
     * @param message the message
     */
    @SuppressWarnings("deprecation")
    protected void sendNow(Player player, String message) {
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(message));
    }
}