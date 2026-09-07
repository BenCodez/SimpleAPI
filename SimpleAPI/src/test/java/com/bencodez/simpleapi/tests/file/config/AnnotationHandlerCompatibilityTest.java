package com.bencodez.simpleapi.tests.file.config;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.Test;

import com.bencodez.simpleapi.file.annotation.*;
import com.bencodez.simpleapi.file.config.bukkit.BukkitConfigView;
import com.bencodez.simpleapi.time.ParsedDuration;

class AnnotationHandlerCompatibilityTest {

    private final AnnotationHandler handler = new AnnotationHandler();

    static class Scalars {
        @ConfigDataString(path = "string", secondPath = "old.string")
        private String string = "initial";
        @ConfigDataBoolean(path = "bool", secondPath = "old.bool", secondPathInvert = true)
        private boolean bool = true;
        @ConfigDataInt(path = "integer", secondPath = "old.integer")
        private int integer = 7;
        @ConfigDataLong(path = "long", secondPath = "old.long")
        private long number = 99L;
        @ConfigDataDouble(path = "double", secondPath = "old.double")
        private double decimal = 1.5;
    }

    @Test
    void preservesPublicConstructorAndUnambiguousLegacySignature() throws Exception {
        assertNotNull(AnnotationHandler.class.getConstructor().newInstance());
        Method load = AnnotationHandler.class.getMethod("load", ConfigurationSection.class, Object.class);
        assertEquals(void.class, load.getReturnType());
        assertEquals(1L, Arrays.stream(AnnotationHandler.class.getMethods())
                .filter(method -> method.getName().equals("load")).count());
        // Compile-time regression guard: a ConfigView overload would make this ambiguous.
        assertDoesNotThrow(() -> handler.load(null, new Object()));
    }

    @Test
    void preservesInitializedDefaultsIncludingHistoricalLongBehavior() {
        Scalars target = new Scalars();
        handler.load(new MemoryConfiguration(), target);
        assertEquals("initial", target.string);
        assertTrue(target.bool);
        assertEquals(7, target.integer);
        // Existing Field.getInt(longField) fails and leaves the annotation default at zero.
        // Fixing this would be a separate behavior change, not part of the extraction.
        assertEquals(0L, target.number);
        assertEquals(1.5, target.decimal);
    }

    @Test
    void honorsExplicitZeroFalseAndEmptyPrimaryValues() {
        MemoryConfiguration config = new MemoryConfiguration();
        config.set("string", "");
        config.set("bool", false);
        config.set("integer", 0);
        config.set("long", 0L);
        config.set("double", 0.0);
        config.set("old.string", "fallback");
        config.set("old.bool", false);
        config.set("old.integer", 100);
        Scalars target = new Scalars();
        handler.load(config, target);
        assertEquals("", target.string);
        assertFalse(target.bool);
        assertEquals(0, target.integer);
        assertEquals(0L, target.number);
        assertEquals(0.0, target.decimal);
    }

    @Test
    void usesAlternatePathsAndInvertsOnlyTheAlternateBoolean() {
        MemoryConfiguration config = new MemoryConfiguration();
        config.set("old.string", "alternate");
        config.set("old.bool", true);
        config.set("old.integer", 12);
        config.set("old.long", 4_000_000_000L);
        config.set("old.double", 2.25);
        Scalars target = new Scalars();
        handler.load(config, target);
        assertEquals("alternate", target.string);
        assertFalse(target.bool);
        assertEquals(12, target.integer);
        assertEquals(4_000_000_000L, target.number);
        assertEquals(2.25, target.decimal);
        config.set("bool", true);
        handler.load(config, target);
        assertTrue(target.bool);
    }

