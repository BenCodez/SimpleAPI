package com.bencodez.simpleapi.file;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import com.bencodez.simpleapi.scheduler.BukkitScheduler;

import lombok.Getter;
import net.md_5.bungee.api.ChatColor;

/**
 * The Class YMLFile.
 */
public abstract class YMLFile {

    private boolean created = false;

    /** The data. */
    private FileConfiguration data;

    /** The d file. */
    private File dFile;

    @Getter
    private boolean failedToRead = false;

    @Getter
    private JavaPlugin plugin;

    private BukkitScheduler scheduler;

    /**
     * If true, all lookups on {@link #getData()} will be case-insensitive on each
     * path segment (Rewards.Commands == rewards.commands == REWARDS.commands).
     */
    @Getter
    private boolean ignoreCase = false;

    public YMLFile(JavaPlugin plugin, File file) {
        dFile = file;
        this.plugin = plugin;
        scheduler = new BukkitScheduler(plugin);
    }

    public YMLFile(JavaPlugin plugin, File file, BukkitScheduler scheduler) {
        this.dFile = file;
        this.plugin = plugin;
        this.scheduler = scheduler;
    }

    public YMLFile(JavaPlugin plugin, File file, BukkitScheduler scheduler, boolean setup) {
        this(plugin, file, scheduler);
        if (setup) {
            setup();
        }
    }

    public YMLFile(JavaPlugin plugin, File file, boolean setup) {
        dFile = file;
        this.plugin = plugin;
        scheduler = new BukkitScheduler(plugin);
        if (setup) {
            setup();
        }
    }

    /**
     * Enable/disable case-insensitive path lookups.
     *
     * Should normally be called BEFORE {@link #setup()} or {@link #reloadData()}.
     * If called after data is already loaded, it will re-wrap/unwrap the config
     * in-place.
     */
    public void setIgnoreCase(boolean ignoreCase) {
    	if (this.ignoreCase == ignoreCase) {
    		return;
    	}
        this.ignoreCase = ignoreCase;
        if (data != null) {
            if (ignoreCase && !(data instanceof CaseInsensitiveFileConfiguration)) {
                data = new CaseInsensitiveFileConfiguration(data);
            } else if (!ignoreCase && data instanceof CaseInsensitiveFileConfiguration) {
                data = ((CaseInsensitiveFileConfiguration) data).getDelegate();
            }
        }
    }

    public void createSection(String key) {
        getData().createSection(key);
        saveData();
    }

    /**
     * Gets the data.
     *
     * @return the data
     */
    public FileConfiguration getData() {
        return data;
    }

    /**
     * Gets the d file.
     *
     * @return the d file
     */
    public File getdFile() {
        return dFile;
    }

    public boolean isJustCreated() {
        return created;
    }

    public void loadValues() {

    }

    /**
     * On file creation.
     */
    public abstract void onFileCreation();

    /**
     * Reload data.
     */
    public void reloadData() {
        try {
            // Load raw configuration first
            FileConfiguration loaded = YamlConfiguration.loadConfiguration(dFile);
            failedToRead = false;

            if (loaded.getConfigurationSection("").getKeys(false).size() == 0) {
                failedToRead = true;
                this.data = ignoreCase ? new CaseInsensitiveFileConfiguration(loaded) : loaded;
            } else {
                this.data = ignoreCase ? new CaseInsensitiveFileConfiguration(loaded) : loaded;
                loadValues();
            }
        } catch (Exception e) {
            failedToRead = true;
            e.printStackTrace();
            plugin.getLogger().severe("Failed to load " + dFile.getName());
            scheduler.runTaskAsynchronously(plugin, new Runnable() {

                @Override
                public void run() {
                    plugin.getLogger().severe("Detected failure to load files on startup, see server log for details");
                }
            });
        }
    }

