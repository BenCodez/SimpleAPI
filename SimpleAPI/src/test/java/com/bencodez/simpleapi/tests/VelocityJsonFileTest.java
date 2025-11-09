package com.bencodez.simpleapi.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.bencodez.simpleapi.file.velocity.VelocityJSONFile;

import lombok.var;

class VelocityJSONFileTest {

    @TempDir
    Path tmpRoot;

    @Test
    @DisplayName("Constructor creates empty config and file")
    void fileCreationCreatesEmptyConfigAndFileExists() throws Exception {
        Path file = tmpRoot.resolve("config.json");
        Files.deleteIfExists(file);

        VelocityJSONFile v = new VelocityJSONFile(file);

        assertNotNull(v.getData());
        assertTrue(Files.exists(file));
    }

    @Test
    @DisplayName("save() then reload persists values")
    void saveAndReloadPersistsValues() throws Exception {
        Path file = tmpRoot.resolve("config.json");
        VelocityJSONFile writer = new VelocityJSONFile(file);

        writer.set(new Object[] { "some", "nested", "key" }, "myValue");
        writer.save();

        VelocityJSONFile reader = new VelocityJSONFile(file);
        String value = reader.getString(reader.getNode("some", "nested", "key"), null);
        assertEquals("myValue", value);
    }

    @Test
    @DisplayName("set/remove affects contains and getNode")
    void setAndRemoveAffectsContainsAndGetNode() throws Exception {
        Path file = tmpRoot.resolve("config.json");
        VelocityJSONFile v = new VelocityJSONFile(file);

        assertFalse(v.contains("will", "exist"));
        v.set(new Object[] { "will", "exist" }, 123);
        assertTrue(v.contains("will", "exist"));
        assertEquals(123, v.getInt(v.getNode("will", "exist"), 0));

        v.remove("will", "exist");
        assertFalse(v.contains("will", "exist"));
    }

    @Test
    @DisplayName("getKeys returns all child keys")
    void getKeysReturnsAllChildKeys() throws Exception {
        Path file = tmpRoot.resolve("config.json");
        VelocityJSONFile v = new VelocityJSONFile(file);

        v.set(new Object[] { "alpha" }, "1");
        v.set(new Object[] { "beta" }, "2");
        v.set(new Object[] { "gamma" }, "3");

        List<String> keys = v.getKeys(v.getData());
        assertTrue(keys.contains("alpha"));
        assertTrue(keys.contains("beta"));
        assertTrue(keys.contains("gamma"));
    }

    @Test
    @DisplayName("save() creates parent directories if missing")
    void createsParentDirectoriesOnSave() throws Exception {
        Path file = tmpRoot.resolve("plugins/votingplugin/nonvotedplayerscache.json");
        // Ensure parents don't exist
        if (Files.exists(file.getParent())) {
            Files.walk(file.getParent()).sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) {}
            });
        }

        VelocityJSONFile v = new VelocityJSONFile(file);
        v.set(new Object[] { "ok" }, true);
        v.save();

        assertTrue(Files.exists(file.getParent()));
        assertTrue(Files.size(file) > 0);
    }

    @Test
    @DisplayName("No .tmp artifacts are left after save()")
    void noTmpFileLeftBehind() throws Exception {
        Path file = tmpRoot.resolve("x/y/z/thing.json");
        VelocityJSONFile v = new VelocityJSONFile(file);
        v.set(new Object[] { "k" }, "v");
        v.save();

        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        assertFalse(Files.exists(tmp));
    }

    @Test
    @DisplayName("Concurrent saves do not throw and file remains readable")
    void concurrentSavesAreSafeEnough() throws Exception {
        Path file = tmpRoot.resolve("conc/config.json");
        VelocityJSONFile v = new VelocityJSONFile(file);

        var pool = Executors.newFixedThreadPool(4);
        try {
            for (int i = 0; i < 20; i++) {
                final int n = i;
                pool.submit(() -> {
                    v.set(new Object[] { "counter" }, n);
                    v.save();
                });
            }
        } finally {
            pool.shutdown();
            Thread.sleep(200); // small settle time
        }

        VelocityJSONFile re = new VelocityJSONFile(file);
        int last = re.getNode("counter").getInt(Integer.MIN_VALUE);
        assertNotEquals(Integer.MIN_VALUE, last);
    }
}
