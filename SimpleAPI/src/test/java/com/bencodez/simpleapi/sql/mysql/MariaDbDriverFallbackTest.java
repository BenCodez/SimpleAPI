package com.bencodez.simpleapi.sql.mysql;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.bencodez.simpleapi.tests.shared.SharedRuntimeClasspath;

class MariaDbDriverFallbackTest {
    @TempDir Path directory;

    @Test void mysqlFallbackUsesMysqlSchemeAndKeepsConfiguredOptions() {
        ConnectionManager manager = manager(DbType.MARIADB);
        manager.setUseSSL(true);
        manager.setPublicKeyRetrieval(true);
        manager.setStr("&customOption=retained");
        String url = manager.buildJdbcUrl("com.mysql.cj.jdbc.Driver");
        assertTrue(url.startsWith("jdbc:mysql://localhost:3306/votes?"));
        assertTrue(url.contains("useSSL=true"));
        assertTrue(url.contains("allowPublicKeyRetrieval=true"));
        assertTrue(url.contains("&customOption=retained"));
        assertTrue(url.endsWith("&sslMode=REQUIRED"));
        assertEquals(DbType.MARIADB, manager.getDbType());
    }

    @Test void nativeMariaDbDriverRetainsMariaDbScheme() {
        assertTrue(manager(DbType.MARIADB).buildJdbcUrl("org.mariadb.jdbc.Driver").startsWith("jdbc:mariadb:"));
    }

    @Test void explicitMariaDbDriverOverridesMysqlTypeAsBefore() {
        assertTrue(manager(DbType.MYSQL).buildJdbcUrl("org.mariadb.jdbc.Driver").startsWith("jdbc:mariadb:"));
    }

    @Test void customMariaDbDriverRetainsConfiguredScheme() {
        assertTrue(manager(DbType.MARIADB).buildJdbcUrl("example.CustomDriver").startsWith("jdbc:mariadb:"));
    }

    @Test void mysqlAndPostgresqlSelectionsRemainUnchanged() {
        assertTrue(manager(DbType.MYSQL).buildJdbcUrl("com.mysql.cj.jdbc.Driver").startsWith("jdbc:mysql:"));
        assertTrue(manager(DbType.POSTGRESQL).buildJdbcUrl("org.postgresql.Driver").startsWith("jdbc:postgresql:"));
    }

    @Test void absentMariaDbDriverResolvesToDriverThatAcceptsGeneratedUrl() throws Exception {
        try (var loader = isolatedMysqlDriver()) {
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass("org.mariadb.jdbc.Driver"));
            Class<?> type = loader.loadClass(ConnectionManager.class.getName());
            Object manager = type.getConstructor(String.class, String.class, String.class, String.class, String.class)
                    .newInstance("localhost", "3306", "test", "", "votes");
            Class<?> dbType = loader.loadClass(DbType.class.getName());
            type.getMethod("setDbType", dbType).invoke(manager, dbType.getField("MARIADB").get(null));
            var resolve = type.getDeclaredMethod("resolveDriver"); resolve.setAccessible(true);
            assertEquals("com.mysql.cj.jdbc.Driver", resolve.invoke(manager));
            var build = type.getDeclaredMethod("buildJdbcUrl", String.class); build.setAccessible(true);
            String url = (String) build.invoke(manager, resolve.invoke(manager));
            java.sql.Driver driver = (java.sql.Driver) loader.loadClass("com.mysql.cj.jdbc.Driver")
                    .getConstructor().newInstance();
            assertTrue(driver.acceptsURL(url));
            type.getMethod("setMariadbFallbackToMysqlDriver", boolean.class).invoke(manager, false);
            InvocationTargetException rejected = assertThrows(InvocationTargetException.class, () -> resolve.invoke(manager));
            assertInstanceOf(ClassNotFoundException.class, rejected.getCause());
        }
    }

    private java.net.URLClassLoader isolatedMysqlDriver() throws Exception {
        Path source = directory.resolve("com/mysql/cj/jdbc/Driver.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.mysql.cj.jdbc;
                public class Driver implements java.sql.Driver {
                    public boolean acceptsURL(String url) { return url.startsWith("jdbc:mysql:"); }
                    public java.sql.Connection connect(String url, java.util.Properties p) { return null; }
                    public java.sql.DriverPropertyInfo[] getPropertyInfo(String u, java.util.Properties p) { return new java.sql.DriverPropertyInfo[0]; }
                    public int getMajorVersion() { return 1; }
                    public int getMinorVersion() { return 0; }
                    public boolean jdbcCompliant() { return false; }
                    public java.util.logging.Logger getParentLogger() { return java.util.logging.Logger.getGlobal(); }
                }
                """);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-proc:none", "-d", directory.toString(), source.toString()));
        return SharedRuntimeClasspath.open(ConnectionManager.class.getProtectionDomain().getCodeSource().getLocation(),
                directory.toUri().toURL());
    }

    private ConnectionManager manager(DbType type) {
        ConnectionManager result = new ConnectionManager("localhost", "3306", "test", "", "votes");
        result.setDbType(type);
        return result;
    }
}
