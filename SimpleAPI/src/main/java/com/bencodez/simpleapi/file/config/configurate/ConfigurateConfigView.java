package com.bencodez.simpleapi.file.config.configurate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import org.spongepowered.configurate.ConfigurationNode;

import com.bencodez.simpleapi.file.config.ConfigView;

/**
 * Read-only view of a Configurate section. Scalar numbers/booleans are read
 * strictly, rather than using Configurate's more permissive string coercions.
 * This matches the Bukkit scalar/list rules used by the existing binder.
 *
 * <p>Paths use '.' by default; at(String...) uses literal segments for identifiers
 * containing dots. An externally supplied node is a live view and the caller must
 * coordinate its mutation. YamlConfigDocument supplies detached snapshots.</p>
 *
 * <p>Getter/annotation defaults are supported. Bukkit's mutable default-section
 * overlay and native serialized Bukkit objects are deliberately not emulated.</p>
 */
public class ConfigurateConfigView implements ConfigView {
    protected final ConfigurationNode node;
    private final char separator;

    public ConfigurateConfigView(ConfigurationNode node) { this(node, '.'); }

    public ConfigurateConfigView(ConfigurationNode node, char separator) {
        this.node = Objects.requireNonNull(node, "node");
        this.separator = separator;
    }

    protected final String[] segments(String path) {
        Objects.requireNonNull(path, "path");
        return path.isEmpty() ? new String[0] : path.split(Pattern.quote(String.valueOf(separator)), -1);
    }

    protected final ConfigurationNode resolve(String... keys) {
        Objects.requireNonNull(keys, "keys");
        ConfigurationNode current = node;
        for (String key : keys) {
            Objects.requireNonNull(key, "key");
            ConfigurationNode match = null;
            // YAML numeric keys remain addressable as strings, without replacing
            // them with a second, differently typed key when an editor writes.
            for (Map.Entry<Object, ? extends ConfigurationNode> entry : current.childrenMap().entrySet()) {
                if (String.valueOf(entry.getKey()).equals(key)) {
                    if (match != null) throw new IllegalArgumentException("Ambiguous configuration key");
                    match = entry.getValue();
                }
            }
            current = match == null ? current.node(key) : match;
        }
        return current;
    }

    /** Returns a section using literal key segments, or null for an absent/non-map node. */
    public ConfigurateConfigView at(String... keys) {
        ConfigurationNode child = resolve(keys);
        return child.isMap() || child == node && child.isNull()
                ? new ConfigurateConfigView(child, separator) : null;
    }

    @Override public boolean contains(String path) {
        return path.isEmpty() || !resolve(segments(path)).isNull();
    }
    @Override public String getString(String path, String fallback) {
        ConfigurationNode child = resolve(segments(path));
        if (child.isMap()) return fallback; // no Bukkit section toString emulation
        Object value = child.raw();
        return value == null ? fallback : value.toString();
    }
    @Override public boolean getBoolean(String path, boolean fallback) {
        Object value = resolve(segments(path)).rawScalar();
        return value instanceof Boolean bool ? bool : fallback;
    }
    @Override public int getInt(String path, int fallback) {
        Object value = resolve(segments(path)).rawScalar();
        return value instanceof Number number ? number.intValue() : fallback;
    }
    @Override public long getLong(String path, long fallback) {
        Object value = resolve(segments(path)).rawScalar();
        return value instanceof Number number ? number.longValue() : fallback;
    }
    @Override public double getDouble(String path, double fallback) {
        Object value = resolve(segments(path)).rawScalar();
        return value instanceof Number number ? number.doubleValue() : fallback;
    }
    @Override public List<String> getStringList(String path) {
        List<String> result = new ArrayList<>();
        for (ConfigurationNode child : resolve(segments(path)).childrenList()) {
            Object value = child.rawScalar();
            if (value instanceof String || value instanceof Number || value instanceof Boolean || value instanceof Character)
                result.add(value.toString());
        }
        return result;
    }
    @Override public List<Integer> getIntegerList(String path) {
        List<Integer> result = new ArrayList<>();
        for (ConfigurationNode child : resolve(segments(path)).childrenList()) {
            Object value = child.rawScalar();
            if (value instanceof Number number) result.add(number.intValue());
            else if (value instanceof Character character) result.add((int) character);
            else if (value instanceof String string) {
                try { result.add(Integer.parseInt(string)); } catch (NumberFormatException ignored) { }
            }
        }
        return result;
    }
    @Override public boolean isConfigurationSection(String path) { return at(segments(path)) != null; }
    @Override public ConfigurateConfigView getConfigurationSection(String path) { return at(segments(path)); }
    @Override public Set<String> getKeys(boolean deep) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        collectKeys(node, "", deep, result, 0);
        return result;
    }
    private void collectKeys(ConfigurationNode parent, String prefix, boolean deep, Set<String> keys, int depth) {
        if (depth > 64) throw new IllegalArgumentException("Configuration nesting is too deep");
        Set<String> siblings = new LinkedHashSet<>();
        for (Map.Entry<Object, ? extends ConfigurationNode> entry : parent.childrenMap().entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (!siblings.add(key)) throw new IllegalArgumentException("Ambiguous configuration key");
            String path = prefix + key;
            keys.add(path);
            if (deep && entry.getValue().isMap()) collectKeys(entry.getValue(), path + separator, true, keys, depth + 1);
        }
    }
}
