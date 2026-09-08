package com.bencodez.simpleapi.file.config.configurate;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.bencodez.simpleapi.file.config.ConfigEditor;
import com.bencodez.simpleapi.file.config.ConfigSnapshot;

class YamlConfigDocumentTest {
    @TempDir Path directory;
    private Path path() { return directory.resolve("votes.yml"); }
    private YamlConfigDocument initialized() throws Exception {
        Files.writeString(path(), "points: 7\nunknown: preserve\n");
        return YamlConfigDocument.open(path());
    }

    @Test void readingMissingFileDoesNotCreateIt() throws Exception {
        YamlConfigDocument document=YamlConfigDocument.open(path());
        assertEquals("missing", document.snapshot().revision());
        document.reload();
        assertFalse(Files.exists(path()));
        assertEquals(Set.of(), document.snapshot().view().getKeys(false));
    }
    @Test void persistsEditsAndReloadsWithStableRevisions() throws Exception {
        YamlConfigDocument document=initialized();
        ConfigSnapshot before=document.snapshot();
        ConfigSnapshot after=document.update(before.revision(), e -> e.set("points", 8));
        assertEquals(7, before.view().getInt("points", -1));
        assertEquals(8, after.view().getInt("points", -1));
        assertEquals("preserve", after.view().getString("unknown", ""));
        assertNotEquals(before.revision(), after.revision());
        assertEquals(after.revision(), YamlConfigDocument.open(path()).snapshot().revision());
        assertEquals(8, document.reload().view().getInt("points", -1));
        try (var children=Files.list(directory)) { assertEquals(List.of(path()), children.toList()); }
    }
    @Test void createsMissingFileOnlyWhenExplicitlyUpdated() throws Exception {
        YamlConfigDocument document=YamlConfigDocument.open(path());
        document.update("missing", e -> e.set("rewards.commands", List.of("say voted")));
        assertTrue(Files.exists(path()));
        assertEquals(List.of("say voted"), document.reload().view().getStringList("rewards.commands"));
    }
    @Test void malformedReloadPreservesLastGoodAndCannotBeOverwrittenByOldRevision() throws Exception {
        YamlConfigDocument document=initialized();
        ConfigSnapshot before=document.snapshot();
        String corrupt="points: [unterminated";
        Files.writeString(path(), corrupt);
        assertThrows(IOException.class, document::reload);
        assertEquals(before.revision(), document.snapshot().revision());
        assertEquals(7, document.snapshot().view().getInt("points", -1));
        assertThrows(IOException.class, () -> document.update(before.revision(), e -> e.set("points", 9)));
        assertEquals(corrupt, Files.readString(path()));
    }
    @Test void rejectsStaleInMemoryRevision() throws Exception {
        YamlConfigDocument document=initialized();
        String before=document.snapshot().revision();
        document.update(before, e -> e.set("points", 8));
        assertThrows(IOException.class, () -> document.update(before, e -> e.set("points", 9)));
        assertEquals(8, document.snapshot().view().getInt("points", -1));
    }
    @Test void rejectsExternalEditAndRequiresExplicitReload() throws Exception {
        YamlConfigDocument document=initialized();
        Files.writeString(path(), "points: 50\n");
        assertThrows(IOException.class, () -> document.update(document.snapshot().revision(), e -> e.set("points", 9)));
        assertEquals("points: 50\n", Files.readString(path()));
        assertEquals(50, document.reload().view().getInt("points", -1));
    }
    @Test void detectsFileCreatedSinceMissingSnapshot() throws Exception {
        YamlConfigDocument document=YamlConfigDocument.open(path());
        Files.writeString(path(), "");
        assertThrows(IOException.class, () -> document.update("missing", e -> e.set("points", 1)));
        assertEquals("", Files.readString(path()));
    }
    @Test void editExceptionDiscardsPrivateCopy() throws Exception {
        YamlConfigDocument document=initialized();
        String bytes=Files.readString(path());
        String revision=document.snapshot().revision();
        assertThrows(IllegalArgumentException.class, () -> document.update(revision, e -> {
            e.set("points", 99);
            throw new IllegalArgumentException("cancel");
        }));
        assertEquals(bytes, Files.readString(path()));
        assertEquals(revision, document.snapshot().revision());
        assertEquals(7, document.snapshot().view().getInt("points", -1));
    }
    @Test void externalEditDuringCallbackIsNotOverwritten() throws Exception {
        YamlConfigDocument document=initialized();
        assertThrows(IOException.class, () -> document.update(document.snapshot().revision(), e -> {
            e.set("points", 99);
            try { Files.writeString(path(), "points: 42\n"); } catch (IOException failure) { throw new RuntimeException(failure); }
        }));
        assertEquals("points: 42\n", Files.readString(path()));
        assertEquals(7, document.snapshot().view().getInt("points", -1));
    }
    @Test void editorsAndCallerCollectionsCannotMutatePublishedState() throws Exception {
        YamlConfigDocument document=initialized();
        AtomicReference<ConfigEditor> retained=new AtomicReference<>();
        List<String> list=new ArrayList<>(List.of("say voted"));
        document.update(document.snapshot().revision(), e -> { retained.set(e); e.set("commands", list); });
        list.add("unwanted");
        assertThrows(IllegalStateException.class, () -> retained.get().set("points", 123));
        assertEquals(List.of("say voted"), document.snapshot().view().getStringList("commands"));
    }
    @Test void rejectsNestedWritesAndReloads() throws Exception {
        YamlConfigDocument document=initialized();
        assertThrows(IllegalStateException.class, () -> document.update(document.snapshot().revision(), e -> {
            try { document.reload(); } catch (IOException failure) { throw new RuntimeException(failure); }
        }));
        assertThrows(IllegalStateException.class, () -> document.update(document.snapshot().revision(), e -> {
            try { document.update(document.snapshot().revision(), next -> next.set("points", 10)); }
            catch (IOException failure) { throw new RuntimeException(failure); }
        }));
        assertEquals(7, document.snapshot().view().getInt("points", -1));
    }
    @Test void rejectsEditorWritesOnOtherThreads() throws Exception {
        YamlConfigDocument document=initialized();
        AtomicReference<Throwable> observed=new AtomicReference<>();
        document.update(document.snapshot().revision(), e -> {
            Thread thread=new Thread(() -> { try { e.set("points", 99); } catch (Throwable failure) { observed.set(failure); } });
            thread.start();
            try { thread.join(5000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new RuntimeException(interrupted); }
            assertFalse(thread.isAlive());
        });
        assertInstanceOf(IllegalStateException.class, observed.get());
        assertEquals(7, document.snapshot().view().getInt("points", -1));
    }
    @Test void boundsInputAndOutputWithoutTruncatingOriginal() throws Exception {
        Files.writeString(path(), "points: 7\n");
        YamlConfigDocument document=YamlConfigDocument.open(path(), 128);
        String before=Files.readString(path());
        assertThrows(IOException.class, () -> document.update(document.snapshot().revision(), e -> e.set("large", "x".repeat(512))));
        assertEquals(before, Files.readString(path()));
        Files.writeString(path(), "x".repeat(129));
        assertThrows(IOException.class, document::reload);
        assertEquals(7, document.snapshot().view().getInt("points", -1));
    }
    @Test void malformedUtf8DoesNotReplaceLastGood() throws Exception {
        YamlConfigDocument document=initialized();
        Files.write(path(), new byte[] {(byte)0xc3, 0x28});
        assertThrows(IOException.class, document::reload);
        assertEquals(7, document.snapshot().view().getInt("points", -1));
    }
    @Test void refusesSymlinkAndDirectoryTargets() throws Exception {
        Path actual=directory.resolve("actual.yml");
        Files.writeString(actual, "points: 4\n");
        Files.createSymbolicLink(path(), actual);
        assertThrows(IOException.class, () -> YamlConfigDocument.open(path()));
        assertEquals("points: 4\n", Files.readString(actual));
        assertThrows(IOException.class, () -> YamlConfigDocument.open(directory));
    }
    @Test void refusesSymlinkIntroducedAfterOpen() throws Exception {
        YamlConfigDocument document=initialized();
        Path other=directory.resolve("other.yml");
        Files.writeString(other, "do not overwrite\n");
        Files.delete(path());
        Files.createSymbolicLink(path(), other);
        assertThrows(IOException.class, () -> document.update(document.snapshot().revision(), e -> e.set("points", 99)));
        assertEquals("do not overwrite\n", Files.readString(other));
    }
    @Test void preservesPosixModeAndDefaultsNewFilesToOwnerOnly() throws Exception {
        assumeTrue(Files.getFileAttributeView(directory, PosixFileAttributeView.class) != null);
        YamlConfigDocument document=initialized();
        Set<PosixFilePermission> mode=Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_READ);
        Files.setPosixFilePermissions(path(), mode);
        document.update(document.snapshot().revision(), e -> e.set("points", 8));
        assertEquals(mode, Files.getPosixFilePermissions(path()));
        Path fresh=directory.resolve("new.yml");
        YamlConfigDocument.open(fresh).update("missing", e -> e.set("points", 1));
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(fresh));
    }
    @Test void supportsLiteralAndNumericKeysWithoutCreatingDuplicateSections() throws Exception {
        Files.writeString(path(), "rewards:\n  10:\n    command: before\n");
        YamlConfigDocument document=YamlConfigDocument.open(path());
        document.update(document.snapshot().revision(), e -> { e.set("rewards.10.command", "after"); e.setAt(Map.of("command", "literal"), "some.site"); });
        assertEquals(Set.of("10"), document.reload().view().getConfigurationSection("rewards").getKeys(false));
        ConfigurateConfigView view=(ConfigurateConfigView)document.snapshot().view();
        assertEquals("after", view.getString("rewards.10.command", ""));
        assertEquals("literal", view.at("some.site").getString("command", ""));
    }
    @Test void rejectsCyclesDeepTreesNativeObjectsAndOversizedNodeTrees() throws Exception {
        YamlConfigDocument document=initialized();
        Map<String,Object> cycle=new LinkedHashMap<>();cycle.put("cycle", cycle);
        assertThrows(IllegalArgumentException.class, () -> document.update(document.snapshot().revision(), e -> e.set("cycle", cycle)));
        Object tree="leaf";
        for (int i=0;i<70;i++) tree=Map.of("child",tree);
        Object tooDeep=tree;
        assertThrows(IllegalArgumentException.class, () -> document.update(document.snapshot().revision(), e -> e.set("deep", tooDeep)));
        assertThrows(IllegalArgumentException.class, () -> document.update(document.snapshot().revision(), e -> e.set("native", new Object())));
        assertThrows(IllegalArgumentException.class, () -> document.update(document.snapshot().revision(), e -> e.set("many", Collections.nCopies(100_001, 1))));
        assertEquals(7, document.snapshot().view().getInt("points", -1));
    }
    @Test void nullRemovesOnlyTheSelectedValue() throws Exception {
        YamlConfigDocument document=initialized();
        document.update(document.snapshot().revision(), e -> e.remove("points"));
        assertFalse(document.reload().view().contains("points"));
        assertEquals("preserve", document.snapshot().view().getString("unknown", ""));
    }
    @Test void rejectsMissingParentInvalidLimitAndScalarRoot() throws Exception {
        assertThrows(IOException.class, () -> YamlConfigDocument.open(directory.resolve("missing/file.yml")));
        assertFalse(Files.exists(directory.resolve("missing")));
        assertThrows(IllegalArgumentException.class, () -> YamlConfigDocument.open(path(), 0));
        Files.writeString(path(), "[1, 2, 3]\n");
        assertThrows(IOException.class, () -> YamlConfigDocument.open(path()));
    }
}