    @Test
    void retainsBukkitTypeCoercionAndPresentButInvalidBooleanPrecedence() {
        MemoryConfiguration config = new MemoryConfiguration();
        config.set("string", 42);
        config.set("bool", "not-a-boolean");
        config.set("old.bool", true);
        config.set("integer", "invalid");
        config.set("old.integer", 18);
        config.set("long", 123);
        config.set("double", 4);
        Scalars target = new Scalars();
        handler.load(config, target);
        assertEquals(config.getString("string", "initial"), target.string);
        assertTrue(target.bool); // contains(primary) wins; no alternate inversion.
        assertEquals(18, target.integer);
        assertEquals(123L, target.number);
        assertEquals(4.0, target.decimal);
    }

    static class ExplicitDefaults {
        @ConfigDataString(path = "missing", defaultValue = "annotation")
        String text = "field";
        @ConfigDataInt(path = "missing", defaultValue = 12)
        int integer = 7;
        @ConfigDataLong(path = "missing", defaultValue = 123L)
        long number = 99L;
        @ConfigDataDouble(path = "missing", defaultValue = 2.5)
        double decimal = 1.5;
        @ConfigDataBoolean(path = "missing", defaultValue = true)
        boolean bool;
    }

    @Test
    void preservesExplicitAnnotationDefaults() {
        ExplicitDefaults target = new ExplicitDefaults();
        handler.load(new MemoryConfiguration(), target);
        assertEquals("annotation", target.text);
        assertEquals(12, target.integer);
        assertEquals(123L, target.number);
        assertEquals(2.5, target.decimal);
        assertTrue(target.bool);
    }

    static class Lists {
        @ConfigDataListString(path = "strings", secondPath = "old.strings")
        ArrayList<String> strings = new ArrayList<>(List.of("initial"));
        @ConfigDataListInt(path = "integers", secondPath = "old.integers")
        ArrayList<Integer> integers = new ArrayList<>(List.of(7));
    }

    @Test
    void preservesEmptyListFallbackAndInitializedListIdentity() {
        MemoryConfiguration config = new MemoryConfiguration();
        config.set("strings", List.of());
        config.set("integers", List.of());
        Lists target = new Lists();
        ArrayList<String> strings = target.strings;
        ArrayList<Integer> integers = target.integers;
        handler.load(config, target);
        assertSame(strings, target.strings);
        assertSame(integers, target.integers);
        config.set("old.strings", List.of("alternate"));
        config.set("old.integers", List.of(11));
        handler.load(config, target);
        assertEquals(List.of("alternate"), target.strings);
        assertEquals(List.of(11), target.integers);
        assertNotSame(strings, target.strings);
        assertNotSame(integers, target.integers);
    }

    @Test
    void delegatesMixedListConversionToBukkit() {
        MemoryConfiguration config = new MemoryConfiguration();
        config.set("strings", Arrays.asList("one", 2, true, null, List.of("ignored")));
        config.set("integers", Arrays.asList(1, "2", 3.5, 'A', false, null, "bad"));
        Lists target = new Lists();
        handler.load(config, target);
        assertEquals(config.getStringList("strings"), target.strings);
        assertEquals(config.getIntegerList("integers"), target.integers);
    }

    static class Sections {
        @ConfigDataConfigurationSection(path = "rewards", secondPath = "old.rewards")
        ConfigurationSection section;
        @ConfigDataConfigurationSection(path = "rewards", secondPath = "old.rewards")
        Object object;
        @ConfigDataKeys(path = "rewards", secondPath = "old.rewards")
        Set<String> keys = Set.of("initial");
    }

    @Test
    void keepsNativeSectionIdentityForTypedAndObjectFields() {
        MemoryConfiguration config = new MemoryConfiguration();
        ConfigurationSection section = config.createSection("rewards");
        section.set("first.command", "say first");
        section.set("second", "say second");
        Sections target = new Sections();
        handler.load(config, target);
        assertSame(section, target.section);
        assertSame(section, target.object);
        assertEquals(new ArrayList<>(section.getKeys(false)), new ArrayList<>(target.keys));
        target.section.set("edited", true);
        assertTrue(config.getBoolean("rewards.edited"));
    }

