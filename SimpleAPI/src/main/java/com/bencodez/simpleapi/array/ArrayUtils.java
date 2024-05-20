package com.bencodez.simpleapi.array;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;

import com.bencodez.simpleapi.messages.MessageAPI;

import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;

public class ArrayUtils {

	/**
	 * Colorize.
	 *
	 * @param list the list
	 * @return the array list
	 */
	public static ArrayList<String> colorize(ArrayList<String> list) {
		if (list == null) {
			return null;
		}

		for (int i = 0; i < list.size(); i++) {
			list.set(i, MessageAPI.colorize(list.get(i)));
		}
		return list;
	}

	/**
	 * Colorize.
	 *
	 * @param list the list
	 * @return the list
	 */
	public static List<String> colorize(List<String> list) {
		if (list == null) {
			return null;
		}

		for (int i = 0; i < list.size(); i++) {
			list.set(i, MessageAPI.colorize(list.get(i)));
		}
		return list;
	}

	/**
	 * Colorize.
	 *
	 * @param list the list
	 * @return the string[]
	 */
	public static String[] colorize(String[] list) {
		if (list == null) {
			return null;
		}

		for (int i = 0; i < list.length; i++) {
			list[i] = MessageAPI.colorize(list[i]);
		}
		return list;
	}

	/**
	 * Compto string.
	 *
	 * @param comps the comps
	 * @return the array list
	 */
	public static ArrayList<String> comptoString(ArrayList<TextComponent> comps) {
		ArrayList<String> txt = new ArrayList<String>();
		for (TextComponent comp : comps) {
			txt.add(MessageAPI.compToString(comp));
		}
		return txt;
	}

	public static boolean containsIgnoreCase(ArrayList<String> set, String str) {
		str = str.toLowerCase();
		for (String text : set) {
			text = text.toLowerCase();
			if (text.equals(str)) {
				return true;
			}
		}
		return false;
	}

