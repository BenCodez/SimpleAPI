package com.bencodez.simpleapi.tests.shared;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.BasicConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import com.bencodez.simpleapi.bukkit.config.BukkitStructuredConfigView;
import com.bencodez.simpleapi.core.config.AnnotationBinder;
import com.bencodez.simpleapi.core.config.CaseInsensitiveConfigView;
import com.bencodez.simpleapi.core.config.ConfigurateStructuredConfigView;
import com.bencodez.simpleapi.core.config.StructuredConfigView;
import com.bencodez.simpleapi.core.config.StructuredConfigView.Kind;
import com.bencodez.simpleapi.file.CaseInsensitiveSection;
import com.bencodez.simpleapi.file.annotation.ConfigDataInt;
import com.bencodez.simpleapi.file.annotation.ConfigDataListString;

class StructuredConfigViewTest {
    @Test void distinguishesRewardReferencesListsAndInlineSectionsWithoutCoercion() throws Exception {
        var yaml = YamlConfigurationLoader.builder().buildAndLoadString("""
            single: reward-one
            multiple: [reward-one, reward-two]
            empty: []
            inline:
              Commands: [say voted]
            flag: false
            amount: 0
            quoted: '12'
            """);
        var bukkit = new MemoryConfiguration();
        bukkit.set("single", "reward-one");
        bukkit.set("multiple", List.of("reward-one", "reward-two"));
        bukkit.set("empty", List.of());
        bukkit.createSection("inline").set("Commands", List.of("say voted"));
        bukkit.set("flag", false); bukkit.set("amount", 0); bukkit.set("quoted", "12");
        for (StructuredConfigView view : List.of(new ConfigurateStructuredConfigView(yaml), new BukkitStructuredConfigView(bukkit))) {
            assertEquals(Kind.MISSING, view.kind("missing"));
            assertEquals(Kind.STRING, view.kind("single"));
            assertEquals(Kind.LIST, view.kind("multiple"));
            assertEquals(Kind.LIST, view.kind("empty"));
            assertEquals(Kind.SECTION, view.kind("inline"));
            assertEquals(Kind.BOOLEAN, view.kind("flag"));
            assertEquals(Kind.NUMBER, view.kind("amount"));
            assertEquals(Kind.STRING, view.kind("quoted"));
            assertEquals(99, view.getInt("quoted", 99));
            assertEquals(List.of(), view.value("empty"));
            assertNull(view.value("missing"));
            assertNull(view.at("single"));
            assertEquals(Map.of("Commands", List.of("say voted")), view.value("inline"));
            assertEquals(Kind.SECTION, view.kindAt());
            assertEquals(view.snapshotValues(), view.valueAt());
        }
    }

    @Test void caseInsensitiveReadsMatchTheExistingBukkitWrapperIncludingCollisions() {
        var nativeConfig = new MemoryConfiguration();
        nativeConfig.set("Commands.Console", List.of("say first"));
        nativeConfig.set("COMMANDS.Console", List.of("say second"));
        nativeConfig.set("Requirements.Enabled", false);
        nativeConfig.set("Requirements.Amount", 3);
        var legacy = new CaseInsensitiveSection(nativeConfig);
        var view = new CaseInsensitiveConfigView(new BukkitStructuredConfigView(nativeConfig));
        for (String path : List.of("commands.console", "COMMANDS.Console", "Commands.Console.", "missing")) {
            assertEquals(legacy.getStringList(path), view.getStringList(path));
            assertEquals(legacy.contains(path), view.contains(path));
        }
        assertEquals(List.of("say first"), view.getStringList("COMMANDS.Console"));
        assertEquals(legacy.getBoolean("requirements.enabled", true), view.getBoolean("requirements.enabled", true));
        assertEquals(legacy.getInt("requirements.amount", 9), view.getInt("requirements.amount", 9));
        assertEquals(legacy.getLong("requirements.amount", 9L), view.getLong("requirements.amount", 9L));
        assertEquals(legacy.getDouble("requirements.amount", 9.0), view.getDouble("requirements.amount", 9.0));
        assertEquals(legacy.getKeys(true), view.getKeys(true));
        assertEquals(Kind.LIST, view.kind("commands.console"));
        assertEquals(List.of("say first"), view.value("commands.console"));
        assertEquals(Set.of("Console"), view.at("COMMANDS").getKeys(false));
    }

