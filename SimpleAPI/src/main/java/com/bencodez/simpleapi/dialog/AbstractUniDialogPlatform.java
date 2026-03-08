package com.bencodez.simpleapi.dialog;

import java.util.Objects;
import java.util.UUID;

import org.bukkit.plugin.Plugin;

/**
 * Base class for UniDialog platform implementations.
 */
public abstract class AbstractUniDialogPlatform implements UniDialogPlatform {

    protected final Plugin plugin;
    protected final String defaultNamespace;

    protected AbstractUniDialogPlatform(Plugin plugin, String defaultNamespace) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.defaultNamespace = Objects.requireNonNull(defaultNamespace, "defaultNamespace");
    }

    protected String resolveNamespace(String namespace) {
        if (namespace == null || namespace.isEmpty()) {
            return defaultNamespace;
        }
        return namespace;
    }

    protected String resolveActionId(String id) {
        if (id == null || id.isEmpty()) {
            return UUID.randomUUID().toString().replace("-", "");
        }
        return id;
    }
}
