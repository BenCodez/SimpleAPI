package com.bencodez.simpleapi.tests.packaging;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarFile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.bencodez.simpleapi.servercomm.http.PackagedTlsSmoke;

/** Package-phase checks: Maven's dependency classpath must not mask missing shaded classes. */
public class FullArtifactTest {
    @TempDir Path temporary;

    @Test void omitsExternalCryptoProvider() throws Exception {
        Path full = fullJar();
        try (JarFile output = new JarFile(full.toFile())) {
            assertNotNull(output.getManifest(), "Full artifact must have a manifest");
            String classPath = output.getManifest().getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
            assertTrue(classPath == null || classPath.isBlank(), "Smoke test must not load external manifest dependencies");
            assertFalse(output.stream().anyMatch(entry -> entry.getName().startsWith("org/bouncycastle/")),
                    "The JDK-only TLS implementation must not package Bouncy Castle");
        }
        System.out.printf("Full artifact: %,d bytes; no external crypto provider packaged%n", Files.size(full));
    }

    @Test void packagedTlsWorksWithoutMavenDependencies() throws Exception {
        Path full = fullJar();
        String fixtureName = PackagedTlsSmoke.class.getName();
        String fixtureResource = fixtureName.replace('.', '/') + ".class";
        Path fixtureRoot = Files.createDirectory(temporary.resolve("fixture"));
        Path fixtureClass = fixtureRoot.resolve(fixtureResource);
        Files.createDirectories(fixtureClass.getParent());
        // Copy only this JDK-only fixture, not target/classes, all test classes, or dependency JARs.
        try (var input = PackagedTlsSmoke.class.getResourceAsStream("/" + fixtureResource)) {
            assertNotNull(input, "Missing compiled TLS fixture");
            Files.copy(input, fixtureClass);
        }
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        Path log = temporary.resolve("tls-smoke.log");
        Process process = new ProcessBuilder(java.toString(), "-cp", full + File.pathSeparator + fixtureRoot,
                fixtureName, temporary.resolve("identities").toString(), full.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Packaged TLS smoke timed out");
            String output;
            try (var input = Files.newInputStream(log)) {
                output = new String(input.readNBytes(64 * 1024), StandardCharsets.UTF_8);
            }
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains("Packaged TLS smoke passed"), output);
            System.out.print(output);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(10, TimeUnit.SECONDS), "TLS smoke process did not terminate");
            }
        }
    }

    private static Path fullJar() {
        String value = System.getProperty("simpleapi.fullJar");
        assertNotNull(value, "Run this test through the Maven package lifecycle");
        Path full = Path.of(value).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(full), "Missing packaged full artifact: " + full);
        return full;
    }

}
