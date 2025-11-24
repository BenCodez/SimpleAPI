package com.bencodez.simpleapi.file;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

/**
 * Drop-in ConfigurationSection wrapper that resolves paths case-insensitively.
 */
public class CaseInsensitiveSection implements ConfigurationSection {

    private final ConfigurationSection delegate;

    public CaseInsensitiveSection(ConfigurationSection delegate) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate section cannot be null");
        }
        this.delegate = delegate;
    }

    public ConfigurationSection getDelegate() {
        return delegate;
    }

    // ---------------------------------------------------------------------
    // Internal helpers
    // ---------------------------------------------------------------------

    private static String findRealKey(ConfigurationSection section, String key) {
        if (section == null || key == null) {
            return null;
        }
        for (String k : section.getKeys(false)) {
            if (k.equalsIgnoreCase(key)) {
                return k;
            }
        }
        return null;
    }

    /**
     * Resolve a path like "Commands.Console" ignoring case at each segment.
     * Returns the REAL path string using the delegate's original key casing,
     * or null if any segment can't be resolved.
     */
    private String resolvePath(String path) {
        if (path == null || path.isEmpty()) {
            return path;
        }

        String[] parts = path.split("\\.");
        ConfigurationSection current = delegate;
        StringBuilder resolved = new StringBuilder();

        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            String realKey = findRealKey(current, part);
            if (realKey == null) {
                return null;
            }

            if (i > 0) {
                resolved.append('.');
            }
            resolved.append(realKey);

            if (i < parts.length - 1) {
                current = current.getConfigurationSection(realKey);
                if (current == null) {
                    return null;
                }
            }
        }

        return resolved.toString();
    }

    /** Resolve, falling back to original if resolution fails (used for setters). */
    private String resolvePathOrOriginal(String path) {
        String real = resolvePath(path);
        return real != null ? real : path;
    }

    // ---------------------------------------------------------------------
    // ConfigurationSection meta
    // ---------------------------------------------------------------------

    @Override
    public Configuration getRoot() {
        return delegate.getRoot();
    }

    @Override
    public ConfigurationSection getParent() {
        ConfigurationSection parent = delegate.getParent();
        return parent == null ? null : new CaseInsensitiveSection(parent);
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public String getCurrentPath() {
        return delegate.getCurrentPath();
    }

    // ---------------------------------------------------------------------
    // Keys / values
    // ---------------------------------------------------------------------

    @Override
    public Set<String> getKeys(boolean deep) {
        return delegate.getKeys(deep);
    }

    @Override
    public Map<String, Object> getValues(boolean deep) {
        return delegate.getValues(deep);
    }

    @Override
    public boolean contains(String path) {
        String real = resolvePath(path);
        return real != null && delegate.contains(real);
    }

    @Override
    public boolean contains(String path, boolean ignoreDefault) {
        String real = resolvePath(path);
        return real != null && delegate.contains(real, ignoreDefault);
    }

    @Override
    public boolean isSet(String path) {
        String real = resolvePath(path);
        return real != null && delegate.isSet(real);
    }

    // ---------------------------------------------------------------------
    // Raw get/set
    // ---------------------------------------------------------------------

    @Override
    public Object get(String path) {
        String real = resolvePath(path);
        return real == null ? null : delegate.get(real);
    }

    @Override
    public Object get(String path, Object def) {
        String real = resolvePath(path);
        return real == null ? def : delegate.get(real, def);
    }

    @Override
    public void set(String path, Object value) {
        // For set, we allow creating new paths using the original casing
        String real = resolvePathOrOriginal(path);
        delegate.set(real, value);
    }

    @Override
    public ConfigurationSection createSection(String path) {
        // Create using original path (so new keys preserve caller casing),
        // but navigate parents case-insensitively if possible.
        String realParent = null;
        String lastSegment = path;
        int idx = path.lastIndexOf('.');
        if (idx >= 0) {
            String parentPath = path.substring(0, idx);
            lastSegment = path.substring(idx + 1);
            String resolvedParent = resolvePath(parentPath);
            if (resolvedParent != null) {
                realParent = resolvedParent + "." + lastSegment;
            }
        }
        String real = realParent != null ? realParent : path;
        return new CaseInsensitiveSection(delegate.createSection(real));
    }

    @Override
    public ConfigurationSection createSection(String path, Map<?, ?> values) {
        String realParent = null;
        String lastSegment = path;
        int idx = path.lastIndexOf('.');
        if (idx >= 0) {
            String parentPath = path.substring(0, idx);
            lastSegment = path.substring(idx + 1);
            String resolvedParent = resolvePath(parentPath);
            if (resolvedParent != null) {
                realParent = resolvedParent + "." + lastSegment;
            }
        }
        String real = realParent != null ? realParent : path;
        return new CaseInsensitiveSection(delegate.createSection(real, values));
    }

    // ---------------------------------------------------------------------
    // Sections
    // ---------------------------------------------------------------------

    @Override
    public ConfigurationSection getConfigurationSection(String path) {
        if (path == null || path.isEmpty()) {
            return this;
        }
        String real = resolvePath(path);
        if (real == null) {
            return null;
        }
        ConfigurationSection sec = delegate.getConfigurationSection(real);
        return sec == null ? null : new CaseInsensitiveSection(sec);
    }

    @Override
    public boolean isConfigurationSection(String path) {
        String real = resolvePath(path);
        return real != null && delegate.isConfigurationSection(real);
    }

    // ---------------------------------------------------------------------
    // Typed getters
    // ---------------------------------------------------------------------

    @Override
    public String getString(String path) {
        String real = resolvePath(path);
        return real == null ? null : delegate.getString(real);
    }

    @Override
    public String getString(String path, String def) {
        String real = resolvePath(path);
        return real == null ? def : delegate.getString(real, def);
    }

    @Override
    public boolean isString(String path) {
        String real = resolvePath(path);
        return real != null && delegate.isString(real);
    }

    @Override
    public int getInt(String path) {
        String real = resolvePath(path);
        return real == null ? 0 : delegate.getInt(real);
    }

    @Override
    public int getInt(String path, int def) {
        String real = resolvePath(path);
        return real == null ? def : delegate.getInt(real, def);
    }

    @Override
    public boolean isInt(String path) {
        String real = resolvePath(path);
        return real != null && delegate.isInt(real);
    }

    @Override
    public long getLong(String path) {
        String real = resolvePath(path);
        return real == null ? 0L : delegate.getLong(real);
    }

    @Override
    public long getLong(String path, long def) {
        String real = resolvePath(path);
        return real == null ? def : delegate.getLong(real, def);
    }

    @Override
    public boolean isLong(String path) {
        String real = resolvePath(path);
        return real != null && delegate.isLong(real);
    }

    @Override
    public double getDouble(String path) {
        String real = resolvePath(path);
        return real == null ? 0.0D : delegate.getDouble(real);
    }

    @Override
    public double getDouble(String path, double def) {
        String real = resolvePath(path);
        return real == null ? def : delegate.getDouble(real, def);
    }

    @Override
    public boolean isDouble(String path) {
        String real = resolvePath(path);
        return real != null && delegate.isDouble(real);
    }

    @Override
    public boolean getBoolean(String path) {
        String real = resolvePath(path);
        return real != null && delegate.getBoolean(real);
    }

    @Override
    public boolean getBoolean(String path, boolean def) {
        String real = resolvePath(path);
        return real == null ? def : delegate.getBoolean(real, def);
    }

    @Override
    public boolean isBoolean(String path) {
        String real = resolvePath(path);
        return real != null && delegate.isBoolean(real);
    }

    @Override
    public List<?> getList(String path) {
        String real = resolvePath(path);
        return real == null ? null : delegate.getList(real);
    }

    @Override
    public List<?> getList(String path, List<?> def) {
        String real = resolvePath(path);
        return real == null ? def : delegate.getList(real, def);
    }

    @Override
    public boolean isList(String path) {
        String real = resolvePath(path);
        return real != null && delegate.isList(real);
    }

    @Override
    public List<String> getStringList(String path) {
        String real = resolvePath(path);
        return real == null ? Collections.emptyList() : delegate.getStringList(real);
    }

    @Override
    public List<Integer> getIntegerList(String path) {
        String real = resolvePath(path);
        return real == null ? Collections.emptyList() : delegate.getIntegerList(real);
    }

    @Override
    public List<Boolean> getBooleanList(String path) {
        String real = resolvePath(path);
        return real == null ? Collections.emptyList() : delegate.getBooleanList(real);
    }

    @Override
    public List<Double> getDoubleList(String path) {
        String real = resolvePath(path);
        return real == null ? Collections.emptyList() : delegate.getDoubleList(real);
    }

    @Override
    public List<Float> getFloatList(String path) {
        String real = resolvePath(path);
        return real == null ? Collections.emptyList() : delegate.getFloatList(real);
    }

    @Override
    public List<Long> getLongList(String path) {
        String real = resolvePath(path);
        return real == null ? Collections.emptyList() : delegate.getLongList(real);
    }

    @Override
    public List<Byte> getByteList(String path) {
        String real = resolvePath(path);
        return real == null ? Collections.emptyList() : delegate.getByteList(real);
    }

    @Override
    public List<Character> getCharacterList(String path) {
        String real = resolvePath(path);
        return real == null ? Collections.emptyList() : delegate.getCharacterList(real);
    }

    @Override
    public List<Short> getShortList(String path) {
        String real = resolvePath(path);
        return real == null ? Collections.emptyList() : delegate.getShortList(real);
    }

    @Override
    public List<Map<?, ?>> getMapList(String path) {
        String real = resolvePath(path);
        return real == null ? Collections.emptyList() : delegate.getMapList(real);
    }

    // ---------------------------------------------------------------------
    // Bukkit-specific object getters
    // ---------------------------------------------------------------------

    @Override
    public Vector getVector(String path) {
        String real = resolvePath(path);
        return real == null ? null : delegate.getVector(real);
    }

    @Override
    public Vector getVector(String path, Vector def) {
        String real = resolvePath(path);
        return real == null ? def : delegate.getVector(real, def);
    }

    @Override
    public boolean isVector(String path) {
        String real = resolvePath(path);
        return real != null && delegate.isVector(real);
    }

    @Override
    public OfflinePlayer getOfflinePlayer(String path) {
        String real = resolvePath(path);
        return real == null ? null : delegate.getOfflinePlayer(real);
    }

    @Override
    public OfflinePlayer getOfflinePlayer(String path, OfflinePlayer def) {
        String real = resolvePath(path);
        return real == null ? def : delegate.getOfflinePlayer(real, def);
    }

    @Override
    public boolean isOfflinePlayer(String path) {
        String real = resolvePath(path);
        return real != null && delegate.isOfflinePlayer(real);
    }

    @Override
    public ItemStack getItemStack(String path) {
        String real = resolvePath(path);
        return real == null ? null : delegate.getItemStack(real);
    }

    @Override
    public ItemStack getItemStack(String path, ItemStack def) {
        String real = resolvePath(path);
        return real == null ? def : delegate.getItemStack(real, def);
    }

    @Override
    public boolean isItemStack(String path) {
        String real = resolvePath(path);
        return real != null && delegate.isItemStack(real);
    }

    @Override
    public Color getColor(String path) {
        String real = resolvePath(path);
        return real == null ? null : delegate.getColor(real);
    }

    @Override
    public Color getColor(String path, Color def) {
        String real = resolvePath(path);
        return real == null ? def : delegate.getColor(real, def);
    }

    @Override
    public boolean isColor(String path) {
        String real = resolvePath(path);
        return real != null && delegate.isColor(real);
    }

    @Override
    public Location getLocation(String path) {
        String real = resolvePath(path);
        return real == null ? null : delegate.getLocation(real);
    }

    @Override
    public Location getLocation(String path, Location def) {
        String real = resolvePath(path);
        return real == null ? def : delegate.getLocation(real, def);
    }

    @Override
    public boolean isLocation(String path) {
        String real = resolvePath(path);
        return real != null && delegate.isLocation(real);
    }

    @Override
    public <T> T getObject(String path, Class<T> clazz) {
        String real = resolvePath(path);
        return real == null ? null : delegate.getObject(real, clazz);
    }

    @Override
    public <T> T getObject(String path, Class<T> clazz, T def) {
        String real = resolvePath(path);
        return real == null ? def : delegate.getObject(real, clazz, def);
    }

    @Override
    public <T extends ConfigurationSerializable> T getSerializable(String path, Class<T> clazz) {
        String real = resolvePath(path);
        return real == null ? null : delegate.getSerializable(real, clazz);
    }

    @Override
    public <T extends ConfigurationSerializable> T getSerializable(String path, Class<T> clazz, T def) {
        String real = resolvePath(path);
        return real == null ? def : delegate.getSerializable(real, clazz, def);
    }

    // ---------------------------------------------------------------------
    // Serialization
    // ---------------------------------------------------------------------

    public Map<String, Object> serialize() {
        // delegate is also a ConfigurationSection, so it's a ConfigurationSerializable
        return ((ConfigurationSerializable) delegate).serialize();
    }

    @Override
    public ConfigurationSection getDefaultSection() {
        ConfigurationSection def = delegate.getDefaultSection();
        return def == null ? null : new CaseInsensitiveSection(def);
    }

    @Override
    public void addDefault(String path, Object value) {
        // Use resolved-or-original so you can add defaults to new paths too
        String real = resolvePathOrOriginal(path);
        delegate.addDefault(real, value);
    }

    @Override
    public List<String> getComments(String path) {
        String real = resolvePath(path);
        return real == null ? null : delegate.getComments(real);
    }

    @Override
    public List<String> getInlineComments(String path) {
        String real = resolvePath(path);
        return real == null ? null : delegate.getInlineComments(real);
    }

    @Override
    public void setComments(String path, List<String> comments) {
        String real = resolvePathOrOriginal(path);
        delegate.setComments(real, comments);
    }

    @Override
    public void setInlineComments(String path, List<String> comments) {
        String real = resolvePathOrOriginal(path);
        delegate.setInlineComments(real, comments);
    }

}
