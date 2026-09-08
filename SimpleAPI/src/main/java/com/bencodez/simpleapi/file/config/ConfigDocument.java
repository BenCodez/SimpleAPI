package com.bencodez.simpleapi.file.config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Synchronous local-file configuration operations. Call from an I/O worker, not
 * a server/entity thread. Reads and failed operations never create empty files
 * or replace a last-good snapshot. The caller owns exclusive writes to the file;
 * content revisions detect observed external edits, not cross-process file CAS.
 */
public interface ConfigDocument {
    Path path();
    ConfigSnapshot snapshot();
    ConfigSnapshot reload() throws IOException;
    /**
     * Edits a private copy, persists it, then publishes the new snapshot. Any
     * validation, stale-revision or pre-publication I/O failure leaves the current
     * in-memory snapshot unchanged. The callback must not reenter document writes.
     */
    ConfigSnapshot update(String expectedRevision, Consumer<ConfigEditor> edit) throws IOException;
}
