import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import org.bukkit.configuration.MemoryConfiguration;
import org.spongepowered.configurate.BasicConfigurationNode;
import com.bencodez.simpleapi.file.config.configurate.ConfigurateConfigView;
import com.bencodez.simpleapi.sql.mysql.config.MysqlConfig;
import com.bencodez.simpleapi.sql.mysql.config.MysqlConfigSpigot;
import com.bencodez.simpleapi.sql.mysql.config.MysqlConfigView;

/** Deliberately has both platforms' test inputs; never a native runtime dependency. */
public final class ConfigParityProbe {
    private static int checks;
    private static void equal(Object expected,Object actual) {
        checks++;
        if (!Objects.equals(expected,actual)) throw new AssertionError("Configuration parity failed: expected="+expected+", actual="+actual);
    }
    public static void main(String[] arguments) throws Exception {
        Object[] values={null,"", "text", "true", "12", false, true, 0, -2, 5L, 4_000_000_000L, 1.25, List.of(), List.of("1", 2, 3.75, true, "bad")};
        for (Object value:values) {
            var bukkit=new MemoryConfiguration();
            var node=BasicConfigurationNode.root();
            bukkit.set("value",value); node.node("value").raw(value);
            var view=new ConfigurateConfigView(node);
            equal(bukkit.contains("value"),view.contains("value"));
            equal(bukkit.getString("value","fallback"),view.getString("value","fallback"));
            equal(bukkit.getBoolean("value",false),view.getBoolean("value",false));
            equal(bukkit.getBoolean("value",true),view.getBoolean("value",true));
            equal(bukkit.getInt("value",7),view.getInt("value",7));
            equal(bukkit.getLong("value",9L),view.getLong("value",9L));
            equal(bukkit.getDouble("value",2.5),view.getDouble("value",2.5));
            equal(bukkit.getStringList("value"),view.getStringList("value"));
            equal(bukkit.getIntegerList("value"),view.getIntegerList("value"));
        }
        for (boolean populated:new boolean[]{false,true}) {
            var bukkit=new MemoryConfiguration();var node=BasicConfigurationNode.root();
            if (populated) {
                var data=new LinkedHashMap<String,Object>();
                data.put("Host","localhost");data.put("Port",3306);data.put("Username","fixture");data.put("Password","synthetic-fixture");
                data.put("Database","votes");data.put("Prefix","test_");data.put("Name","users");data.put("MaxConnections",0);
                data.put("UseMariaDB",true);data.put("UseSSL",true);data.put("PoolName","fixture");data.put("ConnectionTimeout",1000);
                for (var entry:data.entrySet()) { bukkit.set(entry.getKey(),entry.getValue()); node.node(entry.getKey()).raw(entry.getValue()); }
            }
            var oldConfig=new MysqlConfigSpigot(bukkit);
            var sharedConfig=new MysqlConfigView(new ConfigurateConfigView(node));
            for (Method method:MysqlConfig.class.getDeclaredMethods())
                if (method.getParameterCount()==0 && (method.getName().startsWith("get") || method.getName().startsWith("is") || method.getName().startsWith("has")))
                    equal(method.invoke(oldConfig),method.invoke(sharedConfig));
        }
        System.out.println("Bukkit/shared configuration parity: "+checks+" comparisons passed");
    }
}