    /**
     * Save data.
     */
    public void saveData() {
        try {
            data.save(dFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Copy all values from the provided configuration into this file's config.
     * Honors the ignoreCase setting because it writes through this.data.
     */
    public void setData(FileConfiguration data) {
        if (this.data == null) {
            // if not yet initialized, just adopt it and wrap if necessary
            this.data = ignoreCase ? new CaseInsensitiveFileConfiguration(data) : data;
            return;
        }

        Map<String, Object> map = data.getConfigurationSection("").getValues(true);
        for (Entry<String, Object> entry : map.entrySet()) {
            this.data.set(entry.getKey(), entry.getValue());
        }
    }

    /**
     * Setup.
     */
    public void setup() {
        failedToRead = false;
        getdFile().getParentFile().mkdirs();

        if (!dFile.exists()) {
            try {
                getdFile().createNewFile();
                onFileCreation();
                created = true;
            } catch (IOException e) {
                Bukkit.getServer().getLogger()
                        .severe(ChatColor.RED + "Could not create " + getdFile().getName() + "!");
            }
        }

        try {
            // Load raw configuration first
            FileConfiguration loaded = YamlConfiguration.loadConfiguration(dFile);
            if (loaded.getConfigurationSection("").getKeys(false).size() == 0) {
                failedToRead = true;
            }
            this.data = ignoreCase ? new CaseInsensitiveFileConfiguration(loaded) : loaded;
            loadValues();

        } catch (Exception e) {
            failedToRead = true;
            e.printStackTrace();
            plugin.getLogger().severe("Failed to load " + dFile.getName());
            scheduler.runTaskAsynchronously(plugin, new Runnable() {

                @Override
                public void run() {
                    plugin.getLogger().severe("Detected failure to load files on startup, see server log for details");
                }
            });
        }
    }

    public void setValue(String path, Object value) {
        getData().set(path, value);
        saveData();
    }

    // ========================================================================
    // Case-insensitive wrapper implementation
    // ========================================================================

    /**
     * Wrapper FileConfiguration that resolves all paths case-insensitively, but
     * delegates storage and saving to a real FileConfiguration instance.
     *
     * Designed to be transparent to existing code that calls getData().
     */
    private static class CaseInsensitiveFileConfiguration extends YamlConfiguration {

        private final FileConfiguration delegate;

        CaseInsensitiveFileConfiguration(FileConfiguration delegate) {
            this.delegate = delegate;
        }

        public FileConfiguration getDelegate() {
            return delegate;
        }

        // -------------------------------
        // Path resolution helpers
        // -------------------------------

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

        /**
         * Resolve a path like "Rewards.Commands.Console" into the real path used in
         * the underlying delegate, ignoring case on each segment.
         *
         * If any segment cannot be resolved, returns the original path.
         */
        private String resolvePath(String path) {
            if (path == null || path.isEmpty()) {
                return path;
            }

            String[] parts = path.split("\\.");
            ConfigurationSection current = delegate;
            StringBuilder resolved = new StringBuilder();

            for (int i = 0; i < parts.length; i++) {
                String part = parts[i];
                String realKey = findRealKey(current, part);
                if (realKey == null) {
                    // fall back to original path if something doesn't match
                    return path;
                }

                if (i > 0) {
                    resolved.append('.');
                }
                resolved.append(realKey);

                if (i < parts.length - 1) {
                    current = current.getConfigurationSection(realKey);
                    if (current == null) {
                        // cannot go deeper, bail out
                        return path;
                    }
                }
            }

            return resolved.toString();
        }

        // -------------------------------
        // Delegated core methods
        // -------------------------------

        @Override
        public Object get(String path) {
            String real = resolvePath(path);
            return delegate.get(real);
        }

        @Override
        public Object get(String path, Object def) {
            String real = resolvePath(path);
            return delegate.get(real, def);
        }

        @Override
        public void set(String path, Object value) {
            String real = resolvePath(path);
            delegate.set(real, value);
        }

        @Override
        public boolean contains(String path) {
            String real = resolvePath(path);
            return delegate.contains(real);
        }

        @Override
        public boolean isSet(String path) {
            String real = resolvePath(path);
            return delegate.isSet(real);
        }

        @Override
        public ConfigurationSection getConfigurationSection(String path) {
            if (path == null || path.isEmpty()) {
                return delegate.getConfigurationSection(path);
            }
            String real = resolvePath(path);
            return delegate.getConfigurationSection(real);
        }

        @Override
        public ConfigurationSection createSection(String path) {
            String real = resolvePath(path);
            return delegate.createSection(real);
        }

        @Override
        public Set<String> getKeys(boolean deep) {
            return delegate.getKeys(deep);
        }

        @Override
        public Map<String, Object> getValues(boolean deep) {
            return delegate.getValues(deep);
        }

        // -------------------------------
        // Typed getters
        // -------------------------------

        @Override
        public String getString(String path) {
            String real = resolvePath(path);
            return delegate.getString(real);
        }

        @Override
        public String getString(String path, String def) {
            String real = resolvePath(path);
            return delegate.getString(real, def);
        }

        @Override
        public int getInt(String path) {
            String real = resolvePath(path);
            return delegate.getInt(real);
        }

        @Override
        public int getInt(String path, int def) {
            String real = resolvePath(path);
            return delegate.getInt(real, def);
        }

        @Override
        public long getLong(String path) {
            String real = resolvePath(path);
            return delegate.getLong(real);
        }

        @Override
        public long getLong(String path, long def) {
            String real = resolvePath(path);
            return delegate.getLong(real, def);
        }

        @Override
        public double getDouble(String path) {
            String real = resolvePath(path);
            return delegate.getDouble(real);
        }

        @Override
        public double getDouble(String path, double def) {
            String real = resolvePath(path);
            return delegate.getDouble(real, def);
        }

        @Override
        public boolean getBoolean(String path) {
            String real = resolvePath(path);
            return delegate.getBoolean(real);
        }

        @Override
        public boolean getBoolean(String path, boolean def) {
            String real = resolvePath(path);
            return delegate.getBoolean(real, def);
        }

        @Override
        public List<?> getList(String path) {
            String real = resolvePath(path);
            return delegate.getList(real);
        }

        @Override
        public List<String> getStringList(String path) {
            String real = resolvePath(path);
            return delegate.getStringList(real);
        }

        // -------------------------------
        // Saving / loading
        // -------------------------------

        @Override
        public String saveToString() {
            if (delegate instanceof YamlConfiguration) {
                return ((YamlConfiguration) delegate).saveToString();
            }
            // fallback: use super if delegate isn't a YamlConfiguration
            return super.saveToString();
        }

        @Override
        public void loadFromString(String contents) throws org.bukkit.configuration.InvalidConfigurationException {
            if (delegate instanceof YamlConfiguration) {
                ((YamlConfiguration) delegate).loadFromString(contents);
            } else {
                super.loadFromString(contents);
            }
        }

        @Override
        public void save(File file) throws IOException {
            if (delegate instanceof YamlConfiguration) {
                ((YamlConfiguration) delegate).save(file);
            } else {
                super.save(file);
            }
        }

        @Override
        public void save(String file) throws IOException {
            if (delegate instanceof YamlConfiguration) {
                ((YamlConfiguration) delegate).save(file);
            } else {
                super.save(file);
            }
        }
    }
}
