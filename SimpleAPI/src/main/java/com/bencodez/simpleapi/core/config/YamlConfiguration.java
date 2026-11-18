package com.bencodez.simpleapi.core.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import com.bencodez.simpleapi.file.config.ConfigEditor;
import com.bencodez.simpleapi.file.config.ConfigSnapshot;

/**
 * Platform-neutral YAML file facade with typed reads and atomic, revision-checked
 * persistence. All file operations are synchronous; the caller owns its I/O thread
 * and trusted parent directory. Existing files are never automatically rewritten.
 *
 * <p>Resource defaults initialize missing files only, not a read-time overlay.
 * Getter defaults, strict scalar reads, legacy list conversions and literal keys
 * follow the existing configuration views. Sections and snapshots are detached;
 * obtain them again after a successful reload/update. Writes are immediately
 * persisted, not staged in a mutable configuration tree. Formatting/inline comment
 * preservation across edits is not promised.</p>
 */
public final class YamlConfiguration implements StructuredConfigView {
    private final YamlConfigDocument document;
    private volatile ConfigSnapshot current;
    private volatile boolean ignoreCase;

    private YamlConfiguration(YamlConfigDocument document) {
        this.document = document;
        publish(document.snapshot());
    }

    /** Reads an existing file, or an empty missing document without creating it. */
    public static YamlConfiguration load(Path path) throws IOException {
        return load(path, YamlConfigDocument.DEFAULT_MAX_BYTES);
    }

    public static YamlConfiguration load(Path path, int maxBytes) throws IOException {
        return new YamlConfiguration(YamlConfigDocument.open(path, maxBytes));
    }

    /**
     * Initializes a missing file from a classpath resource. An existing file is
     * loaded without opening the resource. Resource names follow Class.getResourceAsStream.
     */
    public static YamlConfiguration load(Path path, Class<?> resourceOwner, String resourceName) throws IOException {
        Objects.requireNonNull(resourceOwner, "resourceOwner");
        Objects.requireNonNull(resourceName, "resourceName");
        YamlConfiguration configuration = load(path);
        if (configuration.current.revision().equals("missing")) {
            byte[] bytes;
            try (InputStream defaults = resourceOwner.getResourceAsStream(resourceName)) {
                if (defaults == null) throw new IOException("Default YAML resource is missing");
                bytes = defaults.readNBytes(YamlConfigDocument.DEFAULT_MAX_BYTES + 1);
            }
            configuration.publish(configuration.document.initializeFrom(bytes));
        }
        return configuration;
    }

    /** The supplied stream is consumed only for a missing file and always closed, including on failure. */
    public static YamlConfiguration load(Path path, InputStream defaults) throws IOException {
        return load(path, defaults, YamlConfigDocument.DEFAULT_MAX_BYTES);
    }

    public static YamlConfiguration load(Path path, InputStream defaults, int maxBytes) throws IOException {
        Objects.requireNonNull(defaults, "defaults");
        YamlConfiguration configuration;
        byte[] bytes = null;
        try (defaults) {
            configuration = load(path, maxBytes);
            if (configuration.current.revision().equals("missing")) bytes = defaults.readNBytes(maxBytes + 1);
        }
        if (bytes != null) configuration.publish(configuration.document.initializeFrom(bytes));
        return configuration;
    }

    public Path path() { return document.path(); }
    public ConfigSnapshot snapshot() { return current; }
    public StructuredConfigView getData() { return (StructuredConfigView) current.view(); }
    public boolean isIgnoreCase() { return ignoreCase; }

    /** Opt-in read policy only. Edits always use exact keys; use setAt for literal segments. */
    public synchronized void setIgnoreCase(boolean ignoreCase) {
        this.ignoreCase = ignoreCase;
        publish(document.snapshot());
    }

    /** Failed reloads leave both the active snapshot and file untouched. */
    public synchronized ConfigSnapshot reload() throws IOException { return publish(document.reload()); }

    /** Atomically persists an edit using the active revision. Failed edits publish nothing. */
    public synchronized ConfigSnapshot update(Consumer<ConfigEditor> edit) throws IOException {
        return update(current.revision(), edit);
    }

    public synchronized ConfigSnapshot update(String expectedRevision, Consumer<ConfigEditor> edit) throws IOException {
        return publish(document.update(expectedRevision, edit));
    }

    /** Immediately persists a value; null removes it. This is not an in-memory-only setter. */
    public ConfigSnapshot set(String path, Object value) throws IOException {
        return update(edit -> edit.set(path, value));
    }

    public ConfigSnapshot setAt(Object value, String... keys) throws IOException {
        return update(edit -> edit.setAt(value, keys));
    }

    /** Explicitly saves the current document, including creation of an empty missing file. */
    public ConfigSnapshot save() throws IOException { return update(edit -> { }); }

    private ConfigSnapshot publish(ConfigSnapshot snapshot) {
        StructuredConfigView view = new ConfigurateStructuredConfigView((ConfigurateConfigView) snapshot.view());
        if (ignoreCase) view = new CaseInsensitiveConfigView(view);
        current = new ConfigSnapshot(snapshot.revision(), view);
        return current;
    }

    public String getString(String path) { return getString(path, null); }
    public boolean getBoolean(String path) { return getBoolean(path, false); }
    public int getInt(String path) { return getInt(path, 0); }
    public long getLong(String path) { return getLong(path, 0L); }
    public double getDouble(String path) { return getDouble(path, 0D); }

    @Override public boolean contains(String path) { return getData().contains(path); }
    @Override public String getString(String path, String fallback) { return getData().getString(path, fallback); }
    @Override public boolean getBoolean(String path, boolean fallback) { return getData().getBoolean(path, fallback); }
    @Override public int getInt(String path, int fallback) { return getData().getInt(path, fallback); }
    @Override public long getLong(String path, long fallback) { return getData().getLong(path, fallback); }
    @Override public double getDouble(String path, double fallback) { return getData().getDouble(path, fallback); }
    @Override public List<String> getStringList(String path) { return getData().getStringList(path); }
    @Override public List<Integer> getIntegerList(String path) { return getData().getIntegerList(path); }
    @Override public boolean isConfigurationSection(String path) { return getData().isConfigurationSection(path); }
    @Override public StructuredConfigView getConfigurationSection(String path) { return getData().getConfigurationSection(path); }
    @Override public Set<String> getKeys(boolean deep) { return getData().getKeys(deep); }
    @Override public Kind kind(String path) { return getData().kind(path); }
    @Override public Kind kindAt(String... keys) { return getData().kindAt(keys); }
    @Override public Object value(String path) { return getData().value(path); }
    @Override public Object valueAt(String... keys) { return getData().valueAt(keys); }
    @Override public StructuredConfigView at(String... keys) { return getData().at(keys); }
}
