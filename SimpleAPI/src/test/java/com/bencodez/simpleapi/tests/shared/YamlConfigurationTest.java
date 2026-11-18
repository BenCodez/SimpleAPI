package com.bencodez.simpleapi.tests.shared;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.argThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.bencodez.simpleapi.core.config.YamlConfiguration;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;

class YamlConfigurationTest {
    @TempDir Path directory;
    private Path path() { return directory.resolve("settings.yml"); }

    @Test void publicApiSupportsNativeConsumerWithoutConfiguratePlumbing() throws Exception {
        NativeYamlApiFixture.run();
    }

    @Test void existingEmptyFileIsNotReplacedAndUnusedStreamIsClosedWithoutReading() throws Exception {
        Files.writeString(path(), "");
        var defaults = new TrackedStream("Points: 7\n");
        var config = YamlConfiguration.load(path(), defaults);
        assertTrue(defaults.closed);
        assertEquals(defaults.initialSize, defaults.available());
        assertEquals(99, config.getInt("Points", 99));
        assertEquals("", Files.readString(path()));
        assertNotEquals("missing", config.snapshot().revision());
    }

    @Test void invalidDefaultsAndMissingResourcesLeaveMissingFileUntouched() throws Exception {
        for (String invalid : new String[] {"Points: [unterminated", "[1, 2]"}) {
            var stream = new TrackedStream(invalid);
            assertThrows(IOException.class, () -> YamlConfiguration.load(path(), stream));
            assertTrue(stream.closed);
            assertFalse(Files.exists(path()));
        }
        assertThrows(IOException.class, () -> YamlConfiguration.load(path(), getClass(), "/missing.yml"));
        assertFalse(Files.exists(path()));
    }

    @Test void resourceReadAndCloseFailuresDoNotCreateFile() {
        InputStream readFailure = new InputStream() {
            @Override public int read() throws IOException { throw new IOException("synthetic read failure"); }
        };
        assertThrows(IOException.class, () -> YamlConfiguration.load(path(), readFailure));
        InputStream closeFailure = new ByteArrayInputStream("Points: 7\n".getBytes(StandardCharsets.UTF_8)) {
            @Override public void close() throws IOException { throw new IOException("synthetic close failure"); }
        };
        assertThrows(IOException.class, () -> YamlConfiguration.load(path(), closeFailure));
        assertFalse(Files.exists(path()));
    }

    @Test void defaultResourceAndEditedOutputAreBoundedWithoutPublishingChanges() throws Exception {
        var large = new TrackedStream("Points: " + "x".repeat(1024));
        assertThrows(IOException.class, () -> YamlConfiguration.load(path(), large, 64));
        assertTrue(large.closed);
        assertEquals(large.initialSize - 65, large.available(), "Resource read must be bounded");
        assertFalse(Files.exists(path()));
        var config = YamlConfiguration.load(path(), new TrackedStream("Points: 7\n"), 64);
        var before = config.snapshot();
        String bytes = Files.readString(path());
        assertThrows(IOException.class, () -> config.set("Large", "x".repeat(128)));
        assertSame(before, config.snapshot());
        assertEquals(bytes, Files.readString(path()));
        assertThrows(IllegalArgumentException.class, () -> config.set("Native", new Object()));
        assertSame(before, config.snapshot());
        assertEquals(bytes, Files.readString(path()));
    }

    @Test void resourceCannotOverwriteFileCreatedDuringInitialization() {
        InputStream defaults = new ByteArrayInputStream("Points: 7\n".getBytes(StandardCharsets.UTF_8)) {
            @Override public byte[] readNBytes(int length) throws IOException {
                try { Files.writeString(path(), "Points: 42\n"); }
                catch (IOException failure) { throw new AssertionError(failure); }
                return super.readNBytes(length);
            }
        };
        assertThrows(IOException.class, () -> YamlConfiguration.load(path(), defaults));
        assertDoesNotThrow(() -> assertEquals("Points: 42\n", Files.readString(path())));
    }

    @Test void malformedExistingFileDoesNotFallBackToResourceAndClosesSuppliedStream() throws Exception {
        String malformed = "Points: [unterminated";
        Files.writeString(path(), malformed);
        var defaults = new TrackedStream("Points: 7\n");
        assertThrows(IOException.class, () -> YamlConfiguration.load(path(), defaults));
        assertTrue(defaults.closed);
        assertEquals(malformed, Files.readString(path()));
    }

