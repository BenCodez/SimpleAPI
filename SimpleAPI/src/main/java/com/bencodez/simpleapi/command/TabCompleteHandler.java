package com.bencodez.simpleapi.command;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.bukkit.command.CommandSender;

import com.bencodez.simpleapi.array.ArrayUtils;

public class TabCompleteHandler {
	static TabCompleteHandler instance = new TabCompleteHandler();

	public static TabCompleteHandler getInstance() {
		return instance;
	}

	private ConcurrentHashMap<String, ArrayList<String>> tabCompleteOptions = new ConcurrentHashMap<>();

	private ArrayList<String> tabCompleteReplaces = new ArrayList<>();

	private ConcurrentLinkedQueue<TabCompleteHandle> tabCompletes = new ConcurrentLinkedQueue<>();

	public void addTabCompleteOption(String toReplace, ArrayList<String> options) {
		addTabCompleteOption(new TabCompleteHandle(toReplace, options) {

			@Override
			public void reload() {
			}

			@Override
			public void updateReplacements() {
			}
		});
	}

	public void addTabCompleteOption(String toReplace, String... options) {
		addTabCompleteOption(toReplace, ArrayUtils.convert(options));
	}

	public void addTabCompleteOption(TabCompleteHandle handle) {
		for (TabCompleteHandle h : tabCompletes) {
			if (h.getToReplace().equals(handle.getToReplace())) {
				// plugin.debug("Tabcompletehandle not added, one already exists for " +
				// handle.getToReplace());
				return;
			}
		}
		handle.reload();
		tabCompletes.add(handle);
		loadTabCompleteOptions();

		ArrayList<String> list = new ArrayList<>();
		for (TabCompleteHandle h : tabCompletes) {
			list.add(h.getToReplace());
			h.updateReplacements();
		}
		tabCompleteReplaces.clear();
		tabCompleteReplaces.addAll(list);
	}

	public ConcurrentHashMap<String, ArrayList<String>> getTabCompleteOptions() {
		return tabCompleteOptions;
	}

	public ArrayList<String> getTabCompleteOptions(ArrayList<CommandHandler> handles, CommandSender sender,
			String[] args, int argNum) {
		ArrayList<String> tabComplete = new ArrayList<>();
		ConcurrentHashMap<String, ArrayList<String>> options = getTabCompleteOptions();
		for (CommandHandler h : handles) {
			tabComplete.addAll(h.getTabCompleteOptions(sender, args, argNum, options));
		}
		return tabComplete;
	}

	public ArrayList<String> getTabCompleteReplaces() {
		return tabCompleteReplaces;
	}

	public void loadTabCompleteOptions() {
		for (TabCompleteHandle h : tabCompletes) {
			h.updateReplacements();
		}
		tabCompleteOptions.clear();
		for (TabCompleteHandle h : tabCompletes) {
			tabCompleteOptions.put(h.getToReplace(), h.getReplace());
		}
	}

	public void loadTimer(ScheduledExecutorService timer) {
		timer.scheduleWithFixedDelay(new Runnable() {

			@Override
			public void run() {
				loadTabCompleteOptions();

			}
		}, 5, 30, TimeUnit.MINUTES);
	}

	public void onLogin() {
		for (TabCompleteHandle h : tabCompletes) {
			if (h.isUpdateOnLoginLogout()) {
				h.updateReplacements();
			}
		}
		tabCompleteOptions.clear();
		for (TabCompleteHandle h : tabCompletes) {
			tabCompleteOptions.put(h.getToReplace(), h.getReplace());
		}
	}

	public void reload() {
		for (TabCompleteHandle h : tabCompletes) {
			h.reload();
		}
	}
}
