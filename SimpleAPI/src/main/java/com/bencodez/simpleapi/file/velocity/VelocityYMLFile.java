package com.bencodez.simpleapi.file.velocity;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.spongepowered.configurate.BasicConfigurationNode;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import lombok.Getter;
import lombok.Setter;

public class VelocityYMLFile {

	@Getter
	@Setter
	private ConfigurationNode conf;

	@Getter
	private final File file;

	private YamlConfigurationLoader loader;

	public VelocityYMLFile(File file) {
		this.file = file;
		ensureFileExists(file);

		this.loader = buildLoader(file.toPath());
		this.conf = loadOrEmpty();
	}

	private static void ensureFileExists(File file) {
		try {
			File parent = file.getParentFile();
			if (parent != null && !parent.exists()) {
				parent.mkdirs();
			}
			if (!file.exists()) {
				file.createNewFile();
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private static YamlConfigurationLoader buildLoader(Path path) {
		return YamlConfigurationLoader.builder()
				.path(path)
				.nodeStyle(NodeStyle.BLOCK)
				.build();
	}

	private ConfigurationNode loadOrEmpty() {
		try {
			return loader.load();
		} catch (IOException e) {
			e.printStackTrace();
			// Fallback root node using the loader's default options
			return BasicConfigurationNode.root(loader.defaultOptions());
		}
	}

	public boolean getBoolean(ConfigurationNode node, boolean def) {
		return node.getBoolean(def);
	}

	public ConfigurationNode getData() {
		return conf;
	}

	public int getInt(ConfigurationNode node, int def) {
		return node.getInt(def);
	}

	public double getDouble(ConfigurationNode node, double def) {
		return node.getDouble(def);
	}

	public ArrayList<String> getKeys(ConfigurationNode node) {
		ArrayList<String> keys = new ArrayList<>();
		for (Object key : node.childrenMap().keySet()) {
			keys.add(String.valueOf(key));
		}
		return keys;
	}

	public long getLong(ConfigurationNode node, long def) {
		return node.getLong(def);
	}

	public ConfigurationNode getNode(Object... path) {
		return getData().node(path);
	}

	public String getString(ConfigurationNode node, String def) {
		return node.getString(def);
	}

	public List<String> getStringList(ConfigurationNode node, ArrayList<String> def) {
		try {
			return node.getList(String.class, def);
		} catch (SerializationException e) {
			e.printStackTrace();
			return def;
		}
	}

	public void reload() {
		this.loader = buildLoader(file.toPath());
		this.conf = loadOrEmpty();
	}

	public void save() {
		try {
			loader.save(conf);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
}
