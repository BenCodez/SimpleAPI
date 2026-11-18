package com.bencodez.simpleapi.tests.shared;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import com.bencodez.simpleapi.core.config.StructuredConfigView.Kind;
import com.bencodez.simpleapi.core.config.YamlConfiguration;

/** A native consumer uses only the public facade: no Configurate nodes or casts. */
public final class NativeYamlApiFixture {
    private NativeYamlApiFixture() { }

    public static void run() throws Exception {
        var directory = Files.createTempDirectory("simpleapi-yaml-api-");
        var path = directory.resolve("settings.yml");
        try {
            var missing = YamlConfiguration.load(path);
            require(!Files.exists(path) && missing.getInt("PointsOnVote", 3) == 3, "Missing read changed disk");
            var config = YamlConfiguration.load(path, NativeYamlApiFixture.class, "/native-yaml-defaults.yml");
            byte[] resource;
            try (var defaults = NativeYamlApiFixture.class.getResourceAsStream("/native-yaml-defaults.yml")) {
                resource = defaults.readAllBytes();
            }
            require(java.util.Arrays.equals(resource, Files.readAllBytes(path)), "Initial resource bytes changed");
            require(config.getInt("PointsOnVote") == 1 && config.getBoolean("ProcessRewards"), "Typed options failed");
            require(config.getLong("PointsOnVote") == 1L && config.getDouble("PointsOnVote") == 1D, "Numeric reads failed");
            require(config.getInt("Quoted", 99) == 99, "Quoted scalar was coerced");
            require(config.kind("Empty") == Kind.LIST && config.kind("Absent") == Kind.MISSING, "Empty/missing lost");
            require(config.getString("Absent") == null && !config.getBoolean("Absent"), "Convenience defaults changed");
            var sites = config.getConfigurationSection("VoteSites");
            require(sites.at("some.site").getStringList("Rewards.Commands").equals(List.of("say voted")),
                    "Literal site/list traversal failed");
            var before = config.snapshot();
            require(config.getInt("pointsonvote", 99) == 99, "Default reads are not case-sensitive");
            config.setIgnoreCase(true);
            require(config.getInt("POINTSONVOTE") == 1, "Opt-in case policy failed");
            require(config.at("votesites", "SOME.SITE").getBoolean("enabled", false), "Case/literal traversal failed");
            config.setIgnoreCase(false);
            config.update(edit -> {
                edit.set("PointsOnVote", 4);
                edit.set("Unknown", "preserve");
                edit.set("Milestones.10.Points", 3);
                edit.setAt(List.of("say updated"), "VoteSites", "some.site", "Rewards", "Commands");
            });
            require(before.view().getInt("PointsOnVote", 0) == 1, "Snapshot was not detached");
            require(sites.at("some.site").getStringList("Rewards.Commands").equals(List.of("say voted")),
                    "Retained section changed");
            config.setAt(Map.of("Enabled", false), "another.site");
            config.save();
            var reopened = YamlConfiguration.load(path, NativeYamlApiFixture.class, "/missing-resource.yml");
            require(reopened.getInt("PointsOnVote") == 4 && "preserve".equals(reopened.getString("Unknown")),
                    "Existing file overwritten by defaults");
            require(reopened.getConfigurationSection("Milestones").getKeys(false).equals(java.util.Set.of("10")),
                    "Numeric key duplicated");
            require(!reopened.at("another.site").getBoolean("Enabled", true), "Literal setter failed");
            String bytes = Files.readString(path);
            try {
                config.update(before.revision(), edit -> edit.set("PointsOnVote", 8));
                throw new AssertionError("Stale snapshot accepted");
            } catch (IOException expected) { }
            require(bytes.equals(Files.readString(path)), "Stale write changed file");
            var lastGood = config.snapshot();
            Files.writeString(path, "PointsOnVote: [unterminated");
            try { config.reload(); throw new AssertionError("Malformed reload accepted"); }
            catch (IOException expected) { }
            require(config.snapshot() == lastGood && config.getInt("PointsOnVote") == 4, "Failed reload changed active data");
            try { config.save(); throw new AssertionError("Save overwrote external malformed edit"); }
            catch (IOException expected) { }
            Files.writeString(path, "PointsOnVote: 9\n");
            require(config.reload().view().getInt("PointsOnVote", 0) == 9, "Reload did not recover");
        } finally {
            Files.deleteIfExists(path);
            Files.deleteIfExists(directory);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
