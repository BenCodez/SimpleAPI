package com.bencodez.simpleapi.tests;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.inventory.ItemFactory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.bencodez.simpleapi.array.ArrayUtils;

import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;

public class ArraysUtilsTest {

	@BeforeAll
	public static void setup() {
		Server mockServer = mock(Server.class);
		ItemFactory mockItemFactory = mock(ItemFactory.class);
		Logger mockLogger = mock(Logger.class);

		when(mockServer.getItemFactory()).thenReturn(mockItemFactory);
		when(mockServer.getLogger()).thenReturn(mockLogger);

		Bukkit.setServer(mockServer);
	}

	@Test
	public void colorizeArrayListReturnsColorizedList() {
		ArrayList<String> input = new ArrayList<>(Arrays.asList("&1Hello", "&2World"));
		ArrayList<String> expected = new ArrayList<>(Arrays.asList(ChatColor.DARK_BLUE + "Hello", ChatColor.DARK_GREEN + "World"));
		assertEquals(expected, ArrayUtils.colorize(input));
	}

	@Test
	public void colorizeArrayListReturnsNullForNullInput() {
		assertNull(ArrayUtils.colorize((ArrayList<String>) null));
	}

	@Test
	public void colorizeListReturnsColorizedList() {
		List<String> input = Arrays.asList("&1Hello", "&2World");
		List<String> expected = Arrays.asList(ChatColor.DARK_BLUE + "Hello", ChatColor.DARK_GREEN + "World");
		assertEquals(expected, ArrayUtils.colorize(input));
	}

	@Test
	public void colorizeListReturnsNullForNullInput() {
		assertNull(ArrayUtils.colorize((List<String>) null));
	}

	@Test
	public void colorizeArrayReturnsColorizedArray() {
		String[] input = { "&1Hello", "&2World" };
		String[] expected = { ChatColor.DARK_BLUE + "Hello", ChatColor.DARK_GREEN + "World" };
		assertArrayEquals(expected, ArrayUtils.colorize(input));
	}

	@Test
	public void colorizeArrayReturnsNullForNullInput() {
		assertNull(ArrayUtils.colorize((String[]) null));
	}

	@Test
	public void containsIgnoreCaseArrayListReturnsTrueForMatchingString() {
		ArrayList<String> input = new ArrayList<>(Arrays.asList("Hello", "World"));
		assertTrue(ArrayUtils.containsIgnoreCase(input, "hello"));
	}

	@Test
	public void containsIgnoreCaseArrayListReturnsFalseForNonMatchingString() {
		ArrayList<String> input = new ArrayList<>(Arrays.asList("Hello", "World"));
		assertFalse(ArrayUtils.containsIgnoreCase(input, "hi"));
	}

	@Test
	public void containsIgnoreCaseListReturnsTrueForMatchingString() {
		List<String> input = Arrays.asList("Hello", "World");
		assertTrue(ArrayUtils.containsIgnoreCase(input, "hello"));
	}

	@Test
	public void containsIgnoreCaseListReturnsFalseForNonMatchingString() {
		List<String> input = Arrays.asList("Hello", "World");
		assertFalse(ArrayUtils.containsIgnoreCase(input, "hi"));
	}

	@Test
	public void containsIgnoreCaseSetReturnsTrueForMatchingString() {
		Set<String> input = new HashSet<>(Arrays.asList("Hello", "World"));
		assertTrue(ArrayUtils.containsIgnoreCase(input, "hello"));
	}

	@Test
	public void containsIgnoreCaseSetReturnsFalseForNonMatchingString() {
		Set<String> input = new HashSet<>(Arrays.asList("Hello", "World"));
		assertFalse(ArrayUtils.containsIgnoreCase(input, "hi"));
	}

	@Test
	public void convertArrayListToArrayReturnsCorrectArray() {
		ArrayList<String> input = new ArrayList<>(Arrays.asList("Hello", "World"));
		String[] expected = { "Hello", "World" };
		assertArrayEquals(expected, ArrayUtils.convert(input));
	}

