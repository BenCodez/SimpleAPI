package com.bencodez.simpleapi.tests.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.BasicConfigurationNode;
import com.bencodez.simpleapi.core.config.ConfigurateConfigView;
import com.bencodez.simpleapi.core.sql.MysqlConfigView;
import com.bencodez.simpleapi.sql.mysql.config.MysqlConfig;
import com.bencodez.simpleapi.sql.mysql.config.MysqlConfigSpigot;

/** Compares against the real Bukkit getters, while packaged isolation is tested separately. */
class ConfigParityTest {
    @Test void preservesScalarListAndSqlConfigurationBehavior() throws Exception {
        int comparisons = 0;
        Object[] values = {null, "", "text", "true", "12", false, true, 0, -2, 5L, 4_000_000_000L,
                1.25, List.of(), List.of("1", 2, 3.75, true, "bad")};
        for (Object value : values) {
            var bukkit = new MemoryConfiguration();
            var node = BasicConfigurationNode.root();
            bukkit.set("value", value); node.node("value").raw(value);
            var view = new ConfigurateConfigView(node);
            assertEquals(bukkit.contains("value"), view.contains("value"));
            assertEquals(bukkit.getString("value", "fallback"), view.getString("value", "fallback"));
            assertEquals(bukkit.getBoolean("value", false), view.getBoolean("value", false));
            assertEquals(bukkit.getBoolean("value", true), view.getBoolean("value", true));
            assertEquals(bukkit.getInt("value", 7), view.getInt("value", 7));
            assertEquals(bukkit.getLong("value", 9L), view.getLong("value", 9L));
            assertEquals(bukkit.getDouble("value", 2.5), view.getDouble("value", 2.5));
            assertEquals(bukkit.getStringList("value"), view.getStringList("value"));
            assertEquals(bukkit.getIntegerList("value"), view.getIntegerList("value"));
            comparisons += 9;
        }
        for (boolean populated : new boolean[] {false, true}) {
            var bukkit = new MemoryConfiguration(); var node = BasicConfigurationNode.root();
            if (populated) {
                var data = new LinkedHashMap<String, Object>();
                data.put("Host", "localhost"); data.put("Port", 3306); data.put("Username", "fixture");
                data.put("Password", "synthetic-fixture"); data.put("Database", "votes");
                data.put("Prefix", "test_"); data.put("Name", "users"); data.put("MaxConnections", 0);
                data.put("UseMariaDB", true); data.put("UseSSL", true); data.put("PoolName", "fixture");
                data.put("ConnectionTimeout", 1000);
                for (var entry : data.entrySet()) {
                    bukkit.set(entry.getKey(), entry.getValue()); node.node(entry.getKey()).raw(entry.getValue());
                }
            }
            var oldConfig = new MysqlConfigSpigot(bukkit);
            var sharedConfig = new MysqlConfigView(new ConfigurateConfigView(node));
            for (Method method : MysqlConfig.class.getDeclaredMethods()) {
                if (method.getParameterCount() == 0 && (method.getName().startsWith("get")
                        || method.getName().startsWith("is") || method.getName().startsWith("has"))) {
                    assertEquals(method.invoke(oldConfig), method.invoke(sharedConfig), method.getName());
                    comparisons++;
                }
            }
        }
        System.out.println("Bukkit/core configuration parity: " + comparisons + " comparisons passed");
    }
}
