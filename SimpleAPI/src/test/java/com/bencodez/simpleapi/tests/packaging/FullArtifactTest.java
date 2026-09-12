package com.bencodez.simpleapi.tests.packaging;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarFile;

import org.bouncycastle.asn1.cms.ContentInfo;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.bencodez.simpleapi.servercomm.http.PackagedTlsSmoke;

/** Package-phase checks: Maven's dependency classpath must not mask missing shaded classes. */
public class FullArtifactTest {
    @TempDir Path temporary;

    @Test void retainsBaseCryptoClassesWithoutUnusableVersionedPayload() throws Exception {
        Path full = fullJar();
        long removedEntries = 0;
        long removedCompressedBytes = 0;
        long retainedEntries = 0;
        try (JarFile output = new JarFile(full.toFile())) {
            assertNotNull(output.getManifest(), "Full artifact must have a manifest");
            assertFalse(Boolean.parseBoolean(output.getManifest().getMainAttributes()
                    .getValue(Attributes.Name.MULTI_RELEASE)),
                    "Revisit the BC filter before making the full artifact multi-release");
            String classPath = output.getManifest().getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
            assertTrue(classPath == null || classPath.isBlank(), "Smoke test must not load external manifest dependencies");
            assertFalse(output.stream().anyMatch(entry -> entry.getName().startsWith("META-INF/versions/")),
                    "A non-multi-release artifact must not bundle unreachable versioned implementations");

            // Resolve all three original libraries from Maven, without a pinned version or ~/.m2 path.
            for (Class<?> anchor : List.of(BouncyCastleProvider.class, X509CertificateHolder.class, ContentInfo.class)) {
                Path source = Path.of(anchor.getProtectionDomain().getCodeSource().getLocation().toURI());
                assertFalse(Files.isSameFile(source, full), "Expected Maven's original dependency for comparison");
                try (JarFile dependency = new JarFile(source.toFile())) {
                    for (var entry : dependency.stream().filter(entry -> !entry.isDirectory()).toList()) {
                        if (entry.getName().startsWith("META-INF/versions/")) {
                            removedEntries++;
                            removedCompressedBytes += entry.getCompressedSize();
                        } else if (entry.getName().startsWith("org/bouncycastle/")) {
                            assertNotNull(output.getEntry(entry.getName()), "Lost base dependency entry: " + entry.getName());
                            retainedEntries++;
                        }
                    }
                }
            }
        }
        assertTrue(retainedEntries > 0, "No base crypto entries were checked");
        // This is input ZIP payload, not an invented before/after output-JAR size.
        System.out.printf("Full artifact: %,d bytes; retained %,d base BC entries; omitted %,d versioned entries "
                + "(%,d compressed bytes in upstream dependency JARs)%n",
                Files.size(full), retainedEntries, removedEntries, removedCompressedBytes);
    }

    @Test void thinArtifactContainsProjectClassesWithoutEmbeddedDependencies() throws Exception {
        Path thin = thinJar();
        try (JarFile artifact = new JarFile(thin.toFile())) {
            assertNotNull(artifact.getEntry("com/bencodez/simpleapi/servercomm/http/HttpTlsIdentity.class"),
                    "Thin artifact must preserve the complete SimpleAPI API");
            assertNotNull(artifact.getEntry("com/bencodez/simpleapi/scheduler/BukkitScheduler.class"));
            assertNull(artifact.getEntry("org/bouncycastle/jce/provider/BouncyCastleProvider.class"));
            assertNull(artifact.getEntry("com/zaxxer/hikari/HikariDataSource.class"));
            assertNull(artifact.getEntry("redis/clients/jedis/Jedis.class"));
            assertNull(artifact.getEntry("org/spongepowered/configurate/ConfigurationNode.class"));
            assertFalse(artifact.stream().anyMatch(entry -> entry.getName().startsWith("META-INF/versions/")));
        }
        System.out.printf("Thin artifact: %,d bytes (project classes only)%n", Files.size(thin));
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

    private static Path thinJar() {
        String value = System.getProperty("simpleapi.thinJar");
        assertNotNull(value, "Run this test through the Maven package lifecycle");
        Path thin = Path.of(value).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(thin), "Missing packaged thin artifact: " + thin);
        return thin;
    }
}