	@Test
	public void convertArrayListToArrayReturnsNullForNullInput() {
		assertNull(ArrayUtils.convert((ArrayList<String>) null));
	}

	@Test
	public void convertSetToArrayListReturnsCorrectList() {
		Set<String> input = new HashSet<>(Arrays.asList("Hello", "World"));
		ArrayList<String> expected = new ArrayList<>(Arrays.asList("Hello", "World"));
		assertEquals(expected, ArrayUtils.convert(input));
	}

	@Test
	public void convertSetToArrayListReturnsEmptyListForEmptySet() {
		Set<String> input = new HashSet<>();
		ArrayList<String> expected = new ArrayList<>();
		assertEquals(expected, ArrayUtils.convert(input));
	}

	@Test
	public void convertArrayToArrayListReturnsCorrectList() {
		String[] input = { "Hello", "World" };
		ArrayList<String> expected = new ArrayList<>(Arrays.asList("Hello", "World"));
		assertEquals(expected, ArrayUtils.convert(input));
	}

	@Test
	public void convertArrayToArrayListReturnsNullForNullInput() {
		assertNull(ArrayUtils.convert((String[]) null));
	}

	@Test
	public void convertBaseComponentArrayListToArrayReturnsCorrectArray() {
		ArrayList<BaseComponent> input = new ArrayList<>(Arrays.asList(new TextComponent("Hello"), new TextComponent("World")));
		BaseComponent[] expected = { new TextComponent("Hello"), new TextComponent("World") };
		assertArrayEquals(expected, ArrayUtils.convertBaseComponent(input));
	}

	@Test
	public void convertBaseComponentArrayListToArrayReturnsNullForNullInput() {
		assertNull(ArrayUtils.convertBaseComponent((ArrayList<BaseComponent>) null));
	}

	@Test
	public void convertBaseComponentArrayToArrayListReturnsCorrectList() {
		BaseComponent[] input = { new TextComponent("Hello"), new TextComponent("World") };
		ArrayList<BaseComponent> expected = new ArrayList<>(Arrays.asList(new TextComponent("Hello"), new TextComponent("World")));
		assertEquals(expected, ArrayUtils.convertBaseComponent(input));
	}

	@Test
	public void convertBaseComponentArrayToArrayListReturnsNullForNullInput() {
		assertNull(ArrayUtils.convertBaseComponent((BaseComponent[]) null));
	}

	@Test
	public void convertItemsCollectionToArrayReturnsCorrectArray() {
		Collection<ItemStack> input = Arrays.asList(new ItemStack(Material.DIAMOND), new ItemStack(Material.GOLD_INGOT));

		ItemStack[] expected = { new ItemStack(Material.DIAMOND), new ItemStack(Material.GOLD_INGOT) };
		ItemStack[] actual = ArrayUtils.convertItems(input);

		assertEquals(expected.length, actual.length);
		for (int i = 0; i < expected.length; i++) {
			assertEquals(expected[i].getType(), actual[i].getType());
		}
	}

	@Test
	public void convertItemsCollectionToArrayReturnsNullForNullInput() {
		assertNull(ArrayUtils.convertItems((Collection<ItemStack>) null));
	}

	@Test
	public void convertItemsListToArrayReturnsCorrectArray() {
		List<ItemStack> input = Arrays.asList(new ItemStack(Material.DIAMOND), new ItemStack(Material.GOLD_INGOT));

		ItemStack[] expected = { new ItemStack(Material.DIAMOND), new ItemStack(Material.GOLD_INGOT) };
		ItemStack[] actual = ArrayUtils.convertItems(input);

		assertEquals(expected.length, actual.length);
		for (int i = 0; i < expected.length; i++) {
			assertEquals(expected[i].getType(), actual[i].getType());
		}
	}

	@Test
	public void convertItemsListToArrayReturnsNullForNullInput() {
		assertNull(ArrayUtils.convertItems((List<ItemStack>) null));
	}
}
