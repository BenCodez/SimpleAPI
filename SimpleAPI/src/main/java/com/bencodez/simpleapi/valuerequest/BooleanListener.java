package com.bencodez.simpleapi.valuerequest;

import org.bukkit.entity.Player;

/**
 * Callback interface for when a boolean value has been provided by a player.
 */
@FunctionalInterface
public interface BooleanListener {
    /**
     * Invoked when the player submits a boolean value.
     *
     * @param player the player who provided the input
     * @param value  the submitted boolean value
     */
    void onInput(Player player, boolean value);
}
