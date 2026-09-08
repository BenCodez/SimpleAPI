package com.bencodez.simpleapi.tests.shared;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.LinkedHashSet;
import java.util.Set;

/** Builds the native classpath from resolved dependency locations, never versioned Maven-cache paths. */
public final class SharedRuntimeClasspath {
    private SharedRuntimeClasspath() { }

    public static URLClassLoader open(URL project, URL... fixtures) throws Exception {
        Set<URL> urls = new LinkedHashSet<>();
        urls.add(project);
        for (URL fixture : fixtures) urls.add(fixture);
        for (String type : new String[] {
                "org.spongepowered.configurate.ConfigurationNode",
                "org.spongepowered.configurate.yaml.YamlConfigurationLoader",
                "io.leangen.geantyref.GenericTypeReflector",
                "net.kyori.option.Option",
                "com.zaxxer.hikari.HikariConfig",
                "org.slf4j.Logger" }) {
            Class<?> resolved = Class.forName(type, false, SharedRuntimeClasspath.class.getClassLoader());
            urls.add(resolved.getProtectionDomain().getCodeSource().getLocation());
        }
        return new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
    }

    public static void assertPlatformsAbsent() throws Exception {
        URL project = com.bencodez.simpleapi.core.config.AnnotationBinder.class
                .getProtectionDomain().getCodeSource().getLocation();
        try (URLClassLoader loader = open(project)) {
            requirePlatformsAbsent(loader);
        }
    }

    public static void requirePlatformsAbsent(ClassLoader loader) throws Exception {
        for (String type : new String[] { "org.bukkit.Bukkit", "net.minecraft.server.MinecraftServer",
                "net.md_5.bungee.api.ProxyServer", "com.velocitypowered.api.proxy.ProxyServer",
                "net.fabricmc.api.ModInitializer", "net.minecraftforge.fml.ModList" }) {
            try {
                loader.loadClass(type);
                throw new AssertionError("Platform leaked into shared classpath: " + type);
            } catch (ClassNotFoundException expected) { }
        }
    }
}
