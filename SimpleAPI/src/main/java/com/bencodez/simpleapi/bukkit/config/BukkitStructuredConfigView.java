package com.bencodez.simpleapi.bukkit.config;

import java.util.Map;
import java.util.Objects;

import org.bukkit.configuration.ConfigurationSection;

import com.bencodez.simpleapi.core.config.PlainConfigValues;
import com.bencodez.simpleapi.core.config.StructuredConfigView;

/**
 * Live structured Bukkit reads. Ordinary getters still delegate to Bukkit.
 * Literal reads never change the root path separator or mutate the section.
 */
public final class BukkitStructuredConfigView extends BukkitConfigView implements StructuredConfigView {
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
                Map<String, Object> values = section.getValues(false);
                if (values.containsKey(key)) {
                    current = values.get(key);
                } else {
                    ConfigurationSection defaults = section.getDefaultSection();
                    current = defaults == null ? null : defaults.getValues(false).get(key);
                }
            } else if (current instanceof Map<?, ?> map) {
                current = map.get(key);
            } else {
                return null;
            }
        }
        return current;
    }
}
