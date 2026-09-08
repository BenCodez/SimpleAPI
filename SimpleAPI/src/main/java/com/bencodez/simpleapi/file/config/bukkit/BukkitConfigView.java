package com.bencodez.simpleapi.file.config.bukkit;

import org.bukkit.configuration.ConfigurationSection;

/** Compatibility name. New Bukkit integration code belongs in simpleapi.bukkit. */
public final class BukkitConfigView extends com.bencodez.simpleapi.bukkit.config.BukkitConfigView {
    public BukkitConfigView(ConfigurationSection section) { super(section); }
    @Override protected BukkitConfigView sectionView(ConfigurationSection child) { return new BukkitConfigView(child); }
    @Override public BukkitConfigView getConfigurationSection(String path) {
        return (BukkitConfigView) super.getConfigurationSection(path);
    }
}