    @Test
    void supportsAlternateSectionsAndClearsAbsentSectionsAndKeys() {
        MemoryConfiguration config = new MemoryConfiguration();
        ConfigurationSection section = config.createSection("old.rewards");
        section.set("command", "say alternate");
        Sections target = new Sections();
        handler.load(config, target);
        assertSame(section, target.section);
        assertSame(section, target.object);
        assertEquals(Set.of("command"), target.keys);
        config.set("old.rewards", null);
        handler.load(config, target);
        assertNull(target.section);
        assertNull(target.object);
        assertTrue(target.keys.isEmpty());
    }

    static class RootSection {
        @ConfigDataConfigurationSection(path = "")
        Object root;
    }

    @Test
    void retainsRootSectionIdentity() {
        MemoryConfiguration config = new MemoryConfiguration();
        RootSection target = new RootSection();
        handler.load(config, target);
        assertSame(config, target.root);
    }

    @Test
    void retainsConfiguredDefaultsAndPathSeparatorThroughTheLiveAdapter() {
        MemoryConfiguration defaults = new MemoryConfiguration();
        defaults.set("bool", false);
        MemoryConfiguration config = new MemoryConfiguration(defaults);
        config.options().pathSeparator('/');
        config.set("nested/value", 25);
        BukkitConfigView view = new BukkitConfigView(config);
        assertEquals(config.contains("bool"), view.contains("bool"));
        assertEquals(config.getBoolean("bool", true), view.getBoolean("bool", true));
        assertEquals(25, view.getInt("nested/value", 0));
        assertEquals(config.getKeys(true), view.getKeys(true));
        config.set("nested/value", 26);
        assertEquals(26, view.getInt("nested/value", 0));
        assertSame(config.getConfigurationSection("nested"), view.getConfigurationSection("nested").getSection());
        assertNull(view.getConfigurationSection("absent"));
    }

    static class Parent {
        @ConfigDataInt(path = "value")
        int inherited = 3;
    }

    static class Child extends Parent {
        @ConfigDataInt(path = "value")
        private int declared = 4;
    }

    @Test
    void retainsDeclaredFieldsOnlyTraversal() {
        MemoryConfiguration config = new MemoryConfiguration();
        config.set("value", 20);
        Child target = new Child();
        handler.load(config, target);
        assertEquals(3, target.inherited);
        assertEquals(20, target.declared);
    }

    static class InvalidField {
        @ConfigDataString(path = "text")
        @ConfigDataInt(path = "number")
        int invalid = 5;
        @ConfigDataInt(path = "number")
        int valid;
    }

    @Test
    void isolatesFieldErrorsWithoutChangingAnnotationOrder() {
        MemoryConfiguration config = new MemoryConfiguration();
        config.set("text", "not an integer");
        config.set("number", 10);
        InvalidField target = new InvalidField();
        assertDoesNotThrow(() -> handler.load(config, target));
        assertEquals(5, target.invalid); // String runs first; the field's remaining annotations are skipped.
        assertEquals(10, target.valid);
    }

    static class Durations {
        @ConfigDataParsedDuration(path = "delay", secondPath = "old.delay", defaultTimeUnit = TimeUnit.SECONDS)
        ParsedDuration duration;
        @ConfigDataParsedDuration(path = "missing", defaultValue = "2s", defaultTimeUnit = TimeUnit.SECONDS)
        ParsedDuration fallback;
    }

    @Test
    void keepsDurationParsingAndAlternatePaths() {
        MemoryConfiguration config = new MemoryConfiguration();
        config.set("old.delay", "3");
        Durations target = new Durations();
        handler.load(config, target);
        assertEquals(3_000L, target.duration.getMillis());
        assertEquals(2_000L, target.fallback.getMillis());
    }

    @Test
    void retainsNullTargetFailure() {
        assertThrows(NullPointerException.class, () -> handler.load(new MemoryConfiguration(), null));
        assertThrows(NullPointerException.class, () -> handler.load(null, null));
    }
}
