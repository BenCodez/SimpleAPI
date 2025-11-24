package com.bencodez.simpleapi.file;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Wrapper that makes all file configuration lookups case-insensitive.
 */
public class CaseInsensitiveFileConfiguration {

	private final FileConfiguration delegate;

	public CaseInsensitiveFileConfiguration(FileConfiguration delegate) {
		if (delegate == null) {
			throw new IllegalArgumentException("delegate cannot be null");
		}
		this.delegate = delegate;
	}

	public FileConfiguration getDelegate() {
		return delegate;
	}

	// --------------------------------------------------------
	// Internal helpers
	// --------------------------------------------------------

	private static String findRealKey(ConfigurationSection section, String key) {
		if (section == null || key == null) {
			return null;
		}
		for (String k : section.getKeys(false)) {
			if (k.equalsIgnoreCase(key)) {
				return k;
			}
		}
		return null;
	}

	private static class ResolvedPath {
		final ConfigurationSection section;
		final String key;

		ResolvedPath(ConfigurationSection section, String key) {
			this.section = section;
			this.key = key;
		}
	}

	/** Resolve nested path ignoring case for each segment */
	private ResolvedPath resolvePath(String path) {
		if (path == null || path.isEmpty()) {
			return null;
		}

		String[] parts = path.split("\\.");
		ConfigurationSection current = delegate;

		if (parts.length == 1) {
			String real = findRealKey(current, parts[0]);
			if (real == null)
				return null;
			return new ResolvedPath(current, real);
		}

		// walk intermediate sections
		for (int i = 0; i < parts.length - 1; i++) {
			String part = parts[i];
			String realKey = findRealKey(current, part);
			if (realKey == null)
				return null;

			current = current.getConfigurationSection(realKey);
			if (current == null)
				return null;
		}

		// last key
		String last = parts[parts.length - 1];
		String realLast = findRealKey(current, last);
		if (realLast == null)
			return null;

		return new ResolvedPath(current, realLast);
	}

	// --------------------------------------------------------
	// Generic accessors
	// --------------------------------------------------------

	public Object get(String path) {
		ResolvedPath rp = resolvePath(path);
		return rp == null ? null : rp.section.get(rp.key);
	}

	public Object get(String path, Object def) {
		Object val = get(path);
		return val != null ? val : def;
	}

	public boolean contains(String path) {
		return resolvePath(path) != null;
	}

	public boolean isSet(String path) {
		return contains(path);
	}

	// --------------------------------------------------------
	// Typed shortcuts
	// --------------------------------------------------------

	public String getString(String path) {
		ResolvedPath rp = resolvePath(path);
		return rp == null ? null : rp.section.getString(rp.key);
	}

	public String getString(String path, String def) {
		String v = getString(path);
		return v != null ? v : def;
	}

	public int getInt(String path) {
		ResolvedPath rp = resolvePath(path);
		return rp == null ? 0 : rp.section.getInt(rp.key);
	}

	public int getInt(String path, int def) {
		ResolvedPath rp = resolvePath(path);
		return rp == null ? def : rp.section.getInt(rp.key, def);
	}

	public long getLong(String path) {
		ResolvedPath rp = resolvePath(path);
		return rp == null ? 0L : rp.section.getLong(rp.key);
	}

	public long getLong(String path, long def) {
		ResolvedPath rp = resolvePath(path);
		return rp == null ? def : rp.section.getLong(rp.key, def);
	}

	public double getDouble(String path) {
		ResolvedPath rp = resolvePath(path);
		return rp == null ? 0.0 : rp.section.getDouble(rp.key);
	}

	public double getDouble(String path, double def) {
		ResolvedPath rp = resolvePath(path);
		return rp == null ? def : rp.section.getDouble(rp.key, def);
	}

	public boolean getBoolean(String path) {
		ResolvedPath rp = resolvePath(path);
		return rp != null && rp.section.getBoolean(rp.key);
	}

	public boolean getBoolean(String path, boolean def) {
		ResolvedPath rp = resolvePath(path);
		return rp == null ? def : rp.section.getBoolean(rp.key, def);
	}

	@SuppressWarnings("unchecked")
	public List<?> getList(String path) {
		ResolvedPath rp = resolvePath(path);
		if (rp == null)
			return null;
		Object v = rp.section.get(rp.key);
		return v instanceof List<?> ? (List<?>) v : null;
	}

	public List<String> getStringList(String path) {
		ResolvedPath rp = resolvePath(path);
		return rp == null ? Collections.emptyList() : rp.section.getStringList(rp.key);
	}

	// --------------------------------------------------------
	// Section handling
	// --------------------------------------------------------

	public CaseInsensitiveSectionWrapper getSection(String path) {
		if (path == null || path.isEmpty()) {
			return new CaseInsensitiveSectionWrapper(delegate);
		}

		String[] parts = path.split("\\.");
		ConfigurationSection current = delegate;

		for (String part : parts) {
			String realKey = findRealKey(current, part);
			if (realKey == null) {
				return null;
			}
			current = current.getConfigurationSection(realKey);
			if (current == null) {
				return null;
			}
		}

		return new CaseInsensitiveSectionWrapper(current);
	}

	public Set<String> getKeys(boolean deep) {
		return delegate.getKeys(deep);
	}

	// --------------------------------------------------------
	// Nested class for wrapping child sections
	// --------------------------------------------------------
	public static class CaseInsensitiveSectionWrapper extends CaseInsensitiveFileConfiguration {

		public CaseInsensitiveSectionWrapper(ConfigurationSection section) {
			super((FileConfiguration) section); // safe because Bukkit internally uses FileConfiguration for all
												// top-levels
		}
	}
}
