package com.bencodez.simpleapi.tests.file.config;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.bencodez.simpleapi.file.annotation.AnnotationBinder;
import com.bencodez.simpleapi.file.annotation.ConfigDataConfigurationSection;
import com.bencodez.simpleapi.file.annotation.ConfigDataInt;
import com.bencodez.simpleapi.file.annotation.ConfigDataKeys;
import com.bencodez.simpleapi.file.annotation.ConfigDataParsedDuration;
import com.bencodez.simpleapi.file.annotation.ConfigDataString;
import com.bencodez.simpleapi.file.config.ConfigView;
import com.bencodez.simpleapi.time.ParsedDuration;

/** Executed in a separate class loader that has no Bukkit or test libraries. */
public final class HeadlessBindingFixture {

    private HeadlessBindingFixture() {
    }

    public static final class Values {
        @ConfigDataString(path = "message", secondPath = "legacy-message")
        private String message;
        @ConfigDataInt(path = "votes")
        private int votes;
        @ConfigDataConfigurationSection(path = "rewards")
        private ConfigView rewards;
        @ConfigDataKeys(path = "rewards")
        private Set<String> keys;
        @ConfigDataParsedDuration(path = "delay", defaultTimeUnit = TimeUnit.SECONDS)
        private ParsedDuration delay;
    }

    public static void run() {
        ConfigView rewards = new TestView(Map.of("command", "say voted"));
        ConfigView config = new TestView(Map.of("legacy-message", "thanks", "votes", 12,
                "rewards", rewards, "delay", "3"));
        Values values = new Values();
        new AnnotationBinder().load(config, values);
        if (!"thanks".equals(values.message) || values.votes != 12 || values.rewards != rewards
                || !Set.of("command").equals(values.keys) || values.delay == null
                || values.delay.getMillis() != 3_000L) {
            throw new AssertionError("Headless annotation binding failed");
        }
    }

    // A deliberately small in-memory test implementation, not a new YAML parser.
    private static final class TestView implements ConfigView {
        private final Map<String, ?> values;

        private TestView(Map<String, ?> values) {
            this.values = values;
        }

        @Override
        public boolean contains(String path) {
            return values.containsKey(path);
        }

        @Override
        public String getString(String path, String fallback) {
            Object value = values.get(path);
            return value == null ? fallback : value.toString();
        }

        @Override
        public boolean getBoolean(String path, boolean fallback) {
            Object value = values.get(path);
            return value instanceof Boolean ? (Boolean) value : fallback;
        }

        @Override
        public int getInt(String path, int fallback) {
            Object value = values.get(path);
            return value instanceof Number ? ((Number) value).intValue() : fallback;
        }

        @Override
        public long getLong(String path, long fallback) {
            Object value = values.get(path);
            return value instanceof Number ? ((Number) value).longValue() : fallback;
        }

        @Override
        public double getDouble(String path, double fallback) {
            Object value = values.get(path);
            return value instanceof Number ? ((Number) value).doubleValue() : fallback;
        }

        @Override
        public List<String> getStringList(String path) {
            return List.of();
        }

        @Override
        public List<Integer> getIntegerList(String path) {
            return List.of();
        }

        @Override
        public boolean isConfigurationSection(String path) {
            return values.get(path) instanceof ConfigView;
        }

        @Override
        public ConfigView getConfigurationSection(String path) {
            Object value = values.get(path);
            return value instanceof ConfigView ? (ConfigView) value : null;
        }

        @Override
        public Set<String> getKeys(boolean deep) {
            return values.keySet();
        }
    }
}
