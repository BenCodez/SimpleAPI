package com.bencodez.simpleapi.valuerequest;

import org.bukkit.entity.Player;

/**
 * Callback for multi-value requests.
 */
@FunctionalInterface
public interface MultiValueListener {

	void onInput(Player player, MultiValueResult result);
}