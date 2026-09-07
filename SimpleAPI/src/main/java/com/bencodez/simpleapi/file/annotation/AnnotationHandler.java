package com.bencodez.simpleapi.file.annotation;

import org.bukkit.configuration.ConfigurationSection;

import com.bencodez.simpleapi.file.config.bukkit.BukkitConfigView;

/** Bukkit-compatible entry point for the shared annotation binder. */
public class AnnotationHandler {

    private final AnnotationBinder binder = new AnnotationBinder(
            view -> ((BukkitConfigView) view).getSection());

    public AnnotationHandler() {
    }

    /**
     * Loads the existing annotations without changing their Bukkit behavior.
     * This signature remains unchanged; a ConfigView overload is deliberately
     * not added, so existing calls such as load(null, target) stay unambiguous.
     *
     * @param config Bukkit configuration (legacy null handling is preserved)
     * @param classToLoad object whose declared fields should be populated
     */
    public void load(ConfigurationSection config, Object classToLoad) {
        binder.load(config == null ? null : new BukkitConfigView(config), classToLoad);
    }
}
