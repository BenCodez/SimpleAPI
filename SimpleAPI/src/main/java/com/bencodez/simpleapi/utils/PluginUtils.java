package com.bencodez.simpleapi.utils;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.TabCompleter;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

public class PluginUtils {

	public static long getFreeMemory() {
		return Runtime.getRuntime().freeMemory() / (1024 * 1024);
	}

	public static long getMemory() {
		return Runtime.getRuntime().totalMemory() / (1024 * 1024);
	}

	public static void registerCommands(JavaPlugin plugin, String commandText, CommandExecutor executor,
			TabCompleter tab) {
		plugin.getCommand(commandText).setExecutor(executor);
		if (tab != null) {
			plugin.getCommand(commandText).setTabCompleter(tab);
		}
	}

	public static void registerEvents(Listener listener, JavaPlugin plugin) {
		Bukkit.getPluginManager().registerEvents(listener, plugin);
	}

	private PluginUtils() {
	}

}
