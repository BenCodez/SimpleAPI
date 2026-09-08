package com.bencodez.simpleapi.file.config.configurate;

import org.spongepowered.configurate.ConfigurationNode;

/** Compatibility facade retaining the original constructors and covariant return types. */
public class ConfigurateConfigView extends com.bencodez.simpleapi.core.config.ConfigurateConfigView {
    private final char legacySeparator;

    public ConfigurateConfigView(ConfigurationNode node) { this(node, '.'); }
    public ConfigurateConfigView(ConfigurationNode node, char separator) {
        super(node, separator);
        legacySeparator = separator;
    }
    private ConfigurateConfigView(com.bencodez.simpleapi.core.config.ConfigurateConfigView source) {
        super(source);
        // Document snapshots always use the default separator.
        legacySeparator = '.';
    }
    static ConfigurateConfigView documentView(com.bencodez.simpleapi.core.config.ConfigurateConfigView source) {
        return new ConfigurateConfigView(source);
    }
    @Override protected ConfigurateConfigView sectionView(ConfigurationNode child) {
        return new ConfigurateConfigView(child, legacySeparator);
    }
    @Override public ConfigurateConfigView at(String... keys) {
        return (ConfigurateConfigView) super.at(keys);
    }
    @Override public ConfigurateConfigView getConfigurationSection(String path) {
        return (ConfigurateConfigView) super.getConfigurationSection(path);
    }
}
