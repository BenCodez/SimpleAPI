package com.bencodez.simpleapi.bukkit.config;

import org.bukkit.configuration.ConfigurationSection;
import com.bencodez.simpleapi.core.config.AnnotationBinder;

/** Bukkit entry point; native section-typed fields continue receiving the original section. */
public class AnnotationHandler {
    private final AnnotationBinder binder = new AnnotationBinder(view -> ((BukkitConfigView) view).getSection());
    public AnnotationHandler() { }
    public void load(ConfigurationSection config, Object target) {
        binder.load(config == null ? null : new BukkitConfigView(config), target);
    }
}
