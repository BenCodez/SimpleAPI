package com.bencodez.simpleapi.valuerequest;

import java.util.List;
import org.bukkit.entity.Player;

/**
 * Callback interface for receiving multiple string values from a multi-field
 * value request. When a request for multiple strings completes this
 * listener is invoked with the list of values in the same order as the
 * prompts provided.
 */
public interface MultiStringListener {

    /**
     * Invoked when the player has provided all requested string values. The
     * values list will contain an entry for each prompt passed to the
     * requesting method.
     *
     * @param player the player who provided the input
     * @param values the list of values corresponding to each requested field
     */
    void onInput(Player player, List<String> values);
}