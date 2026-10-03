package com.bencodez.simpleapi.sql.mysql;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class MariaDbFallbackTlsTest {
    @Test void sslEnabledFallbackRequiresTls() {
        assertTrue(fallback().buildJdbcUrl("com.mysql.cj.jdbc.Driver").endsWith("&sslMode=REQUIRED"));
    }

    @Test void explicitStrongerModesRemainIntact() {
        for (String mode : new String[]{"REQUIRED", "VERIFY_CA", "VERIFY_IDENTITY"}) {
            ConnectionManager manager = fallback();
            manager.setStr("&sslMode=" + mode);
            assertTrue(manager.buildJdbcUrl("com.mysql.cj.jdbc.Driver").endsWith("&sslMode=" + mode));
        }
    }

    @Test void weakMalformedAndDuplicateModesFailClosed() {
        for (String options : new String[]{"&sslMode=DISABLED", "&sslMode=PREFERRED", "&sslMode=",
                "&sslMode=unknown", "&ssl%4dode=PREFERRED", "&sslMode=%50REFERRED",
                "&sslMode=REQUIRED&sslMode=DISABLED", "&sslMode=VERIFY_CA&sslMode=REQUIRED",
                "&sslMode=%GG"}) {
            ConnectionManager manager = fallback();
            manager.setStr(options);
            assertThrows(IllegalArgumentException.class, () -> manager.buildJdbcUrl("com.mysql.cj.jdbc.Driver"));
        }
    }

    @Test void legacyCertificateVerificationRemainsEnabled() {
        ConnectionManager manager = fallback();
        manager.setStr("&verifyServerCertificate=true&customOption=retained");
        assertTrue(manager.buildJdbcUrl("com.mysql.cj.jdbc.Driver").endsWith("&sslMode=VERIFY_CA"));
    }

    @Test void ordinaryMysqlNativeMariaAndSslDisabledRemainUnchanged() {
        ConnectionManager manager = fallback();
        assertFalse(manager.buildJdbcUrl("org.mariadb.jdbc.Driver").contains("sslMode="));
        manager.setDbType(DbType.MYSQL);
        assertFalse(manager.buildJdbcUrl("com.mysql.cj.jdbc.Driver").contains("sslMode="));
        manager.setDbType(DbType.MARIADB);
        manager.setUseSSL(false);
        manager.setStr("&sslMode=DISABLED");
        assertTrue(manager.buildJdbcUrl("com.mysql.cj.jdbc.Driver").endsWith("&sslMode=DISABLED"));
    }

    @Test void actualConnectorRejectsNonTlsGreetingBeforeSendingCredentials() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(5000);
            var worker = Executors.newSingleThreadExecutor();
            try {
                var peer = worker.submit(() -> {
                    try (var socket = server.accept()) {
                        socket.setSoTimeout(5000);
                        socket.getOutputStream().write(nonTlsGreeting());
                        socket.getOutputStream().flush();
                        return socket.getInputStream().read();
                    }
                });
                ConnectionManager manager = fallback();
                manager.setHost(server.getInetAddress().getHostAddress());
                manager.setPort(Integer.toString(server.getLocalPort()));
                manager.setStr("&connectTimeout=2000&socketTimeout=2000");
                Properties properties = new Properties();
                properties.setProperty("user", "test-user");
                properties.setProperty("password", "test-only-not-a-secret");
                SQLException failure = assertThrows(SQLException.class, () ->
                        new com.mysql.cj.jdbc.Driver().connect(manager.buildJdbcUrl("com.mysql.cj.jdbc.Driver"), properties));
                assertTrue(failure.getMessage().contains("SSL"), failure::getMessage);
                assertEquals(-1, peer.get(5, TimeUnit.SECONDS), "No authentication packet may be sent to a non-TLS peer");
            } finally {
                worker.shutdownNow();
            }
        }
    }

    private static ConnectionManager fallback() {
        ConnectionManager manager = new ConnectionManager("localhost", "3306", "test", "", "votes");
        manager.setDbType(DbType.MARIADB);
        manager.setUseSSL(true);
        return manager;
    }

    // Protocol-10 greeting with protocol-41, secure connection and plugin authentication,
    // deliberately omitting CLIENT_SSL. No actual database or external network is needed.
    private static byte[] nonTlsGreeting() {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.write(10);
        payload.writeBytes("8.0.0-test\0".getBytes(StandardCharsets.US_ASCII));
        payload.writeBytes(new byte[]{1, 0, 0, 0});
        payload.writeBytes("abcdefgh".getBytes(StandardCharsets.US_ASCII));
        payload.write(0);
        int capabilities = 1 | 512 | 8192 | 32768 | 0x80000;
        payload.write(capabilities & 255);
        payload.write((capabilities >>> 8) & 255);
        payload.write(45);
        payload.write(2);
        payload.write(0);
        payload.write((capabilities >>> 16) & 255);
        payload.write((capabilities >>> 24) & 255);
        payload.write(21);
        payload.writeBytes(new byte[10]);
        payload.writeBytes("ijklmnopqrst\0".getBytes(StandardCharsets.US_ASCII));
        payload.writeBytes("mysql_native_password\0".getBytes(StandardCharsets.US_ASCII));
        ByteArrayOutputStream packet = new ByteArrayOutputStream();
        packet.write(payload.size() & 255);
        packet.write((payload.size() >>> 8) & 255);
        packet.write((payload.size() >>> 16) & 255);
        packet.write(0);
        packet.writeBytes(payload.toByteArray());
        return packet.toByteArray();
    }
}
