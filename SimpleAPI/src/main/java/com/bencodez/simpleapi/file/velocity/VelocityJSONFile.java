package com.bencodez.simpleapi.file.velocity;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.common.reflect.TypeToken;

import lombok.Getter;
import lombok.Setter;
import ninja.leaping.configurate.ConfigurationNode;
import ninja.leaping.configurate.gson.GsonConfigurationLoader;
import ninja.leaping.configurate.objectmapping.ObjectMappingException;

public class VelocityJSONFile {
    private static final Logger LOG = Logger.getLogger(VelocityJSONFile.class.getName());

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);

    @Getter @Setter
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

    /* =====================  Load / Save  ===================== */

    /** Reloads from disk (rebuilds the loader in case the path changed or options are updated). */
    public void reload() {
        lock.writeLock().lock();
        try {
            buildLoader();
            loadInternal(true);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Saves to disk, using a temp file + atomic move where supported. */
    public void save() {
        lock.readLock().lock();
        try {
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            try {
                GsonConfigurationLoader tmpLoader = GsonConfigurationLoader.builder().setPath(tmp).build();
                tmpLoader.save(conf);

                try {
                    Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            }
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Failed to save JSON config: " + path, e);
        } finally {
            lock.readLock().unlock();
        }
    }

    /* =====================  Getters / Helpers  ===================== */

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
            return conf.getNode(path);
        } finally {
            lock.readLock().unlock();
        }
    }

    public boolean contains(Object... path) {
        lock.readLock().lock();
        try {
            return !conf.getNode(path).isVirtual();
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
            for (Map.Entry<Object, ? extends ConfigurationNode> e : node.getChildrenMap().entrySet()) {
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
            return node.getList(TypeToken.of(String.class), def);
        } catch (ObjectMappingException e) {
            LOG.log(Level.WARNING, "Failed to read string list at " + safeNodePath(node) + ", using default.", e);
            return def;
        } finally {
            lock.readLock().unlock();
        }
    }

    /* =====================  Mutators  ===================== */

    public void set(Object[] path, Object value) {
        lock.writeLock().lock();
        try {
            conf.getNode(path).setValue(value);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void remove(Object... path) {
        lock.writeLock().lock();
        try {
            conf.getNode(path).setValue(null);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /* =====================  Internals  ===================== */

    private void buildLoader() {
        this.loader = GsonConfigurationLoader.builder()
                .setPath(this.path)
                .build();
    }

    private void loadInternal(boolean logErrors) {
        lock.writeLock().lock();
        try {
            conf = loader.load();
            if (conf == null) {
                conf = loader.createEmptyNode();
            }
        } catch (IOException e) {
            if (logErrors) {
                LOG.log(Level.SEVERE, "Failed to load JSON config: " + path, e);
            }
            if (conf == null) {
                conf = loader.createEmptyNode();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void ensureFileExists(Path p) {
        try {
            if (p.getParent() != null) {
                Files.createDirectories(p.getParent());
            }
            if (!Files.exists(p)) {
                Files.createFile(p);
                buildLoader();
                conf = loader.createEmptyNode();
                save();
            }
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Failed to ensure config file exists: " + p, e);
        }
    }

    private String safeNodePath(ConfigurationNode node) {
        try {
            return node.getPath().toString();
        } catch (Throwable t) {
            return "<unknown>";
        }
    }
}
