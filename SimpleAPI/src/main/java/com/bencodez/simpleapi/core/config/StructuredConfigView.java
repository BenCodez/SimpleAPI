package com.bencodez.simpleapi.core.config;

import java.util.Map;

import com.bencodez.simpleapi.file.config.ConfigView;

/**
 * Additive reward-definition reads. Existing ConfigView implementations do not
 * need to implement this interface. Views retain their backing API's getter and
 * default rules; a view is not a promise of immutability or thread safety.
 */
public interface StructuredConfigView extends ConfigView {
    /** MAP is a raw map value, not a native configuration SECTION. */
    enum Kind { MISSING, STRING, BOOLEAN, NUMBER, LIST, MAP, SECTION, OTHER }

    Kind kind(String path);
    Kind kindAt(String... keys);

    /**
     * Returns a detached, unmodifiable plain-data tree, or null when absent.
     * Native objects are rejected, never serialized or converted to strings.
     */
    Object value(String path);
    Object valueAt(String... keys);

    /** Section lookup using literal keys, including keys containing dots. */
    StructuredConfigView at(String... keys);

    @Override
    StructuredConfigView getConfigurationSection(String path);

    /**
     * Exports the section's enumerated values. Defaults not exposed by the
     * backing API's key enumeration are not implicitly materialized. Individual
     * getter/value reads still follow that API's configured default behavior.
     */
    @SuppressWarnings("unchecked")
    default Map<String, Object> snapshotValues() {
        Object result = valueAt();
        if (!(result instanceof Map<?, ?>)) {
            throw new IllegalStateException("A section snapshot must be a mapping");
        }
        return (Map<String, Object>) result;
    }
}
