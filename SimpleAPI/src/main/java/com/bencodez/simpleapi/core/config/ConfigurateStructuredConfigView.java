package com.bencodez.simpleapi.core.config;

import java.util.Map;

import org.spongepowered.configurate.ConfigurationNode;

/** Structured reads layered on the existing Configurate getter implementation. */
public final class ConfigurateStructuredConfigView extends ConfigurateConfigView implements StructuredConfigView {
    public ConfigurateStructuredConfigView(ConfigurationNode node) { super(node); }
    public ConfigurateStructuredConfigView(ConfigurationNode node, char separator) { super(node, separator); }
    public ConfigurateStructuredConfigView(ConfigurateConfigView source) { super(source); }

    @Override
    protected ConfigurateStructuredConfigView sectionView(ConfigurationNode child) {
        return new ConfigurateStructuredConfigView(super.sectionView(child));
    }

    @Override
    public ConfigurateStructuredConfigView at(String... keys) {
        return (ConfigurateStructuredConfigView) super.at(keys);
    }

    @Override
    public ConfigurateStructuredConfigView getConfigurationSection(String path) {
        return (ConfigurateStructuredConfigView) super.getConfigurationSection(path);
    }

    @Override public Kind kind(String path) { return kindAt(segments(path)); }
    @Override public Kind kindAt(String... keys) {
        ConfigurationNode child = resolve(keys);
        if (child.isMap() || child == node && child.isNull()) return Kind.SECTION;
        if (child.isList()) return Kind.LIST;
        return PlainConfigValues.kind(child.rawScalar());
    }

    @Override public Object value(String path) { return valueAt(segments(path)); }
    @Override public Object valueAt(String... keys) {
        ConfigurationNode child = resolve(keys);
        if (child == node && child.isNull()) return Map.of();
        return PlainConfigValues.copy(child, value -> {
            if (!(value instanceof ConfigurationNode current)) return value;
            if (current.isMap()) return current.childrenMap();
            if (current.isList()) return current.childrenList();
            return current.rawScalar();
        });
    }
}
