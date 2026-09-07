package com.bencodez.simpleapi.file.config;

import java.util.Objects;

/** Detached read view and opaque content revision used to reject stale edits. */
public record ConfigSnapshot(String revision, ConfigView view) {
    public ConfigSnapshot {
        Objects.requireNonNull(revision, "revision");
        Objects.requireNonNull(view, "view");
    }
}
