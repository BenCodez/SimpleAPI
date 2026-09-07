package com.bencodez.simpleapi.file.config;

import java.util.List;
import java.util.Set;

/**
 * Read-only access used by the annotation binder, independent of a server API.
 *
 * <p>Implementations own path traversal, configured defaults and scalar/list
 * conversion semantics. A platform adapter must delegate these operations rather
 * than round-tripping a live configuration through a different parser. This
 * interface does not imply a snapshot, thread safety, or permission to mutate the
 * underlying configuration.</p>
 *
 * <p>List getters return an empty list when no compatible list exists. Section
 * lookup returns {@code null} when absent. Paths and the {@code deep} key flag
 * follow the backing configuration's rules.</p>
 */
public interface ConfigView {

    boolean contains(String path);

    String getString(String path, String defaultValue);

    boolean getBoolean(String path, boolean defaultValue);

    int getInt(String path, int defaultValue);

    long getLong(String path, long defaultValue);

    double getDouble(String path, double defaultValue);

    List<String> getStringList(String path);

    List<Integer> getIntegerList(String path);

    boolean isConfigurationSection(String path);

    ConfigView getConfigurationSection(String path);

    Set<String> getKeys(boolean deep);
}
