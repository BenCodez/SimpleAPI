package com.bencodez.simpleapi.valuerequest;

import org.bukkit.entity.Player;

/**
 * Callback interface for when a string value has been provided by a player.
 */
@FunctionalInterface
public interface StringListener {
    /**
     * Invoked when the player submits a string value.
     *
     * @param player the player who provided the input
     * @param value  the submitted value
     */
    void onInput(Player player, String value);
}
