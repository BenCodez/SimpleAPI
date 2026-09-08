package com.bencodez.simpleapi.file.annotation;

import java.util.function.Function;
import com.bencodez.simpleapi.file.config.ConfigView;

/** Compatibility name for the shared binder. Existing annotations and rules are unchanged. */
public class AnnotationBinder extends com.bencodez.simpleapi.core.config.AnnotationBinder {
    public AnnotationBinder() { super(); }
    public AnnotationBinder(Function<ConfigView, ?> sectionValue) { super(sectionValue); }
    @Override public void load(ConfigView config, Object target) { super.load(config, target); }
}
