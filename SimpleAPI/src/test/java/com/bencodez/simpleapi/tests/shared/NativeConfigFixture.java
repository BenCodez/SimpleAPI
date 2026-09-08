package com.bencodez.simpleapi.tests.shared;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import com.bencodez.simpleapi.core.config.AnnotationBinder;
import com.bencodez.simpleapi.core.config.YamlConfigDocument;
import com.bencodez.simpleapi.core.sql.MysqlConfigView;
import com.bencodez.simpleapi.file.annotation.ConfigDataInt;
import com.bencodez.simpleapi.file.annotation.ConfigDataListString;

/** No JUnit/Bukkit dependencies: executed with the shared JAR and its explicit runtime libraries only. */
public final class NativeConfigFixture {
    public static final class Options {
        @ConfigDataInt(path = "points") public int points;
        @ConfigDataListString(path = "commands") public ArrayList<String> commands;
    }
    private NativeConfigFixture() { }
    public static void run() throws Exception {
        var folder = Files.createTempDirectory("simpleapi-single-project-");
        var path = folder.resolve("votes.yml");
        try {
            var document = YamlConfigDocument.open(path);
            document.update("missing", editor -> {
                editor.set("points", 3);
                editor.set("commands", List.of("say voted"));
                editor.set("MySQL.Host", "localhost");
            });
            var view = YamlConfigDocument.open(path).snapshot().view();
            var options = new Options();
            new AnnotationBinder().load(view, options);
            if (options.points != 3 || !List.of("say voted").equals(options.commands))
                throw new AssertionError("Core binding/persistence failed");
            if (!"localhost".equals(new MysqlConfigView(view.getConfigurationSection("MySQL")).getHostName()))
                throw new AssertionError("Core SQL configuration failed");
            var legacy = com.bencodez.simpleapi.file.config.configurate.YamlConfigDocument.open(path);
            if (!(legacy.snapshot().view() instanceof com.bencodez.simpleapi.file.config.configurate.ConfigurateConfigView))
                throw new AssertionError("Legacy snapshot type changed");
            var legacyOptions = new Options();
            new com.bencodez.simpleapi.file.annotation.AnnotationBinder().load(legacy.snapshot().view(), legacyOptions);
            if (legacyOptions.points != 3) throw new AssertionError("Legacy binder failed");
        } finally {
            Files.deleteIfExists(path);
            Files.deleteIfExists(folder);
        }
    }
}
