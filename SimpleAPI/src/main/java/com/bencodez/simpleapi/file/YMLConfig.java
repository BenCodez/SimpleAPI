package com.bencodez.simpleapi.file;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import lombok.Getter;

/**
 * The Class YMLFile.
 */
public abstract class YMLConfig {

	@Getter
	private ConfigurationSection data;

	@Getter
	private boolean failedToRead = false;

	@Getter
	private JavaPlugin plugin;

	public YMLConfig(JavaPlugin plugin, ConfigurationSection data) {
		this.data = data;
		this.plugin = plugin;
	}

	public abstract void createSection(String key);

	public abstract void saveData();

	public abstract void setValue(String path, Object value);

}
