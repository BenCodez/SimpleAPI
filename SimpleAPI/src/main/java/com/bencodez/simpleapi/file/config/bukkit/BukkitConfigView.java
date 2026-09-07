package com.bencodez.simpleapi.file.config.bukkit;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.bukkit.configuration.ConfigurationSection;

import com.bencodez.simpleapi.file.config.ConfigView;

/**
 * Live, read-only facade over a Bukkit configuration section. Reads deliberately
 * preserve the backing section's defaults, coercions, key order and path options.
 */
public final class BukkitConfigView implements ConfigView {

    private final ConfigurationSection section;

    public BukkitConfigView(ConfigurationSection section) {
        this.section = Objects.requireNonNull(section, "section");
    }

    /**
     * Returns the original section for legacy Bukkit-typed annotated fields.
     * No copy is made: existing callers retain section identity and mutability.
     * This method belongs to the Bukkit adapter, not the platform-neutral API.
     *
     * @return the backing Bukkit section
     */
    public ConfigurationSection getSection() {
        return section;
    }

    @Override
    public boolean contains(String path) {
        return section.contains(path);
    }

    @Override
    public String getString(String path, String defaultValue) {
        return section.getString(path, defaultValue);
    }

    @Override
    public boolean getBoolean(String path, boolean defaultValue) {
        return section.getBoolean(path, defaultValue);
    }

    @Override
    public int getInt(String path, int defaultValue) {
        return section.getInt(path, defaultValue);
    }

    @Override
    public long getLong(String path, long defaultValue) {
        return section.getLong(path, defaultValue);
    }

    @Override
    public double getDouble(String path, double defaultValue) {
        return section.getDouble(path, defaultValue);
    }

    @Override
    public List<String> getStringList(String path) {
        return section.getStringList(path);
    }

    @Override
    public List<Integer> getIntegerList(String path) {
        return section.getIntegerList(path);
    }

    @Override
    public boolean isConfigurationSection(String path) {
        return section.isConfigurationSection(path);
    }

    @Override
    public BukkitConfigView getConfigurationSection(String path) {
        ConfigurationSection child = section.getConfigurationSection(path);
        return child == null ? null : new BukkitConfigView(child);
    }

    @Override
    public Set<String> getKeys(boolean deep) {
        return section.getKeys(deep);
    }
}
