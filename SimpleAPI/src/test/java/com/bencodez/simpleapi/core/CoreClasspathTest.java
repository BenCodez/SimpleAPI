package com.bencodez.simpleapi.core;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URLClassLoader;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import com.bencodez.simpleapi.sql.data.DataValueInt;
import com.bencodez.simpleapi.time.ParsedDuration;

class CoreClasspathTest {
    @Test void existingValueAndDurationClassesRemainUsableWithoutPlatforms() throws Exception {
        assertEquals(7, new DataValueInt(7).getInt());
        assertEquals(5000L, ParsedDuration.parse("5s", TimeUnit.SECONDS).getMillis());
        var project = DataValueInt.class.getProtectionDomain().getCodeSource().getLocation();
        try (var loader = new URLClassLoader(new java.net.URL[] {project}, ClassLoader.getPlatformClassLoader())) {
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass("org.bukkit.Bukkit"));
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass("net.md_5.bungee.api.ProxyServer"));
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass("com.velocitypowered.api.proxy.ProxyServer"));
            var value = loader.loadClass(DataValueInt.class.getName()).getConstructor(int.class).newInstance(7);
            assertEquals(7, value.getClass().getMethod("getInt").invoke(value));
        }
    }
}
