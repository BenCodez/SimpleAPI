package com.bencodez.simpleapi.tests.shared;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.Arrays;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.spongepowered.configurate.BasicConfigurationNode;
import com.bencodez.simpleapi.file.annotation.ConfigDataConfigurationSection;
import com.bencodez.simpleapi.file.annotation.ConfigDataInt;

class PackageCompatibilityTest {
    @TempDir Path directory;
    static class Values {
        @ConfigDataInt(path="value") int value;
        @ConfigDataConfigurationSection(path="section") ConfigurationSection section;
    }
    @Test void bothBukkitEntryPointsPreserveNativeSectionIdentityAndNullOverload() {
        var configuration = new MemoryConfiguration();
        configuration.set("value", 7);
        var section = configuration.createSection("section");
        var modern = new Values(); var legacy = new Values();
        new com.bencodez.simpleapi.bukkit.config.AnnotationHandler().load(configuration, modern);
        new com.bencodez.simpleapi.file.annotation.AnnotationHandler().load(configuration, legacy);
        assertEquals(modern.value, legacy.value);
        assertSame(section, modern.section); assertSame(section, legacy.section);
        var oldView = new com.bencodez.simpleapi.file.config.bukkit.BukkitConfigView(configuration);
        com.bencodez.simpleapi.file.config.bukkit.BukkitConfigView child = oldView.getConfigurationSection("section");
        assertSame(section, child.getSection());
        assertDoesNotThrow(() -> new com.bencodez.simpleapi.file.annotation.AnnotationHandler().load(null, new Object()));
        assertEquals(1L, Arrays.stream(com.bencodez.simpleapi.file.annotation.AnnotationHandler.class.getMethods())
                .filter(method -> method.getName().equals("load")).count());
    }
    @Test void legacyCovariantSectionsRetainCustomSeparator() {
        var root = BasicConfigurationNode.root();
        root.node("a", "b", "c").raw(9);
        var old = new com.bencodez.simpleapi.file.config.configurate.ConfigurateConfigView(root, '/');
        com.bencodez.simpleapi.file.config.configurate.ConfigurateConfigView child = old.getConfigurationSection("a");
        assertEquals(9, child.getInt("b/c", -1));
        assertInstanceOf(com.bencodez.simpleapi.file.config.configurate.ConfigurateConfigView.class, child.at("b"));
        var core = new com.bencodez.simpleapi.core.config.ConfigurateConfigView(root, '/');
        assertEquals(9, core.getConfigurationSection("a").getInt("b/c", -1));
    }
    @Test void bothDocumentNamesPreserveStateAndLegacySnapshotType() throws Exception {
        var path = directory.resolve("config.yml");
        var core = com.bencodez.simpleapi.core.config.YamlConfigDocument.open(path);
        var saved = core.update("missing", edit -> edit.set("value", 12));
        var legacy = com.bencodez.simpleapi.file.config.configurate.YamlConfigDocument.open(path);
        assertEquals(saved.revision(), legacy.snapshot().revision());
        assertInstanceOf(com.bencodez.simpleapi.file.config.configurate.ConfigurateConfigView.class, legacy.snapshot().view());
        var changed = legacy.update(saved.revision(), edit -> edit.set("value", 13));
        assertInstanceOf(com.bencodez.simpleapi.file.config.configurate.ConfigurateConfigView.class, changed.view());
        assertEquals(13, core.reload().view().getInt("value", -1));
        assertEquals(12, saved.view().getInt("value", -1));
    }
}
