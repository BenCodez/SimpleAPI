package com.bencodez.simpleapi.valuerequest;

import org.bukkit.entity.Player;

/**
 * Callback interface for when a numeric value has been provided by a player.
 */
@FunctionalInterface
public interface NumberListener {
    /**
     * Invoked when the player submits a numeric value.
     *
     * @param player the player who provided the input
     * @param value  the submitted numeric value
     */
    void onInput(Player player, Number value);
}
