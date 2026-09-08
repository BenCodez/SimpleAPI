package com.bencodez.simpleapi.tests.shared;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;

/** Runs in the package phase after both JARs exist, not against unfiltered target/classes. */
public class SharedArtifactTest {
    private static final List<String> FORBIDDEN = List.of("org/bukkit/", "org/spigotmc/", "io/papermc/paper/",
            "net/minecraft/", "net/fabricmc/", "net/minecraftforge/", "net/neoforged/",
            "net/md_5/bungee/", "com/velocitypowered/api/", "com/bencodez/simpleapi/bukkit/");

    @Test void packagedSharedLibraryLinksAndRunsWithoutPlatformApis() throws Exception {
        Path shared = Path.of(System.getProperty("simpleapi.sharedJar"));
        Path full = Path.of(System.getProperty("simpleapi.fullJar"));
        assertTrue(Files.isRegularFile(shared), "Missing shared classifier");
        assertTrue(Files.isRegularFile(full), "Missing legacy distribution");
        Path sources = shared.resolveSibling(shared.getFileName().toString().replace(".jar", "-sources.jar"));
        assertTrue(Files.isRegularFile(sources), "Missing shared sources");
        List<String> classes = new ArrayList<>();
        try (JarFile jar = new JarFile(shared.toFile()); JarFile all = new JarFile(full.toFile());
                JarFile sourceJar = new JarFile(sources.toFile())) {
            assertNotNull(all.getEntry("com/bencodez/simpleapi/file/annotation/AnnotationHandler.class"));
            assertNotNull(all.getEntry("com/bencodez/simpleapi/bukkit/config/AnnotationHandler.class"));
            assertNotNull(jar.getEntry("com/bencodez/simpleapi/core/config/YamlConfigDocument.class"));
            for (var entry : jar.stream().toList()) {
                if (!entry.getName().endsWith(".class")) continue;
                assertTrue(entry.getName().startsWith("com/bencodez/simpleapi/"), "Thin artifact contains external classes");
                String constants = new String(jar.getInputStream(entry).readAllBytes(), StandardCharsets.ISO_8859_1);
                for (String prefix : FORBIDDEN) {
                    assertFalse(entry.getName().contains(prefix) || constants.contains(prefix),
                            "Platform reference in " + entry.getName() + ": " + prefix);
                }
                assertNotNull(all.getEntry(entry.getName()), "Full distribution lost " + entry.getName());
                String outer = entry.getName().replaceFirst("\\$.*\\.class$", ".class").replace(".class", ".java");
                assertNotNull(sourceJar.getEntry(outer), "Missing source for " + entry.getName());
                classes.add(entry.getName().replace('/', '.').replace(".class", ""));
            }
        }
        assertFalse(classes.isEmpty());
        try (URLClassLoader loader = SharedRuntimeClasspath.open(shared.toUri().toURL(),
                NativeConfigFixture.class.getProtectionDomain().getCodeSource().getLocation())) {
            SharedRuntimeClasspath.requirePlatformsAbsent(loader);
            for (String name : classes) {
                Class<?> type = Class.forName(name, false, loader);
                assertSame(loader, type.getClassLoader());
                type.getDeclaredConstructors(); type.getDeclaredMethods(); type.getDeclaredFields();
                type.getGenericSuperclass(); type.getGenericInterfaces();
            }
            Class<?> fixture = loader.loadClass(NativeConfigFixture.class.getName());
            assertSame(loader, fixture.getClassLoader());
            fixture.getMethod("run").invoke(null);
        }
        System.out.println("Shared classifier: " + classes.size() + " classes linked; native configuration smoke passed");
    }
}
