package com.bencodez.simpleapi.tests.shared;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.bencodez.simpleapi.core.config.YamlConfigDocument;

class NativeYamlTest {
    @TempDir Path directory;

    @Test void nativeYamlConsumptionContract() throws Exception {
        NativeYamlFixture.run();
    }

    @Test void unwritableParentPreservesFileAndSnapshotAndAllowsRetry() throws Exception {
        assumeTrue(Files.getFileAttributeView(directory, PosixFileAttributeView.class) != null);
        Path path = directory.resolve("settings.yml");
        String original = "Points: 7\n";
        Files.writeString(path, original);
        var document = YamlConfigDocument.open(path);
        var before = document.snapshot();
        var permissions = Files.getPosixFilePermissions(directory);
        try {
            Files.setPosixFilePermissions(directory,
                    Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
            assumeTrue(!Files.isWritable(directory), "Process can bypass directory permissions");
            assertThrows(IOException.class,
                    () -> document.update(before.revision(), edit -> edit.set("Points", 8)));
            assertEquals(original, Files.readString(path));
            assertEquals(before.revision(), document.snapshot().revision());
            assertEquals(7, document.snapshot().view().getInt("Points", -1));
            try (var children = Files.list(directory)) { assertEquals(List.of(path), children.toList()); }
        } finally {
            Files.setPosixFilePermissions(directory, permissions);
        }
        assertEquals(8, document.update(before.revision(), edit -> edit.set("Points", 8))
                .view().getInt("Points", -1));
        assertEquals(8, YamlConfigDocument.open(path).snapshot().view().getInt("Points", -1));
    }
}
