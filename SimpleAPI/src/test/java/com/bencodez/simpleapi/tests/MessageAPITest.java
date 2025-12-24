package com.bencodez.simpleapi.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.bencodez.simpleapi.messages.MessageAPI;

import net.md_5.bungee.api.chat.TextComponent;

public class MessageAPITest {

	private Player player;
	private ArrayList<TextComponent> messages;

	@BeforeEach
	public void setUp() {
		player = Mockito.mock(Player.class);
		messages = new ArrayList<>();
	}

	@Test
	public void colorize_NullInput() {
		assertEquals(null, MessageAPI.colorize(null));
	}

	/**
	 * Encoding-safe test: don't compare strings containing '§' at all.
	 * We strip colors and compare the plain text output.
	 */
	@Test
	public void colorize_ValidInput() {
		String out = MessageAPI.colorize("{AQUA}Hello {RED}World");
		assertEquals("Hello World", ChatColor.stripColor(out));
	}

	@Test
	public void compToString_ValidComponent() {
		TextComponent comp = new TextComponent("Hello World");
		assertEquals("Hello World", MessageAPI.compToString(comp));
	}

	@Test
	public void contains_True() {
		assertTrue(MessageAPI.contains("Hello World", "World"));
	}

	@Test
	public void contains_False() {
		assertFalse(MessageAPI.contains("Hello World", "world"));
	}

	@Test
	public void containsIgnorecase_True() {
		assertTrue(MessageAPI.containsIgnorecase("Hello World", "world"));
	}

	@Test
	public void containsIgnorecase_False() {
		assertFalse(MessageAPI.containsIgnorecase("Hello World", "planet"));
	}

	@Test
	public void containsJson_True() {
		assertTrue(MessageAPI.containsJson("[Text=\"Hello\"]"));
	}

	@Test
	public void containsJson_False() {
		assertFalse(MessageAPI.containsJson("Hello World"));
	}

	@Test
	public void isDouble_True() {
		assertTrue(MessageAPI.isDouble("123.45"));
	}

	@Test
	public void isDouble_False() {
		assertFalse(MessageAPI.isDouble("abc"));
	}

	@Test
	public void isInt_True() {
		assertTrue(MessageAPI.isInt("123"));
	}

	@Test
	public void isInt_False() {
		assertFalse(MessageAPI.isInt("123.45"));
	}

	@Test
	public void roundDecimals_ValidInput() {
		assertEquals("123.46", MessageAPI.roundDecimals(123.456, 2));
	}

	@Test
	public void sendJson_ValidPlayerAndMessages() {
		messages.add(new TextComponent("Hello"));
		messages.add(new TextComponent("World"));
		MessageAPI.sendJson(player, messages);
		// Add assertions to verify the behavior
	}

	@Test
	public void sendJson_ValidPlayerAndMessage() {
		TextComponent message = new TextComponent("Hello World");
		MessageAPI.sendJson(player, message);
		// Add assertions to verify the behavior
	}

	@Test
	public void startsWithIgnoreCase_True() {
		assertTrue(MessageAPI.startsWithIgnoreCase("Hello World", "hello"));
	}

	@Test
	public void startsWithIgnoreCase_False() {
		assertFalse(MessageAPI.startsWithIgnoreCase("Hello World", "world"));
	}

	@Test
	public void stringToComp_ValidString() {
		TextComponent comp = MessageAPI.stringToComp("&bHello &cWorld");
		assertNotNull(comp);
		// Add assertions to verify the component properties
	}

	@Test
	public void translateHexColorCodes_ValidInput() {
		String out = MessageAPI.translateHexColorCodes("&#", "#", "&#FF0000#Hello");

		// Encoding-safe: verify the visible output text rather than color prefix bytes
		assertEquals("Hello", ChatColor.stripColor(out));
	}
}
