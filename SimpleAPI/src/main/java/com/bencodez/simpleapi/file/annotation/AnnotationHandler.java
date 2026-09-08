package com.bencodez.simpleapi.file.annotation;

import org.bukkit.configuration.ConfigurationSection;

/** Original public API retained for source and binary compatibility. */
public class AnnotationHandler extends com.bencodez.simpleapi.bukkit.config.AnnotationHandler {
    public AnnotationHandler() { super(); }
    @Override public void load(ConfigurationSection config, Object target) { super.load(config, target); }
}
