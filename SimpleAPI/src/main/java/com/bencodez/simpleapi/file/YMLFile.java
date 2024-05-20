package com.bencodez.simpleapi.file;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Map.Entry;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import com.bencodez.simpleapi.scheduler.BukkitScheduler;

import lombok.Getter;
import net.md_5.bungee.api.ChatColor;

/**
 * The Class YMLFile.
 */
public abstract class YMLFile {

	private boolean created = false;

	/** The data. */
	private FileConfiguration data;

	/** The d file. */
	private File dFile;

	@Getter
	private boolean failedToRead = false;

	@Getter
	private JavaPlugin plugin;

	private BukkitScheduler scheduler;

	public YMLFile(JavaPlugin plugin, File file) {
		dFile = file;
		this.plugin = plugin;
		scheduler = new BukkitScheduler(plugin);
	}

	public YMLFile(JavaPlugin plugin, File file, boolean setup) {
		dFile = file;
		this.plugin = plugin;
		scheduler = new BukkitScheduler(plugin);
		if (setup) {
			setup();
		}
	}

	public void createSection(String key) {
		getData().createSection(key);
		saveData();
	}

	/**
	 * Gets the data.
	 *
	 * @return the data
	 */
	public FileConfiguration getData() {
		return data;
	}

	/**
	 * Gets the d file.
	 *
	 * @return the d file
	 */
	public File getdFile() {
		return dFile;
	}

	public boolean isJustCreated() {
		return created;
	}

	public void loadValues() {

	}

	/**
	 * On file creation.
	 */
	public abstract void onFileCreation();

	/**
	 * Reload data.
	 */
	public void reloadData() {
		try {
			data = YamlConfiguration.loadConfiguration(dFile);
			failedToRead = false;
			if (data.getConfigurationSection("").getKeys(false).size() == 0) {
				failedToRead = true;
			} else {
				loadValues();
			}
		} catch (Exception e) {
			failedToRead = true;
			e.printStackTrace();
			plugin.getLogger().severe("Failed to load " + dFile.getName());
			scheduler.runTaskAsynchronously(plugin, new Runnable() {

				@Override
				public void run() {
					plugin.getLogger().severe("Detected failure to load files on startup, see server log for details");
				}
			});
		}
	}

	/**
	 * Save data.
	 */
	public void saveData() {
		try {
			data.save(dFile);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public void setData(FileConfiguration data) {
		Map<String, Object> map = data.getConfigurationSection("").getValues(true);
		for (Entry<String, Object> entry : map.entrySet()) {
			this.data.set(entry.getKey(), entry.getValue());
		}
	}

	/**
	 * Setup.
	 */
	public void setup() {
		failedToRead = false;
		getdFile().getParentFile().mkdirs();

		if (!dFile.exists()) {
			try {
				getdFile().createNewFile();
				onFileCreation();
				created = true;
			} catch (IOException e) {
				Bukkit.getServer().getLogger().severe(ChatColor.RED + "Could not create " + getdFile().getName() + "!");
			}
		}

		try {
			data = YamlConfiguration.loadConfiguration(dFile);
			if (data.getConfigurationSection("").getKeys(false).size() == 0) {
				failedToRead = true;
			}
			loadValues();

		} catch (Exception e) {
			failedToRead = true;
			e.printStackTrace();
			plugin.getLogger().severe("Failed to load " + dFile.getName());
			scheduler.runTaskAsynchronously(plugin, new Runnable() {

				@Override
				public void run() {
					plugin.getLogger().severe("Detected failure to load files on startup, see server log for details");
				}
			});
		}
	}

	public void setValue(String path, Object value) {
		getData().set(path, value);
		saveData();
	}
}
