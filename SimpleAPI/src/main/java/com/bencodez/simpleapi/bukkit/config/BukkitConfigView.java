package com.bencodez.simpleapi.bukkit.config;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;
import com.bencodez.simpleapi.file.config.ConfigView;

/** Live Bukkit adapter. Defaults, coercions, native section identity and path rules are delegated. */
public class BukkitConfigView implements ConfigView {
    private final ConfigurationSection section;
    public BukkitConfigView(ConfigurationSection section) { this.section = Objects.requireNonNull(section, "section"); }
    public ConfigurationSection getSection() { return section; }
    @Override public boolean contains(String path) { return section.contains(path); }
    @Override public String getString(String path, String fallback) { return section.getString(path, fallback); }
    @Override public boolean getBoolean(String path, boolean fallback) { return section.getBoolean(path, fallback); }
    @Override public int getInt(String path, int fallback) { return section.getInt(path, fallback); }
    @Override public long getLong(String path, long fallback) { return section.getLong(path, fallback); }
    @Override public double getDouble(String path, double fallback) { return section.getDouble(path, fallback); }
    @Override public List<String> getStringList(String path) { return section.getStringList(path); }
    @Override public List<Integer> getIntegerList(String path) { return section.getIntegerList(path); }
    @Override public boolean isConfigurationSection(String path) { return section.isConfigurationSection(path); }
    @Override public BukkitConfigView getConfigurationSection(String path) {
        ConfigurationSection child = section.getConfigurationSection(path);
        return child == null ? null : sectionView(child);
    }
    protected BukkitConfigView sectionView(ConfigurationSection child) { return new BukkitConfigView(child); }
    @Override public Set<String> getKeys(boolean deep) { return section.getKeys(deep); }
}
