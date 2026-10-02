package com.bencodez.simpleapi.file.velocity;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
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
	private volatile ConfigurationNode conf;

	@Getter
	private final File file;

	private YamlConfigurationLoader loader;

	public VelocityYMLFile(File file) {
		this.file = file;
		ensureFileExists(file);

		this.loader = buildLoader(file.toPath());
		this.conf = loadInitialOrEmpty();
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

	private ConfigurationNode loadInitialOrEmpty() {
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

	/**
	 * Publishes a replacement only after a successful load. Startup may use
	 * defaults, but a reload failure must keep the last valid active snapshot.
	 * @throws UncheckedIOException when the replacement cannot be read or parsed
	 */
	public synchronized void reload() {
		YamlConfigurationLoader replacement = buildLoader(file.toPath());
		try {
			ConfigurationNode loaded;
			// Open before invoking Configurate: its path loader treats a missing file as empty.
			try (BufferedReader reader = Files.newBufferedReader(file.toPath())) {
				loaded = YamlConfigurationLoader.builder().source(() -> reader)
						.nodeStyle(NodeStyle.BLOCK).build().load();
			}
			this.loader = replacement;
			this.conf = loaded;
		} catch (IOException failure) {
			throw new UncheckedIOException("Velocity YAML reload failed; previous configuration remains active", failure);
		}
	}

	public void save() {
		try {
			loader.save(conf);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
}
