package com.bencodez.simpleapi.file.velocity;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.yaml.snakeyaml.DumperOptions;

import com.google.common.reflect.TypeToken;

import lombok.Getter;
import lombok.Setter;
import ninja.leaping.configurate.ConfigurationNode;
import ninja.leaping.configurate.objectmapping.ObjectMappingException;
import ninja.leaping.configurate.yaml.YAMLConfigurationLoader;

public class VelocityYMLFile {
	@Getter
	@Setter
	private ConfigurationNode conf;
	@Getter
	private File file;
	private YAMLConfigurationLoader loader;

	public VelocityYMLFile(File file) {

		this.file = file;
		if (!file.exists()) {
			try {
				file.createNewFile();
				if (file.getParentFile() != null && !file.getParentFile().exists()) {
					file.getParentFile().mkdirs();
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		loader = YAMLConfigurationLoader.builder().setFlowStyle(DumperOptions.FlowStyle.BLOCK).setPath(file.toPath())
				.build();

		try {
			conf = loader.load();
		} catch (IOException e) {
			e.printStackTrace();
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
		for (ConfigurationNode key : node.getChildrenMap().values()) {
			keys.add(key.getKey().toString());
		}
		return keys;
	}

	public long getLong(ConfigurationNode node, long def) {
		return node.getLong(def);
	}

	public ConfigurationNode getNode(Object... path) {
		return getData().getNode(path);
	}

	public String getString(ConfigurationNode node, String def) {
		return node.getString(def);
	}

	public List<String> getStringList(ConfigurationNode node, ArrayList<String> def) {
		try {
			return node.getList(TypeToken.of(String.class), def);
		} catch (ObjectMappingException e) {
			e.printStackTrace();
			return def;
		}
	}

	public void reload() {
		loader = YAMLConfigurationLoader.builder().setFlowStyle(DumperOptions.FlowStyle.BLOCK).setPath(file.toPath())
				.build();

		try {
			conf = loader.load();
		} catch (IOException e) {
			e.printStackTrace();
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