    @Test void literalKeysWorkWithoutChangingPathSeparators() {
        var node = BasicConfigurationNode.root();
        node.node("Sites", "example.site", "Commands").raw(List.of("say voted"));
        var nativeConfig = new MemoryConfiguration();
        nativeConfig.options().pathSeparator('/');
        nativeConfig.createSection("Sites/example.site").set("Commands", List.of("say voted"));
        for (StructuredConfigView view : List.of(new ConfigurateStructuredConfigView(node, '/'), new BukkitStructuredConfigView(nativeConfig))) {
            assertEquals(List.of("say voted"), view.valueAt("Sites", "example.site", "Commands"));
            assertEquals(Kind.LIST, view.kindAt("Sites", "example.site", "Commands"));
            assertEquals(List.of("say voted"), view.at("Sites", "example.site").getStringList("Commands"));
            var insensitive = new CaseInsensitiveConfigView(view, '/');
            assertEquals(List.of("say voted"), insensitive.valueAt("sites", "EXAMPLE.SITE", "commands"));
            assertEquals(List.of("say voted"), insensitive.getStringList("sites/EXAMPLE.SITE/commands"));
        }
        assertEquals('/', nativeConfig.options().pathSeparator());
    }

    @SuppressWarnings("unchecked")
    @Test void exportsAreDetachedUnmodifiableAndRetainOriginalKeyCasing() {
        var nativeConfig = new MemoryConfiguration();
        var commands = new ArrayList<>(List.of("say original"));
        nativeConfig.createSection("Rewards").set("Commands", commands);
        var view = new CaseInsensitiveConfigView(new BukkitStructuredConfigView(nativeConfig));
        Map<String, Object> snapshot = view.snapshotValues();
        commands.add("say later");
        nativeConfig.set("Other", true);
        var rewards = (Map<String, Object>) snapshot.get("Rewards");
        assertEquals(List.of("say original"), rewards.get("Commands"));
        assertFalse(snapshot.containsKey("Other"));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.put("bad", true));
        assertThrows(UnsupportedOperationException.class, () -> ((List<Object>) rewards.get("Commands")).add("bad"));
    }

    @Test void defaultsAndMixedListsKeepBackingGetterSemantics() {
        var defaults = new MemoryConfiguration(); defaults.set("Amount", 12);
        var nativeConfig = new MemoryConfiguration(defaults);
        nativeConfig.set("Values", Arrays.asList("1", 2, 3.5, true, "bad"));
        var view = new BukkitStructuredConfigView(nativeConfig);
        assertEquals(nativeConfig.getInt("Amount", -1), view.getInt("Amount", -1));
        assertEquals(Kind.NUMBER, view.kind("Amount"));
        assertEquals(nativeConfig.get("Amount"), view.valueAt("Amount"));
        assertEquals(nativeConfig.getIntegerList("Values"), view.getIntegerList("Values"));
        assertEquals(nativeConfig.getStringList("Values"), view.getStringList("Values"));
        assertEquals(nativeConfig.getKeys(false), view.snapshotValues().keySet());
        var oldCase = new CaseInsensitiveSection(nativeConfig);
        var newCase = new CaseInsensitiveConfigView(view);
        assertEquals(oldCase.getInt("amount", -1), newCase.getInt("amount", -1));
    }

    @Test void rejectsNativeObjectsInsteadOfSilentlySerializingThem() {
        var nativeConfig = new MemoryConfiguration();
        Object opaque = new Object(); nativeConfig.set("Rewards.Native", opaque);
        var view = new BukkitStructuredConfigView(nativeConfig);
        assertEquals(Kind.OTHER, view.kind("Rewards.Native"));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, view::snapshotValues);
        assertTrue(failure.getMessage().contains("Native"));
        assertSame(opaque, nativeConfig.get("Rewards.Native"));
    }

    @Test void configurateSnapshotsPreserveNumericKeysAndRejectAmbiguousKeys() throws Exception {
        var node = YamlConfigurationLoader.builder().buildAndLoadString("rewards:\n  10:\n    Commands: [say ten]\n");
        var view = new ConfigurateStructuredConfigView(node);
        assertEquals(List.of("say ten"), view.valueAt("rewards", "10", "Commands"));
        assertEquals(Set.of("10"), view.at("rewards").snapshotValues().keySet());
        var ambiguous = new LinkedHashMap<Object, Object>();
        ambiguous.put(10, "number"); ambiguous.put("10", "text");
        node.node("bad").raw(ambiguous);
        assertThrows(IllegalArgumentException.class, view::snapshotValues);
    }

    static class RewardOptions {
        @ConfigDataInt(path = "Points") int points = 7;
        @ConfigDataListString(path = "Commands") ArrayList<String> commands = new ArrayList<>(List.of("fallback"));
    }

    @Test void sharedBinderUsesTheNewViewWithoutNewAnnotationRules() throws Exception {
        var node = YamlConfigurationLoader.builder().buildAndLoadString("points: 0\ncommands: [say voted]\n");
        var view = new CaseInsensitiveConfigView(new ConfigurateStructuredConfigView(node));
        var target = new RewardOptions();
        new AnnotationBinder().load(view, target);
        assertEquals(0, target.points);
        assertEquals(List.of("say voted"), target.commands);
        assertEquals(Map.of("points", 0, "commands", List.of("say voted")), view.snapshotValues());
    }
}