    @Test void casingIsReadOnlyOptInAndCaseCollisionsUseFirstEnumeratedKey() throws Exception {
        Files.writeString(path(), "Points: 7\nPOINTS: 9\n");
        var config = YamlConfiguration.load(path());
        var exact = config.snapshot();
        config.setIgnoreCase(true);
        assertEquals(7, config.getInt("POINTS"));
        assertEquals(9, exact.view().getInt("POINTS", 0));
        config.set("POINTS", 10); // Editing uses exact keys even with case-insensitive reads.
        config.setIgnoreCase(false);
        assertEquals(7, config.getInt("Points"));
        assertEquals(10, config.getInt("POINTS"));
        assertEquals(Set.of("Points", "POINTS"), config.getKeys(false));
    }

    @Test void explicitSaveCreatesMissingEmptyFileButDoesNotCreateParents() throws Exception {
        var config = YamlConfiguration.load(path());
        assertFalse(Files.exists(path()));
        config.save();
        assertTrue(Files.isRegularFile(path()));
        assertTrue(YamlConfiguration.load(path()).getKeys(false).isEmpty());
        assertThrows(IOException.class, () -> YamlConfiguration.load(directory.resolve("missing/settings.yml")));
        assertFalse(Files.exists(directory.resolve("missing")));
    }

