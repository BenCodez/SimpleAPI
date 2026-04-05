package com.bencodez.simpleapi.messages.actionbar;

import java.util.UUID;

import org.bukkit.scheduler.BukkitTask;

import lombok.Getter;
import lombok.Setter;

/**
 * Stores the active action bar state for a player.
 */
@Getter
@Setter
public class ActionBarEntry {

    /**
     * Player unique id.
     */
    private UUID playerUuid;

    /**
     * Sequence used to prevent older messages from overriding newer ones.
     */
    private long sequence;

    /**
     * Active message.
     */
    private ActionBarMessage message;

    /**
     * Repeating resend task.
     */
    private BukkitTask repeatingTask;

    /**
     * Expiration task.
     */
    private BukkitTask expireTask;

    /**
     * Cancels all scheduled tasks.
     */
    public void cancel() {
        if (repeatingTask != null) {
            repeatingTask.cancel();
            repeatingTask = null;
        }
        if (expireTask != null) {
            expireTask.cancel();
            expireTask = null;
        }
    }
}