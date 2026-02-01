package com.bencodez.simpleapi.file.velocity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.spongepowered.configurate.BasicConfigurationNode;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.gson.GsonConfigurationLoader;
import org.spongepowered.configurate.serialize.SerializationException;

import lombok.Getter;
import lombok.Setter;

public class VelocityJSONFile {
	private static final Logger LOG = Logger.getLogger(VelocityJSONFile.class.getName());

	private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);

	@Getter
	@Setter
	private ConfigurationNode conf;

	@Getter
	private final Path path;

	private GsonConfigurationLoader loader;

	public VelocityJSONFile(Path path) {
		this.path = path;
		ensureFileExists(path);
		buildLoader();
		loadInternal(true);
	}

	public VelocityJSONFile(java.io.File file) {
		this(file.toPath());
	}

	/* ===================== Load / Save ===================== */

	/**
	 * Reloads from disk (rebuilds the loader in case the path changed or options
	 * are updated).
	 */
	public void reload() {
		lock.writeLock().lock();
		try {
			buildLoader();
			loadInternal(true);
		} finally {
			lock.writeLock().unlock();
		}
	}

	public void save() {
		lock.writeLock().lock();
		try {
			Path dir = path.getParent();
			if (dir != null) {
				Files.createDirectories(dir);
			}
			loader.save(conf);
		} catch (IOException e) {
			LOG.log(Level.SEVERE, "Failed to save JSON config: " + path, e);
		} finally {
			lock.writeLock().unlock();
		}
	}

	/* ===================== Getters / Helpers ===================== */

	public ConfigurationNode getData() {
		lock.readLock().lock();
		try {
			return conf;
		} finally {
			lock.readLock().unlock();
		}
	}

	public ConfigurationNode getNode(Object... path) {
		lock.readLock().lock();
		try {
			return conf.node(path);
		} finally {
			lock.readLock().unlock();
		}
	}

	public boolean contains(Object... path) {
		lock.readLock().lock();
		try {
			return !conf.node(path).virtual();
		} finally {
			lock.readLock().unlock();
		}
	}

	public boolean getBoolean(ConfigurationNode node, boolean def) {
		lock.readLock().lock();
		try {
			return node.getBoolean(def);
		} finally {
			lock.readLock().unlock();
		}
	}

	public int getInt(ConfigurationNode node, int def) {
		lock.readLock().lock();
		try {
			return node.getInt(def);
		} finally {
			lock.readLock().unlock();
		}
	}

	public long getLong(ConfigurationNode node, long def) {
		lock.readLock().lock();
		try {
			return node.getLong(def);
		} finally {
			lock.readLock().unlock();
		}
	}

	public String getString(ConfigurationNode node, String def) {
		lock.readLock().lock();
		try {
			return node.getString(def);
		} finally {
			lock.readLock().unlock();
		}
	}

	public ArrayList<String> getKeys(ConfigurationNode node) {
		lock.readLock().lock();
		try {
			ArrayList<String> keys = new ArrayList<>();
			for (Map.Entry<Object, ? extends ConfigurationNode> e : node.childrenMap().entrySet()) {
				keys.add(String.valueOf(e.getKey()));
			}
			return keys;
		} finally {
			lock.readLock().unlock();
		}
	}

	public List<String> getStringList(ConfigurationNode node, List<String> def) {
		lock.readLock().lock();
		try {
			try {
				return node.getList(String.class, def);
			} catch (SerializationException e) {
				LOG.log(Level.WARNING, "Failed to read string list at " + safeNodePath(node) + ", using default.", e);
				return def;
			}
		} finally {
			lock.readLock().unlock();
		}
	}

	/* ===================== Mutators ===================== */

	public void set(Object[] path, Object value) {
		lock.writeLock().lock();
		try {
			try {
				conf.node(path).set(value);
			} catch (SerializationException e) {
				LOG.log(Level.SEVERE, "Failed to set value at path " + safePathString(path), e);
			}
		} finally {
			lock.writeLock().unlock();
		}
	}

	public void remove(Object... path) {
		lock.writeLock().lock();
		try {
			try {
				conf.node(path).set(null);
			} catch (SerializationException e) {
				LOG.log(Level.SEVERE, "Failed to remove value at path " + safePathString(path), e);
			}
		} finally {
			lock.writeLock().unlock();
		}
	}

	/* ===================== Internals ===================== */

	private void buildLoader() {
		this.loader = GsonConfigurationLoader.builder().path(this.path).build();
	}

	private void loadInternal(boolean logErrors) {
		lock.writeLock().lock();
		try {
			conf = loader.load();
			if (conf == null) {
				conf = BasicConfigurationNode.root(loader.defaultOptions());
			}
		} catch (IOException e) {
			if (logErrors) {
				LOG.log(Level.SEVERE, "Failed to load JSON config: " + path, e);
			}
			if (conf == null) {
				conf = BasicConfigurationNode.root(loader.defaultOptions());
			}
		} finally {
			lock.writeLock().unlock();
		}
	}

	private void ensureFileExists(Path p) {
		try {
			Path parent = p.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			if (!Files.exists(p)) {
				Files.createFile(p);
				buildLoader();
				conf = BasicConfigurationNode.root(loader.defaultOptions());
				save();
			}
		} catch (IOException e) {
			LOG.log(Level.SEVERE, "Failed to ensure config file exists: " + p, e);
		}
	}

	private String safeNodePath(ConfigurationNode node) {
		try {
			return String.valueOf(node.path());
		} catch (Throwable t) {
			return "<unknown>";
		}
	}

	private String safePathString(Object[] path) {
		if (path == null) {
			return "<null>";
		}
		StringBuilder sb = new StringBuilder();
		sb.append('[');
		for (int i = 0; i < path.length; i++) {
			if (i > 0) {
				sb.append(", ");
			}
			sb.append(String.valueOf(path[i]));
		}
		sb.append(']');
		return sb.toString();
	}
}