    @Test void unwritableParentLeavesActiveSnapshotAndDiskUnchangedThenRetrySucceeds() throws Exception {
        assumeTrue(Files.getFileAttributeView(directory, PosixFileAttributeView.class) != null);
        var config = YamlConfiguration.load(path(), new TrackedStream("Points: 7\n"));
        var before = config.snapshot();
        String bytes = Files.readString(path());
        var permissions = Files.getPosixFilePermissions(directory);
        try {
            Files.setPosixFilePermissions(directory,
                    Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
            assumeTrue(!Files.isWritable(directory), "Process can bypass directory permissions");
            assertThrows(IOException.class, () -> config.set("Points", 8));
            assertSame(before, config.snapshot());
            assertEquals(bytes, Files.readString(path()));
        } finally { Files.setPosixFilePermissions(directory, permissions); }
        config.set("Points", 8);
        assertEquals(8, YamlConfiguration.load(path()).getInt("Points"));
    }

    @Test void simultaneousFirstLoadsCannotBothPublishDifferentDefaults() throws Exception {
        var workers = Executors.newFixedThreadPool(2);
        try {
            for (int trial = 0; trial < 50; trial++) {
                Path path = directory.resolve("initialize-" + trial + ".yml");
                var gate = new CyclicBarrier(2);
                var first = workers.submit(() -> initialize(path, "Points: 1\n", gate));
                var second = workers.submit(() -> initialize(path, "Points: 2\n", gate));
                Integer one = first.get(10, TimeUnit.SECONDS);
                Integer two = second.get(10, TimeUnit.SECONDS);
                assertNotEquals(one == null, two == null, "Exactly one missing-file initializer must succeed");
                assertEquals(one == null ? two.intValue() : one.intValue(), YamlConfiguration.load(path).getInt("Points"));
            }
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test void simultaneousSameRevisionEditsCannotBothSucceedAndLoseOneChange() throws Exception {
        var workers = Executors.newFixedThreadPool(2);
        try {
            for (int trial = 0; trial < 50; trial++) {
                Path path = directory.resolve("update-" + trial + ".yml");
                Files.writeString(path, "First: 0\nSecond: 0\n");
                var first = YamlConfiguration.load(path);
                var second = YamlConfiguration.load(path);
                var gate = new CyclicBarrier(2);
                var one = workers.submit(() -> editAtBarrier(first, "First", gate));
                var two = workers.submit(() -> editAtBarrier(second, "Second", gate));
                boolean firstSucceeded = one.get(10, TimeUnit.SECONDS);
                boolean secondSucceeded = two.get(10, TimeUnit.SECONDS);
                assertNotEquals(firstSucceeded, secondSucceeded, "One stale writer must be rejected");
                var disk = YamlConfiguration.load(path);
                assertEquals(firstSucceeded ? 1 : 0, disk.getInt("First"));
                assertEquals(secondSucceeded ? 1 : 0, disk.getInt("Second"));
            }
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static Integer initialize(Path path, String text, CyclicBarrier gate) throws Exception {
        InputStream stream = new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)) {
            @Override public byte[] readNBytes(int limit) throws IOException {
                await(gate);
                return super.readNBytes(limit);
            }
        };
        try { return YamlConfiguration.load(path, stream).getInt("Points"); }
        catch (IOException stale) { return null; }
    }

    @Test void caseAliasTargetsOnCaseInsensitiveStoreCannotLoseConcurrentEdits() throws Exception {
        var workers = Executors.newFixedThreadPool(2);
        try (var filesystem = Jimfs.newFileSystem(Configuration.osX().toBuilder()
                .setPathEqualityUsesCanonicalForm(false).build())) {
            Path parent = filesystem.getPath("/config");
            Files.createDirectory(parent);
            for (int trial = 0; trial < 50; trial++) {
                Path firstPath = parent.resolve("Settings-" + trial + ".yml");
                Path secondPath = parent.resolve("settings-" + trial + ".yml");
                Files.writeString(firstPath, "First: 0\nSecond: 0\n");
                assertNotEquals(firstPath, secondPath, "Paths must retain spelling-sensitive equality");
                assertNotEquals(firstPath.hashCode(), secondPath.hashCode());
                assertTrue(Files.isSameFile(firstPath, secondPath), "Store must resolve case aliases");
                var first = YamlConfiguration.load(firstPath);
                var second = YamlConfiguration.load(secondPath);
                var gate = new CyclicBarrier(2);
                var one = workers.submit(() -> editAtBarrier(first, "First", gate));
                var two = workers.submit(() -> editAtBarrier(second, "Second", gate));
                boolean firstSucceeded = one.get(10, TimeUnit.SECONDS);
                boolean secondSucceeded = two.get(10, TimeUnit.SECONDS);
                assertNotEquals(firstSucceeded, secondSucceeded, "Case aliases must share revision coordination");
                var disk = YamlConfiguration.load(firstPath);
                assertEquals(firstSucceeded ? 1 : 0, disk.getInt("First"));
                assertEquals(secondSucceeded ? 1 : 0, disk.getInt("Second"));
            }
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test void committedResourceInitializationRemainsSuccessfulAfterUnlinkFailure() throws Exception {
        try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.deleteIfExists(argThat((Path staged) -> staged != null
                    && staged.getFileName().toString().startsWith(".simpleapi-config-"))))
                    .thenThrow(new IOException("synthetic staged unlink failure"));
            var config = YamlConfiguration.load(path(), new TrackedStream("Points: 7\n"));
            assertEquals(7, config.getInt("Points"));
            assertEquals(config.snapshot().revision(), YamlConfiguration.load(path()).snapshot().revision());
            assertEquals("Points: 7\n", Files.readString(path()));
        }
        try (var children = Files.list(directory)) {
            var staged = children.filter(file -> file.getFileName().toString().startsWith(".simpleapi-config-")).toList();
            assertEquals(1, staged.size(), "Injected cleanup failure should leave exactly its staged sibling");
            for (Path file : staged) Files.delete(file);
        }
    }

    private static boolean editAtBarrier(YamlConfiguration config, String key, CyclicBarrier gate) throws Exception {
        try {
            config.update(edit -> {
                try { await(gate); } catch (IOException failure) { throw new AssertionError(failure); }
                edit.set(key, 1);
            });
            return true;
        } catch (IOException stale) { return false; }
    }

    private static void await(CyclicBarrier gate) throws IOException {
        try { gate.await(5, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(interrupted); }
        catch (java.util.concurrent.BrokenBarrierException | java.util.concurrent.TimeoutException failure) {
            throw new IOException(failure);
        }
    }

    private static final class TrackedStream extends ByteArrayInputStream {
        private final int initialSize;
        private boolean closed;
        TrackedStream(String text) { super(text.getBytes(StandardCharsets.UTF_8)); initialSize = count; }
        @Override public void close() throws IOException { closed = true; super.close(); }
    }
}
