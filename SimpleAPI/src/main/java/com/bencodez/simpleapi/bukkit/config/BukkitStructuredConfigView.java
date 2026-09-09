package com.bencodez.simpleapi.bukkit.config;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import org.bukkit.configuration.ConfigurationSection;

import com.bencodez.simpleapi.core.config.PlainConfigValues;
import com.bencodez.simpleapi.core.config.StructuredConfigView;

/**
 * Live structured Bukkit reads. Ordinary getters still delegate to Bukkit.
 * Literal reads never change the root path separator or mutate the section.
 */
public final class BukkitStructuredConfigView extends BukkitConfigView implements StructuredConfigView {
    private static final class RawMapView implements StructuredConfigView {
        private static final int MAX_DEPTH = 64;
        private static final int MAX_KEYS = 100_000;
        private final Map<?, ?> source;
        private final char separator;

        private RawMapView(Map<?, ?> values, char separator) {
            this.source = values;
            index(values);
            this.separator = separator;
        }

        private static Map<String, Object> index(Map<?, ?> source) {
            Map<String, Object> indexed = new java.util.LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : source.entrySet()) {
                if (indexed.size() >= MAX_KEYS) {
                    throw new IllegalArgumentException("Configuration map contains too many keys");
                }
                Object key = entry.getKey();
                boolean scalarKey = key instanceof String || key instanceof Boolean || key instanceof Character
                        || PlainConfigValues.kind(key) == Kind.NUMBER;
                if (!scalarKey) throw new IllegalArgumentException("Configuration map keys must be plain scalars");
                String name = String.valueOf(key);
                if (indexed.containsKey(name)) {
                    throw new IllegalArgumentException("Configuration map keys must be unambiguous strings");
                }
                indexed.put(name, entry.getValue());
            }
            return indexed;
        }

        private String[] split(String path) {
            Objects.requireNonNull(path, "path");
            return path.isEmpty() ? new String[0] : path.split(Pattern.quote(String.valueOf(separator)));
        }

        private Object literal(String... keys) {
            Objects.requireNonNull(keys, "keys");
            Object current = source;
            for (String key : keys) {
                Objects.requireNonNull(key, "key");
                if (current instanceof Map<?, ?> raw) {
                    Map<String, Object> map = index(raw);
                    if (!map.containsKey(key)) return null;
                    current = map.get(key);
                } else if (current instanceof ConfigurationSection section) {
                    current = sectionLiteral(section, key);
                } else {
                    return null;
                }
            }
            return current;
        }

        private static boolean scalar(Object value) {
            return value instanceof String || value instanceof Number || value instanceof Boolean
                    || value instanceof Character;
        }

