import java.nio.file.Files;
import java.util.List;
import com.bencodez.simpleapi.file.annotation.AnnotationBinder;
import com.bencodez.simpleapi.file.annotation.ConfigDataInt;
import com.bencodez.simpleapi.file.annotation.ConfigDataListString;
import com.bencodez.simpleapi.file.config.configurate.YamlConfigDocument;
import com.bencodez.simpleapi.sql.mysql.config.MysqlConfigView;

/** Consumer compiled against packaged artifacts, not reactor target/classes. */
public final class NativeConfigSmoke {
    static final class Options {
        @ConfigDataInt(path="PointsOnVote") int points;
        @ConfigDataListString(path="Rewards.Commands") java.util.ArrayList<String> commands;
    }
    public static void main(String[] arguments) throws Exception {
        var directory=Files.createTempDirectory("simpleapi-native-smoke-");
        var file=directory.resolve("VoteSites.yml");
        try {
            var document=YamlConfigDocument.open(file);
            document.update("missing", edit -> {
                edit.set("PointsOnVote", 3);
                edit.set("Rewards.Commands", List.of("give player minecraft:diamond"));
                edit.set("MySQL.Host", "localhost");
            });
            var snapshot=YamlConfigDocument.open(file).snapshot();
            var options=new Options();
            new AnnotationBinder().load(snapshot.view(), options);
            if (options.points != 3 || options.commands.size() != 1) throw new AssertionError("Native binding failed");
            var sql=new MysqlConfigView(snapshot.view().getConfigurationSection("MySQL"));
            if (!"localhost".equals(sql.getHostName()) || sql.getMaxThreads() != 1) throw new AssertionError("Native SQL configuration failed");
            try { Class.forName("org.bukkit.Bukkit"); throw new AssertionError("Bukkit was available"); }
            catch (ClassNotFoundException expected) { }
            System.out.println("Packaged native consumer: YAML, binder, persistence and SQL configuration passed");
        } finally { Files.deleteIfExists(file); Files.deleteIfExists(directory); }
    }
}
