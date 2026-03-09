package com.bencodez.simpleapi.dialog;

import java.util.HashMap;

import org.bukkit.entity.Player;

import com.bencodez.simpleapi.messages.MessageAPI;

/**
 * Utility class for dialog text formatting.
 */
public final class DialogTextFormatter {

	private DialogTextFormatter() {
	}

	/**
	 * Formats text with placeholders and color codes.
	 *
	 * @param player       the player
	 * @param text         the text
	 * @param placeholders the placeholders
	 * @return the formatted text
	 */
	public static String format(Player player, String text, HashMap<String, String> placeholders) {
		if (text == null) {
			return null;
		}

		String formatted = text;

		return MessageAPI.colorize(MessageAPI.replacePlaceHolder(formatted, placeholders, true));
	}
}