        @Override public Kind kind(String path) { return kindAt(split(path)); }
        @Override public Kind kindAt(String... keys) {
            if (keys.length == 0) return Kind.MAP;
            Object value = literal(keys);
            if (value instanceof ConfigurationSection) return Kind.SECTION;
            return value == null ? Kind.MISSING : PlainConfigValues.kind(value);
        }
        @Override public Object value(String path) { return valueAt(split(path)); }
        @Override public Object valueAt(String... keys) {
            Object value = literal(keys);
            return value == null ? null : detached(value);
        }
        @Override public StructuredConfigView at(String... keys) {
            Object value = literal(keys);
            return value instanceof ConfigurationSection section ? new BukkitStructuredConfigView(section) : null;
        }
        @Override public StructuredConfigView structuredAt(String... keys) {
            Object value = literal(keys);
            if (value instanceof ConfigurationSection section) return new BukkitStructuredConfigView(section);
            return value instanceof Map<?, ?> map ? new RawMapView(map, separator) : null;
        }
        @Override public StructuredConfigView getConfigurationSection(String path) { return at(split(path)); }
        @Override public boolean isConfigurationSection(String path) {
            return literal(split(path)) instanceof ConfigurationSection;
        }
        @Override public boolean contains(String path) {
            String[] keys = split(path);
            return keys.length == 0 || literal(keys) != null;
        }
        @Override public String getString(String path, String fallback) {
            Object value = literal(split(path));
            return scalar(value) ? value.toString() : fallback;
        }
        @Override public boolean getBoolean(String path, boolean fallback) {
            Object value = literal(split(path));
            return value instanceof Boolean bool ? bool : fallback;
        }
        @Override public int getInt(String path, int fallback) {
            Object value = literal(split(path));
            return value instanceof Number number ? number.intValue() : fallback;
        }
        @Override public long getLong(String path, long fallback) {
            Object value = literal(split(path));
            return value instanceof Number number ? number.longValue() : fallback;
        }
        @Override public double getDouble(String path, double fallback) {
            Object value = literal(split(path));
            return value instanceof Number number ? number.doubleValue() : fallback;
        }
        @Override public List<String> getStringList(String path) {
            Object value = literal(split(path));
            if (!(value instanceof List<?> list)) return List.of();
            List<String> result = new ArrayList<>();
            for (Object item : list) if (scalar(item)) result.add(item.toString());
            return result;
        }
        @Override public List<Integer> getIntegerList(String path) {
            Object value = literal(split(path));
            if (!(value instanceof List<?> list)) return List.of();
            List<Integer> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Number number) result.add(number.intValue());
                else if (item instanceof Character character) result.add((int) character);
                else if (item instanceof String string) {
                    try { result.add(Integer.parseInt(string)); } catch (NumberFormatException ignored) { }
                }
            }
            return result;
        }
        @Override public Set<String> getKeys(boolean deep) {
            Set<String> result = new LinkedHashSet<>();
            collectKeys(source, "", deep, result, 0, new int[1], new IdentityHashMap<>());
            return result;
        }
        private void collectKeys(Object container, String prefix, boolean deep, Set<String> result,
                int depth, int[] count, IdentityHashMap<Object, Boolean> ancestors) {
            if (depth > MAX_DEPTH || ancestors.put(container, Boolean.TRUE) != null) {
                throw new IllegalArgumentException("Configuration map nesting is cyclic or too deep");
            }
            try {
                Map<String, Object> entries = container instanceof ConfigurationSection section
                        ? section.getValues(false) : index((Map<?, ?>) container);
                for (Map.Entry<String, Object> entry : entries.entrySet()) {
                    if (++count[0] > MAX_KEYS) {
                        throw new IllegalArgumentException("Configuration map contains too many keys");
                    }
                    String key = entry.getKey();
                    String path = prefix + key;
                    result.add(path);
                    Object child = entry.getValue();
                    if (deep && (child instanceof Map<?, ?> || child instanceof ConfigurationSection)) {
                        collectKeys(child, path + separator, true, result, depth + 1, count, ancestors);
                    }
                }
            } finally {
                ancestors.remove(container);
            }
        }
    }

    public BukkitStructuredConfigView(ConfigurationSection section) { super(section); }

    @Override protected BukkitStructuredConfigView sectionView(ConfigurationSection child) {
        return new BukkitStructuredConfigView(child);
    }
    @Override public BukkitStructuredConfigView getConfigurationSection(String path) {
        return (BukkitStructuredConfigView) super.getConfigurationSection(path);
    }
    @Override public BukkitStructuredConfigView at(String... keys) {
        Object value = literal(keys);
        return value instanceof ConfigurationSection section ? sectionView(section) : null;
    }
    @Override public StructuredConfigView structuredAt(String... keys) {
        Object value = literal(keys);
        if (value instanceof ConfigurationSection section) return sectionView(section);
        if (value instanceof Map<?, ?> map) {
            ConfigurationSection section = getSection();
            org.bukkit.configuration.Configuration root = section.getRoot();
            return new RawMapView(map, root == null ? '.' : root.options().pathSeparator());
        }
        return null;
    }
    @Override public Kind kind(String path) { return classify(getSection().get(path)); }
    @Override public Kind kindAt(String... keys) { return classify(literal(keys)); }
    private static Kind classify(Object value) {
        return value instanceof ConfigurationSection ? Kind.SECTION : PlainConfigValues.kind(value);
    }
    @Override public Object value(String path) { return detached(getSection().get(path)); }
    @Override public Object valueAt(String... keys) { return detached(literal(keys)); }
    private static Object detached(Object value) {
        return PlainConfigValues.copy(value, current -> current instanceof ConfigurationSection section
                ? section.getValues(false) : current);
    }

    private Object literal(String... keys) {
        Objects.requireNonNull(keys, "keys");
        Object current = getSection();
        for (String key : keys) {
            Objects.requireNonNull(key, "key");
            if (current instanceof ConfigurationSection section) {
                current = sectionLiteral(section, key);
            } else if (current instanceof Map<?, ?> map) {
                current = RawMapView.index(map).get(key);
            } else {
                return null;
            }
        }
        return current;
    }

    private static Object sectionLiteral(ConfigurationSection section, String key) {
        Map<String, Object> values = section.getValues(false);
        if (values.containsKey(key)) return values.get(key);
        ConfigurationSection defaults = section.getDefaultSection();
        return defaults == null ? null : defaults.getValues(false).get(key);
    }
}