	public static boolean containsIgnoreCase(List<String> set, String str) {
		str = str.toLowerCase();
		for (String text : set) {
			text = text.toLowerCase();
			if (text.equals(str)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Sets the contains ignore case.
	 *
	 * @param set the set
	 * @param str the str
	 * @return true, if successful
	 */
	public static boolean containsIgnoreCase(Set<String> set, String str) {
		str = str.toLowerCase();
		for (String text : set) {
			text = text.toLowerCase();
			if (text.equals(str)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Convert array.
	 *
	 * @param list the list
	 * @return the string[]
	 */
	public static String[] convert(ArrayList<String> list) {
		if (list == null) {
			return null;
		}
		String[] string = new String[list.size()];
		for (int i = 0; i < list.size(); i++) {
			string[i] = list.get(i);
		}
		return string;

	}

	public static ItemStack[] convertItems(List<Item> list) {
		if (list == null) {
			return null;
		}
		ItemStack[] string = new ItemStack[list.size()];
		for (int i = 0; i < list.size(); i++) {
			string[i] = list.get(i).getItemStack();
		}
		return string;

	}

	public static ItemStack[] convertItems(Collection<ItemStack> list) {
		if (list == null) {
			return null;
		}
		ItemStack[] string = new ItemStack[list.size()];
		int i = 0;
		for (ItemStack item : list) {
			string[i] = item;
			i++;
		}
		return string;

	}

	/**
	 * Convert.
	 *
	 * @param set1 the set
	 * @return the array list
	 */
	public static ArrayList<String> convert(Set<String> set1) {
		Set<String> set = new HashSet<String>(set1);
		ArrayList<String> list = new ArrayList<String>();
		for (String st : set) {
			list.add(st);
		}
		return list;
	}

	/**
	 * Convert array.
	 *
	 * @param list the list
	 * @return the array list
	 */
	@SuppressWarnings("unused")
	public static ArrayList<String> convert(String[] list) {
		if (list == null) {
			return null;
		}
		ArrayList<String> newlist = new ArrayList<String>();
		for (String element : list) {
			newlist.add(element);
		}
		if (newlist == null) {
			return null;
		} else {
			return newlist;
		}
	}

	public static BaseComponent[] convertBaseComponent(ArrayList<BaseComponent> list) {
		if (list == null) {
			return null;
		}
		BaseComponent[] string = new BaseComponent[list.size()];
		for (int i = 0; i < list.size(); i++) {
			string[i] = list.get(i);
		}
		return string;
	}

	public static ArrayList<BaseComponent> convertBaseComponent(BaseComponent[] list) {
		if (list == null) {
			return null;
		}
		ArrayList<BaseComponent> newlist = new ArrayList<BaseComponent>();
		for (BaseComponent element : list) {
			newlist.add(element);
		}
		return newlist;
	}

	/**
	 * Sets the to array.
	 *
	 * @param set the set
	 * @return the string[]
	 */
	@SuppressWarnings("unused")
	public static String[] convertSet(Set<String> set) {
		String[] array = new String[set.size()];
		int i = 0;
		for (String item : set) {
			array[i] = item;
			i++;
		}
		if (array == null) {
			return null;
		} else {
			return array;
		}
	}

	/**
	 * Make string.
	 *
	 * @param startIndex the start index
	 * @param strs       the strs
	 * @return the string
	 */
	public static String makeString(int startIndex, String[] strs) {
		String str = new String();
		for (int i = startIndex; i < strs.length; i++) {
			if (i == startIndex) {
				str += strs[i];
			} else {
				str += " " + strs[i];
			}

		}
		return str;
	}

	/**
	 * Make string list.
	 *
	 * @param list the list
	 * @return the string
	 */
	public static String makeStringList(ArrayList<String> list) {
		if (list == null) {
			return "";
		}
		String string = new String();
		if (list.size() > 1) {
			for (int i = 0; i < list.size(); i++) {
				if (i == 0) {
					string += list.get(i);
				} else {
					string += ", " + list.get(i);
				}
			}
		} else if (list.size() == 1) {
			string = list.get(0);
		}
		return string;
	}

	public static String pickRandom(ArrayList<String> list) {
		if (list != null) {
			return list.get(ThreadLocalRandom.current().nextInt(list.size()));
		}
		return "";
	}

	/**
	 * Removes the duplicates.
	 *
	 * @param list the list
	 * @return the array list
	 */
	public static ArrayList<String> removeDuplicates(ArrayList<String> list) {
		Set<String> set = new HashSet<String>();
		set.addAll(list);
		list.clear();
		list.addAll(set);
		return list;
	}

	/**
	 * Replace.
	 *
	 * @param list        the list
	 * @param toReplace   the to replace
	 * @param replaceWith the replace with
	 * @return the list
	 */
	public static List<String> replace(List<String> list, String toReplace, String replaceWith) {
		if (list == null) {
			return null;
		}
		if (replaceWith == null || toReplace == null) {
			return list;
		}
		for (int i = 0; i < list.size(); i++) {
			list.set(i, list.get(i).replace(toReplace, replaceWith));
		}
		return list;
	}

	/**
	 * Replace ignore case.
	 *
	 * @param list        the list
	 * @param toReplace   the to replace
	 * @param replaceWith the replace with
	 * @return the array list
	 */
	public static ArrayList<String> replaceIgnoreCase(ArrayList<String> list, String toReplace, String replaceWith) {
		ArrayList<String> newList = new ArrayList<String>();
		for (String msg : list) {
			newList.add(MessageAPI.replaceIgnoreCase(msg, toReplace, replaceWith));
		}
		return newList;
	}

	public static ArrayList<String> sort(ArrayList<String> list) {
		Collections.sort(list, String.CASE_INSENSITIVE_ORDER);
		return list;
	}

	public static HashMap<String, Integer> sortByValuesStr(HashMap<String, Integer> unsortMap, final boolean order) {

		List<Entry<String, Integer>> list = new LinkedList<Entry<String, Integer>>(unsortMap.entrySet());

		// Sorting the list based on values
		Collections.sort(list, new Comparator<Entry<String, Integer>>() {
			@Override
			public int compare(Entry<String, Integer> o1, Entry<String, Integer> o2) {
				if (order) {
					return o1.getValue().compareTo(o2.getValue());
				} else {
					return o2.getValue().compareTo(o1.getValue());

				}
			}
		});

		// Maintaining insertion order with the help of LinkedList
		HashMap<String, Integer> sortedMap = new LinkedHashMap<String, Integer>();
		for (Entry<String, Integer> entry : list) {
			sortedMap.put(entry.getKey(), entry.getValue());
		}

		return sortedMap;
	}

	public static LinkedHashMap<String, ItemStack> sortByValuesStrItem(HashMap<String, ItemStack> unsortMap) {

		ArrayList<String> sortedKeys = sort(new ArrayList<String>(unsortMap.keySet()));

		// Maintaining insertion order with the help of LinkedList
		LinkedHashMap<String, ItemStack> sortedMap = new LinkedHashMap<String, ItemStack>();
		for (String key : sortedKeys) {
			sortedMap.put(key, unsortMap.get(key));
		}

		return sortedMap;
	}
}
