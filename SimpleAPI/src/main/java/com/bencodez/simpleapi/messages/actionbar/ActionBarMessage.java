package com.bencodez.simpleapi.messages.actionbar;

import com.bencodez.simpleapi.messages.MessageAPI;

import lombok.Getter;
import lombok.Setter;

/**
 * Represents an action bar message request.
 */
@Getter
@Setter
public class ActionBarMessage {

    /**
     * Raw message.
     */
    private String message;

    /**
     * Duration in ticks.
     *
     * A value less than 0 means no automatic expiration.
     */
    private long durationTicks;

    /**
     * Repeat interval in ticks.
     *
     * A value less than or equal to 0 means send once only.
     */
    private long repeatIntervalTicks;

    /**
     * Whether to colorize the message.
     */
    private boolean colorize;

    /**
     * Message priority.
     */
    private ActionBarPriority priority;

    /**
     * Creates a single-send normal priority message.
     *
     * @param message the message
     */
    public ActionBarMessage(String message) {
        this(message, -1L, -1L, true, ActionBarPriority.NORMAL);
    }

    /**
     * Creates a timed message.
     *
     * @param message the message
     * @param durationTicks the duration in ticks
     */
    public ActionBarMessage(String message, long durationTicks) {
        this(message, durationTicks, 20L, true, ActionBarPriority.NORMAL);
    }

    /**
     * Creates a timed repeating message.
     *
     * @param message the message
     * @param durationTicks the duration in ticks
     * @param repeatIntervalTicks the repeat interval in ticks
     */
    public ActionBarMessage(String message, long durationTicks, long repeatIntervalTicks) {
        this(message, durationTicks, repeatIntervalTicks, true, ActionBarPriority.NORMAL);
    }

    /**
     * Creates a full action bar message.
     *
     * @param message the message
     * @param durationTicks the duration in ticks
     * @param repeatIntervalTicks the repeat interval in ticks
     * @param colorize whether to colorize the message
     * @param priority the priority
     */
    public ActionBarMessage(String message, long durationTicks, long repeatIntervalTicks, boolean colorize,
            ActionBarPriority priority) {
        this.message = message;
        this.durationTicks = durationTicks;
        this.repeatIntervalTicks = repeatIntervalTicks;
        this.colorize = colorize;
        this.priority = priority;
    }

    /**
     * Gets the final processed message.
     *
     * @return the processed message
     */
    public String getProcessedMessage() {
        if (colorize) {
            return MessageAPI.colorize(message);
        }
        return message;
    }

    /**
     * Checks if this message should repeat.
     *
     * @return true if it should repeat
     */
    public boolean shouldRepeat() {
        return repeatIntervalTicks > 0L && durationTicks != 0L;
    }

    /**
     * Checks if this message should expire automatically.
     *
     * @return true if it should expire
     */
    public boolean shouldExpire() {
        return durationTicks >= 0L;
    }
}