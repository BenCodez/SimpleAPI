package com.bencodez.simpleapi.tests.shared;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import com.bencodez.simpleapi.core.config.CaseInsensitiveConfigView;
import com.bencodez.simpleapi.core.config.ConfigurateConfigView;
import com.bencodez.simpleapi.core.config.ConfigurateStructuredConfigView;
import com.bencodez.simpleapi.core.config.StructuredConfigView.Kind;
import com.bencodez.simpleapi.core.config.YamlConfigDocument;

/** Exercises the native YAML consumption contract without any platform or JUnit classes. */
public final class NativeYamlFixture {
    private NativeYamlFixture() { }

    public static void run() throws Exception {
        var directory = Files.createTempDirectory("simpleapi-native-yaml-");
        var path = directory.resolve("settings.yml");
        try {
            var missing = YamlConfigDocument.open(path);
            require("missing".equals(missing.snapshot().revision()) && !Files.exists(path), "Read created a file");
            String original = """
                    Points: 7
                    Quoted: '12'
                    Enabled: false
                    Empty: []
                    Rewards:
                      10:
                        Commands: [say voted, 2, true]
                    'some.site':
                      Value: literal
                    Unknown: preserve
                    """;
            Files.writeString(path, original);
            var document = YamlConfigDocument.open(path, 1024);
            var before = document.snapshot();
            var typed = before.view();
            require(typed.getInt("Points", -1) == 7, "Numeric read changed");
            require(typed.getInt("Quoted", 99) == 99, "Quoted number was coerced");
            require(!typed.getBoolean("Enabled", true), "False was replaced with a default");
            require(typed.getInt("Absent", 42) == 42 && !typed.contains("Absent"), "Missing default changed");
            require(typed.getInt("points", 99) == 99, "Ordinary reads lost case sensitivity");
            require(typed.getStringList("Rewards.10.Commands").equals(List.of("say voted", "2", "true")),
                    "Legacy scalar-list conversion changed");
            var structured = new ConfigurateStructuredConfigView((ConfigurateConfigView) typed);
            require(structured.kind("Empty") == Kind.LIST && structured.kind("Absent") == Kind.MISSING,
                    "Empty and missing values became indistinguishable");
            require("literal".equals(structured.at("some.site").getString("Value", "")), "Literal key split");
            var insensitive = new CaseInsensitiveConfigView(structured);
            require(insensitive.getInt("POINTS", -1) == 7, "Case-insensitive wrapper failed");
            var exported = structured.snapshotValues();
            try {
                exported.put("Points", 99);
                throw new AssertionError("Structured export is mutable");
            } catch (UnsupportedOperationException expected) { }
            var after = document.update(before.revision(), edit -> {
                edit.set("Points", 8);
                edit.set("Rewards.10.Commands", List.of("say updated"));
                edit.setAt(Map.of("Value", "updated literal"), "some.site");
            });
            require(before.view().getInt("Points", -1) == 7 && exported.get("Points").equals(7),
                    "A published snapshot changed after editing");
            require("preserve".equals(after.view().getString("Unknown", "")), "Unknown key lost");
            var reopened = YamlConfigDocument.open(path).snapshot();
            require(after.revision().equals(reopened.revision()) && reopened.view().getInt("Points", -1) == 8,
                    "Persisted revision/value mismatch");
            require(reopened.view().getConfigurationSection("Rewards").getKeys(false).equals(java.util.Set.of("10")),
                    "Numeric key duplicated");
            require("updated literal".equals(new ConfigurateStructuredConfigView(
                    (ConfigurateConfigView) reopened.view()).at("some.site").getString("Value", "")),
                    "Literal edit did not survive reopening");
            String lastGood = Files.readString(path);
            expectIo(() -> document.update(after.revision(), edit -> edit.set("Large", "x".repeat(2048))));
            require(lastGood.equals(Files.readString(path)), "Failed bounded write changed file bytes");
            require(after.revision().equals(document.snapshot().revision()), "Failed write published state");
            String malformed = "Points: [unterminated";
            Files.writeString(path, malformed);
            expectIo(document::reload);
            require(after.revision().equals(document.snapshot().revision())
                    && document.snapshot().view().getInt("Points", -1) == 8, "Failed reload published state");
            expectIo(() -> document.update(after.revision(), edit -> edit.set("Points", 9)));
            require(malformed.equals(Files.readString(path)), "Stale write overwrote malformed external edit");
            expectIo(() -> YamlConfigDocument.open(path));
            Files.writeString(path, "Points: 11\n");
            require(document.reload().view().getInt("Points", -1) == 11, "Reload failed to recover");
        } finally {
            Files.deleteIfExists(path);
            Files.deleteIfExists(directory);
        }
    }

    @FunctionalInterface private interface IoOperation { void run() throws IOException; }

    private static void expectIo(IoOperation operation) throws IOException {
        try { operation.run(); } catch (IOException expected) { return; }
        throw new AssertionError("Expected IOException");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
