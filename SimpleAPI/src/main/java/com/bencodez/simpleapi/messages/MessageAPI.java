package com.bencodez.simpleapi.messages;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.entity.Player;

import com.bencodez.simpleapi.array.ArrayUtils;
import com.bencodez.simpleapi.messages.hover.HoverEventSupport;
import com.bencodez.simpleapi.player.PlayerUtils;

import lombok.Getter;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.chat.ComponentSerializer;

public class MessageAPI {
	@Getter
	private static final HoverEventSupport hoverEventSupport = HoverEventSupport.findInstance();

	public static final char COLOR_CHAR = ChatColor.COLOR_CHAR;

	public static String colorize(String format) {
		if (format == null) {
			return null;
		}
		format = format.replace("{AQUA}", "�b").replace("{BLACK}", "�0").replace("{BLUE}", "�9")
				.replace("{DARK_AQUA}", "�3").replace("{DARK_BLUE}", "�1").replace("{DARK_GRAY}", "�8")
				.replace("{DARK_GREEN}", "�2").replace("{DARK_PURPLE}", "�5").replace("{DARK_RED}", "�4")
				.replace("{GOLD}", "�6").replace("{GRAY}", "�7").replace("{GREEN}", "�a")
				.replace("{LIGHT_PURPLE}", "�d").replace("{RED}", "�c").replace("{WHITE}", "�f")
				.replace("{YELLOW}", "�e").replace("{BOLD}", "�l").replace("{ITALIC}", "�o").replace("{MAGIC}", "�k")
				.replace("{RESET}", "�r").replace("{STRIKE}", "�m").replace("{STRIKETHROUGH}", "�m")
				.replace("{UNDERLINE}", "�n");

		// hex format: &#FF0000#
		format = translateHexColorCodes("&#", "#", format);
		// hex format: &#FF0000
		format = translateHexColorCodes("&#", "", format);
		return ChatColor.translateAlternateColorCodes('&', format);
	}

	/**
	 * Comp to string.
	 *
	 * @param comp the comp
	 * @return the string
	 */
	public static String compToString(TextComponent comp) {
		return colorize(comp.toPlainText());
	}

	public static boolean contains(String str1, String str2) {
		return str1.contains(str2);
	}

	public static boolean containsIgnorecase(String str1, String str2) {
		if (str1 == null || str2 == null) {
			return false;
		}
		return str1.toLowerCase().contains(str2.toLowerCase());
	}

	public static boolean containsJson(String msg) {
		return contains(msg, "[Text=\"");
	}

	public static String getProgressBar(int current, int max, int totalBars, String symbol, String completedColor,
			String notCompletedColor) {

		float percent = (float) current / max;

		int progressBars = (int) (totalBars * percent);

		int leftOver = (totalBars - progressBars);

		StringBuilder sb = new StringBuilder();
		sb.append(ChatColor.translateAlternateColorCodes('&', completedColor));
		for (int i = 0; i < progressBars; i++) {
			sb.append(symbol);
		}
		sb.append(ChatColor.translateAlternateColorCodes('&', notCompletedColor));
		for (int i = 0; i < leftOver; i++) {
			sb.append(symbol);
		}
		return sb.toString();
	}

	public static boolean isDouble(String st) {
		if (st == null) {
			return false;
		}
		try {
			@SuppressWarnings("unused")
			double num = Double.parseDouble(st);
			return true;

		} catch (NumberFormatException ex) {
			return false;
		}
	}

	/**
	 * Checks if is int.
	 *
	 * @param st the st
	 * @return true, if is int
	 */
	public static boolean isInt(String st) {
		if (st == null) {
			return false;
		}
		try {
			@SuppressWarnings("unused")
			int num = Integer.parseInt(st);
			return true;

		} catch (NumberFormatException ex) {
			return false;
		}
	}

	/**
	 * Replace ignore case.
	 *
	 * @param str         the str
	 * @param toReplace   the to replace
	 * @param replaceWith the replace with
	 * @return the string
	 */
	public static String replaceIgnoreCase(String str, String toReplace, String replaceWith) {
		if (str == null) {
			return "";
		}
		if ((toReplace == null) || (replaceWith == null)) {
			return str;
		}

		try {
			return Pattern.compile(toReplace, Pattern.CASE_INSENSITIVE).matcher(str).replaceAll(replaceWith);
		} catch (IndexOutOfBoundsException e) {
			return str.replace(toReplace, replaceWith);
		}
	}

