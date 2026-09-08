package com.bencodez.simpleapi.file.config.configurate;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;
import com.bencodez.simpleapi.file.config.ConfigDocument;
import com.bencodez.simpleapi.file.config.ConfigEditor;
import com.bencodez.simpleapi.file.config.ConfigSnapshot;

/** Compatibility facade. Persistence and validation live only in the core implementation. */
public final class YamlConfigDocument implements ConfigDocument {
    public static final int DEFAULT_MAX_BYTES = com.bencodez.simpleapi.core.config.YamlConfigDocument.DEFAULT_MAX_BYTES;
    private final com.bencodez.simpleapi.core.config.YamlConfigDocument delegate;

    private YamlConfigDocument(com.bencodez.simpleapi.core.config.YamlConfigDocument delegate) {
        this.delegate = delegate;
    }
    public static YamlConfigDocument open(Path path) throws IOException {
        return new YamlConfigDocument(com.bencodez.simpleapi.core.config.YamlConfigDocument.open(path));
    }
    public static YamlConfigDocument open(Path path, int maxBytes) throws IOException {
        return new YamlConfigDocument(com.bencodez.simpleapi.core.config.YamlConfigDocument.open(path, maxBytes));
    }
    @Override public Path path() { return delegate.path(); }
    @Override public synchronized ConfigSnapshot snapshot() { return legacySnapshot(delegate.snapshot()); }
    @Override public synchronized ConfigSnapshot reload() throws IOException { return legacySnapshot(delegate.reload()); }
    @Override public synchronized ConfigSnapshot update(String expectedRevision, Consumer<ConfigEditor> edit) throws IOException {
        return legacySnapshot(delegate.update(expectedRevision, edit));
    }
    private static ConfigSnapshot legacySnapshot(ConfigSnapshot snapshot) {
        return new ConfigSnapshot(snapshot.revision(), ConfigurateConfigView.documentView(
                (com.bencodez.simpleapi.core.config.ConfigurateConfigView) snapshot.view()));
    }
}
