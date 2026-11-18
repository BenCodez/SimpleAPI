package com.bencodez.simpleapi.tests.shared;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.bencodez.simpleapi.core.config.YamlConfiguration;

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

    private static final class TrackedStream extends ByteArrayInputStream {
        private final int initialSize;
        private boolean closed;
        TrackedStream(String text) { super(text.getBytes(StandardCharsets.UTF_8)); initialSize = count; }
        @Override public void close() throws IOException { closed = true; super.close(); }
    }
}