	/**
	 * Round decimals.
	 *
	 * @param num      the num
	 * @param decimals the decimals
	 * @return the string
	 */
	public static String roundDecimals(double num, int decimals) {
		num = num * Math.pow(10, decimals);
		num = Math.round(num);
		num = num / Math.pow(10, decimals);
		DecimalFormat df = new DecimalFormat("#.00");
		return df.format(num);
	}

	public static void sendJson(Player player, ArrayList<TextComponent> messages) {
		if ((player != null) && (messages != null)) {
			ArrayList<BaseComponent> texts = new ArrayList<>();
			TextComponent newLine = new TextComponent(ComponentSerializer.parse("{text: \"\n\"}"));
			for (int i = 0; i < messages.size(); i++) {
				TextComponent txt = messages.get(i);

				texts.add(txt);
				if (i + 1 < messages.size()) {
					texts.add(newLine);
				}

			}

			PlayerUtils.getServerHandle().sendMessage(player, ArrayUtils.convertBaseComponent(texts));
		}

	}

	public static void sendJson(Player player, TextComponent message) {
		if ((player != null) && (message != null)) {
			message.setText(message.getText());
			PlayerUtils.getServerHandle().sendMessage(player, message);
		}
	}

	/**
	 * Starts with ignore case.
	 *
	 * @param str1 the str 1
	 * @param str2 the str 2
	 * @return true, if successful
	 */
	public static boolean startsWithIgnoreCase(String str1, String str2) {
		return str1.toLowerCase().startsWith(str2.toLowerCase());
	}

