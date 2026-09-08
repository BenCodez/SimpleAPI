package com.bencodez.simpleapi.core;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.bencodez.simpleapi.sql.data.DataValueInt;
import com.bencodez.simpleapi.time.ParsedDuration;
import java.util.concurrent.TimeUnit;

class CoreClasspathTest {
    @Test void existingValueAndDurationClassesRemainUsableWithoutPlatforms() {
        assertEquals(7, new DataValueInt(7).getInt());
        assertEquals(5000L, ParsedDuration.parse("5s", TimeUnit.SECONDS).getMillis());
        assertThrows(ClassNotFoundException.class, () -> Class.forName("org.bukkit.Bukkit"));
        assertThrows(ClassNotFoundException.class, () -> Class.forName("net.md_5.bungee.api.ProxyServer"));
        assertThrows(ClassNotFoundException.class, () -> Class.forName("com.velocitypowered.api.proxy.ProxyServer"));
    }
}
