package com.bencodez.simpleapi.core.config;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Read-only case-insensitive reward view. Like the legacy CaseInsensitiveSection,
 * the first key in the backing section's enumeration wins a case collision; an
 * exact spelling does not silently take precedence. Values/casing are not changed.
 * Use the constructor separator matching the backing view (normally '.').
 */
public final class CaseInsensitiveConfigView implements StructuredConfigView {
    private final StructuredConfigView delegate;
    private final char separator;

    public CaseInsensitiveConfigView(StructuredConfigView delegate) { this(delegate, '.'); }
    public CaseInsensitiveConfigView(StructuredConfigView delegate, char separator) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.separator = separator;
    }

    private record Resolved(StructuredConfigView parent, String key) { }

    private String[] split(String path) {
        Objects.requireNonNull(path, "path");
        // Retain the legacy path wrapper's trailing-separator behavior.
        return path.isEmpty() ? new String[0] : path.split(Pattern.quote(String.valueOf(separator)));
    }

    private Resolved resolve(String... keys) {
        Objects.requireNonNull(keys, "keys");
        StructuredConfigView current = delegate;
        if (keys.length == 0) return new Resolved(current, null);
        for (int index = 0; index < keys.length; index++) {
            String requested = Objects.requireNonNull(keys[index], "key");
            String actual = null;
            for (String candidate : current.getKeys(false)) {
                if (candidate.equalsIgnoreCase(requested)) { actual = candidate; break; }
            }
            if (actual == null) return null;
            if (index == keys.length - 1) return new Resolved(current, actual);
            current = current.at(actual);
            if (current == null) return null;
        }
        throw new AssertionError("Unreachable key traversal");
    }

    private static String path(Resolved value) { return value.key() == null ? "" : value.key(); }

    @Override public Kind kind(String path) { return kindAt(split(path)); }
    @Override public Kind kindAt(String... keys) {
        Resolved value = resolve(keys);
        if (value == null) return Kind.MISSING;
        return value.key() == null ? value.parent().kindAt() : value.parent().kindAt(value.key());
    }
    @Override public Object value(String path) { return valueAt(split(path)); }
    @Override public Object valueAt(String... keys) {
        Resolved value = resolve(keys);
        if (value == null) return null;
        return value.key() == null ? value.parent().valueAt() : value.parent().valueAt(value.key());
    }
    @Override public CaseInsensitiveConfigView at(String... keys) {
        Resolved value = resolve(keys);
        if (value == null) return null;
        StructuredConfigView child = value.key() == null ? value.parent() : value.parent().at(value.key());
        return child == null ? null : new CaseInsensitiveConfigView(child, separator);
    }
    @Override public CaseInsensitiveConfigView getConfigurationSection(String path) { return at(split(path)); }
    @Override public boolean isConfigurationSection(String path) { return kind(path) == Kind.SECTION; }
    @Override public boolean contains(String path) {
        Resolved value = resolve(split(path));
        return value != null && value.parent().contains(path(value));
    }
    @Override public String getString(String path, String fallback) {
        Resolved value = resolve(split(path));
        return value == null ? fallback : value.parent().getString(path(value), fallback);
    }
    @Override public boolean getBoolean(String path, boolean fallback) {
        Resolved value = resolve(split(path));
        return value == null ? fallback : value.parent().getBoolean(path(value), fallback);
    }
    @Override public int getInt(String path, int fallback) {
        Resolved value = resolve(split(path));
        return value == null ? fallback : value.parent().getInt(path(value), fallback);
    }
    @Override public long getLong(String path, long fallback) {
        Resolved value = resolve(split(path));
        return value == null ? fallback : value.parent().getLong(path(value), fallback);
    }
    @Override public double getDouble(String path, double fallback) {
        Resolved value = resolve(split(path));
        return value == null ? fallback : value.parent().getDouble(path(value), fallback);
    }
    @Override public List<String> getStringList(String path) {
        Resolved value = resolve(split(path));
        return value == null ? List.of() : value.parent().getStringList(path(value));
    }
    @Override public List<Integer> getIntegerList(String path) {
        Resolved value = resolve(split(path));
        return value == null ? List.of() : value.parent().getIntegerList(path(value));
    }
    @Override public Set<String> getKeys(boolean deep) { return delegate.getKeys(deep); }
}
