package com.bencodez.simpleapi.file.config;

/**
 * A short-lived editor supplied by ConfigDocument.update. Retaining an editor
 * does not grant access to future document state. Implementations reject writes
 * after the callback returns. Values are plain scalar/list/string-keyed map trees;
 * native player/item objects must be converted by the owning platform first.
 */
public interface ConfigEditor extends ConfigView {
    /** Sets a value using the document's separator. Null removes the value. */
    void set(String path, Object value);
    /** Sets a value using literal key segments, including keys containing dots. */
    void setAt(Object value, String... keys);
    default void remove(String path) { set(path, null); }
}
