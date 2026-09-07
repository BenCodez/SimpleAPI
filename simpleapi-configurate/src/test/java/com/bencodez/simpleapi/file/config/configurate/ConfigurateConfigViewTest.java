package com.bencodez.simpleapi.file.config.configurate;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.BasicConfigurationNode;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;
import com.bencodez.simpleapi.file.annotation.*;
import com.bencodez.simpleapi.file.config.ConfigView;
import com.bencodez.simpleapi.time.ParsedDuration;

class ConfigurateConfigViewTest {
    @Test void scalarReadsPreserveTypesAndExplicitDefaults() {
        ConfigurationNode root = BasicConfigurationNode.root();
        root.node("text").raw(42);
        root.node("trueString").raw("true");
        root.node("numberString").raw("12");
        root.node("flag").raw(false);
        root.node("empty").raw("");
        root.node("zero").raw(0);
        root.node("large").raw(4_000_000_000L);
        root.node("decimal").raw(2.9);
        ConfigView view = new ConfigurateConfigView(root);
        assertEquals("42", view.getString("text", "fallback"));
        assertFalse(view.getBoolean("trueString", false));
        assertEquals(99, view.getInt("numberString", 99));
        assertFalse(view.getBoolean("flag", true));
        assertEquals("", view.getString("empty", "fallback"));
        assertEquals(0, view.getInt("zero", 99));
        assertEquals(4_000_000_000L, view.getLong("large", -1));
        assertEquals(2, view.getInt("decimal", -1));
        assertEquals(2.9, view.getDouble("decimal", -1));
        assertEquals(99L, view.getLong("missing", 99));
    }

    @Test void missingReadsDoNotCreateNodes() {
        ConfigurationNode root = BasicConfigurationNode.root();
        ConfigView view = new ConfigurateConfigView(root);
        assertFalse(view.contains("missing"));
        assertNull(view.getConfigurationSection("missing.child"));
        assertEquals(List.of(), view.getStringList("missing.list"));
        assertEquals(List.of(), view.getIntegerList("missing.list"));
        assertEquals(Set.of(), view.getKeys(true));
        assertTrue(root.childrenMap().isEmpty());
        assertTrue(view.contains(""));
        assertNotNull(view.getConfigurationSection(""));
    }

    @Test void listsUseBukkitCompatibleElementConversions() {
        ConfigurationNode root = BasicConfigurationNode.root();
        root.node("list").raw(Arrays.asList("2", 3, 4.75, true, Map.of("nested", 1), List.of("nested"), "bad", " 5"));
        ConfigView view = new ConfigurateConfigView(root);
        assertEquals(List.of("2", "3", "4.75", "true", "bad", " 5"), view.getStringList("list"));
        assertEquals(List.of(2, 3, 4), view.getIntegerList("list"));
        List<String> copy = view.getStringList("list");
        copy.clear();
        assertEquals(6, view.getStringList("list").size());
    }

    @Test void sectionsKeysAndLiteralSegmentsAreDistinct() {
        ConfigurationNode root = BasicConfigurationNode.root();
        root.node("sites", "some.site", "reward").raw("say voted");
        root.node("sites", "other").raw("url");
        ConfigurateConfigView view = new ConfigurateConfigView(root);
        assertNull(view.getConfigurationSection("sites.some.site"));
        assertEquals("say voted", view.at("sites", "some.site").getString("reward", ""));
        assertEquals(Set.of("sites"), view.getKeys(false));
        assertEquals(Set.of("sites", "sites.some.site", "sites.some.site.reward", "sites.other"), view.getKeys(true));
        assertNull(view.getConfigurationSection("sites.other"));
        assertEquals("say voted", new ConfigurateConfigView(root, '/').getString("sites/some.site/reward", ""));
    }

    @Test void numericYamlKeysCanBeReadUsingStringPaths() throws Exception {
        ConfigurationNode root = YamlConfigurationLoader.builder().buildAndLoadString("rewards:\n  10:\n    command: hello\n");
        ConfigurateConfigView view = new ConfigurateConfigView(root);
        assertEquals("hello", view.getString("rewards.10.command", ""));
        assertEquals(Set.of("10"), view.getConfigurationSection("rewards").getKeys(false));
    }

    static class Values {
        @ConfigDataString(path="message", secondPath="old.message") String message="initial";
        @ConfigDataInt(path="votes") int votes=3;
        @ConfigDataBoolean(path="enabled", secondPath="disabled", secondPathInvert=true) boolean enabled;
        @ConfigDataListString(path="commands", secondPath="old.commands") ArrayList<String> commands=new ArrayList<>(List.of("default"));
        @ConfigDataConfigurationSection(path="rewards") ConfigView rewards;
        @ConfigDataKeys(path="rewards") Set<String> keys;
        @ConfigDataParsedDuration(path="delay") ParsedDuration delay;
    }

    @Test void realYamlFeedsTheSharedBinderWithoutBukkit() throws Exception {
        ConfigurationNode root = YamlConfigurationLoader.builder().buildAndLoadString("""
            old:
              message: thanks
              commands: [say voted]
            votes: 0
            disabled: false
            commands: []
            rewards:
              first: command
            delay: 3s
            """);
        Values target = new Values();
        new AnnotationBinder().load(new ConfigurateConfigView(root), target);
        assertEquals("thanks", target.message);
        assertEquals(0, target.votes);
        assertTrue(target.enabled);
        assertEquals(List.of("say voted"), target.commands);
        assertEquals("command", target.rewards.getString("first", ""));
        assertEquals(Set.of("first"), target.keys);
        assertEquals(3000L, target.delay.getMillis());
        assertThrows(ClassNotFoundException.class, () -> Class.forName("org.bukkit.configuration.ConfigurationSection"));
    }

    @Test void adapterRejectsNullArguments() {
        assertThrows(NullPointerException.class, () -> new ConfigurateConfigView(null));
        ConfigurateConfigView view=new ConfigurateConfigView(BasicConfigurationNode.root());
        assertThrows(NullPointerException.class, () -> view.getInt(null, 1));
    }
}