	/**
	 * String to comp.
	 *
	 * @param string the string
	 * @return the text component
	 */
	public static TextComponent stringToComp(String string) {
		TextComponent base = new TextComponent("");
		boolean previousLetter = false;
		ChatColor currentColor = null;
		boolean bold = false;
		boolean italic = false;
		boolean underline = false;
		boolean strike = false;
		boolean magic = false;
		String currentstring = "";
		for (int i = 0; i < string.length(); i++) {
			char c = string.charAt(i);
			if (c == '&') {
				if (string.charAt(i + 1) == '#') {
					String hexColor = "";
					for (int j = i + 2; j < i + 2 + 6; j++) {
						hexColor += string.charAt(j);
					}
					if (string.charAt(i + 8) == '#') {
						i += 8;
						previousLetter = false;

						TextComponent newTC = new TextComponent(currentstring);
						if (currentColor != null) {
							newTC.setColor(currentColor);
						}
						currentstring = "";
						newTC.setBold(bold);
						newTC.setItalic(italic);
						newTC.setUnderlined(underline);
						newTC.setStrikethrough(strike);
						newTC.setObfuscated(magic);
						base.addExtra(newTC);
						currentColor = ChatColor.of("#" + hexColor);

					}
				} else if (string.charAt(i + 1) == 'l') {
					if (previousLetter) {
						TextComponent newTC = new TextComponent(currentstring);
						if (currentColor != null) {
							newTC.setColor(currentColor);
						}
						newTC.setBold(bold);
						newTC.setItalic(italic);
						newTC.setUnderlined(underline);
						newTC.setStrikethrough(strike);
						newTC.setObfuscated(magic);
						base.addExtra(newTC);
						bold = false;
						italic = false;
						underline = false;
						strike = false;
						magic = false;
						currentstring = "";
						currentColor = null;
						i++;
						previousLetter = false;
					} else {
						bold = true;
						i++;
					}
				} else if (string.charAt(i + 1) == 'k') {
					if (previousLetter) {
						TextComponent newTC = new TextComponent(currentstring);
						if (currentColor != null) {
							newTC.setColor(currentColor);
						}
						newTC.setBold(bold);
						newTC.setItalic(italic);
						newTC.setUnderlined(underline);
						newTC.setStrikethrough(strike);
						newTC.setObfuscated(magic);
						base.addExtra(newTC);
						bold = false;
						italic = false;
						underline = false;
						strike = false;
						magic = false;
						currentstring = "";
						currentColor = null;
						i++;
						previousLetter = false;
					} else {
						magic = true;
						i++;
					}
				} else if (string.charAt(i + 1) == 'm') {
					if (previousLetter) {
						TextComponent newTC = new TextComponent(currentstring);
						if (currentColor != null) {
							newTC.setColor(currentColor);
						}
						newTC.setBold(bold);
						newTC.setItalic(italic);
						newTC.setUnderlined(underline);
						newTC.setStrikethrough(strike);
						newTC.setObfuscated(magic);
						base.addExtra(newTC);
						bold = false;
						italic = false;
						underline = false;
						strike = false;
						magic = false;
						currentstring = "";
						currentColor = null;
						i++;
						previousLetter = false;
					} else {
						strike = true;
						i++;
					}
				} else if (string.charAt(i + 1) == 'n') {
					if (previousLetter) {
						TextComponent newTC = new TextComponent(currentstring);
						if (currentColor != null) {
							newTC.setColor(currentColor);
						}
						newTC.setBold(bold);
						newTC.setItalic(italic);
						newTC.setUnderlined(underline);
						newTC.setStrikethrough(strike);
						newTC.setObfuscated(magic);
						base.addExtra(newTC);
						bold = false;
						italic = false;
						underline = false;
						strike = false;
						magic = false;
						currentstring = "";
						currentColor = null;
						i++;
						previousLetter = false;
					} else {
						underline = true;
						i++;
					}
				} else if (string.charAt(i + 1) == 'o') {
					if (previousLetter) {
						TextComponent newTC = new TextComponent(currentstring);
						if (currentColor != null) {
							newTC.setColor(currentColor);
						}
						newTC.setBold(bold);
						newTC.setItalic(italic);
						newTC.setUnderlined(underline);
						newTC.setStrikethrough(strike);
						newTC.setObfuscated(magic);
						base.addExtra(newTC);
						bold = false;
						italic = false;
						underline = false;
						strike = false;
						magic = false;
						currentstring = "";
						currentColor = null;
						i++;
						previousLetter = false;
					} else {
						italic = true;
						i++;
					}
				} else if (string.charAt(i + 1) == 'r') {
					TextComponent newTC = new TextComponent(currentstring);
					if (currentColor != null) {
						newTC.setColor(currentColor);
					}
					newTC.setBold(bold);
					newTC.setItalic(italic);
					newTC.setUnderlined(underline);
					newTC.setStrikethrough(strike);
					newTC.setObfuscated(magic);
					base.addExtra(newTC);
					bold = false;
					italic = false;
					underline = false;
					strike = false;
					magic = false;
					currentstring = "";
					currentColor = null;
					i++;
					previousLetter = false;
				} else if (ChatColor.getByChar(string.charAt(i + 1)) != null) {
					if (previousLetter) {
						TextComponent newTC = new TextComponent(currentstring);
						if (currentColor != null) {
							newTC.setColor(currentColor);
						}
						newTC.setBold(bold);
						newTC.setItalic(italic);
						newTC.setUnderlined(underline);
						newTC.setStrikethrough(strike);
						newTC.setObfuscated(magic);
						base.addExtra(newTC);
						bold = false;
						italic = false;
						underline = false;
						strike = false;
						magic = false;
						currentColor = ChatColor.getByChar(string.charAt(i + 1));
						currentstring = "";
						i++;
						previousLetter = false;
					} else {
						currentColor = ChatColor.getByChar(string.charAt(i + 1));
						i++;
					}
				} else {
					previousLetter = true;
					currentstring = currentstring + c;
				}
			} else {
				previousLetter = true;
				currentstring = currentstring + c;
			}
		}
		TextComponent newTC = new TextComponent(currentstring);
		if (currentColor != null) {
			newTC.setColor(currentColor);
		}
		newTC.setBold(bold);
		newTC.setItalic(italic);
		newTC.setUnderlined(underline);
		newTC.setStrikethrough(strike);
		newTC.setObfuscated(magic);
		base.addExtra(newTC);
		return base;
	}

	public static String translateHexColorCodes(String startTag, String endTag, String message) {
		final Pattern hexPattern = Pattern.compile(startTag + "([A-Fa-f0-9]{6})" + endTag);
		Matcher matcher = hexPattern.matcher(message);
		StringBuffer buffer = new StringBuffer(message.length() + 4 * 8);
		while (matcher.find()) {
			String group = matcher.group(1);
			matcher.appendReplacement(buffer,
					COLOR_CHAR + "x" + COLOR_CHAR + group.charAt(0) + COLOR_CHAR + group.charAt(1) + COLOR_CHAR
							+ group.charAt(2) + COLOR_CHAR + group.charAt(3) + COLOR_CHAR + group.charAt(4) + COLOR_CHAR
							+ group.charAt(5));
		}
		return matcher.appendTail(buffer).toString();
	}
}
