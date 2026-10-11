package com.bencodez.simpleapi.sql.mysql;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.Properties;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;

import com.bencodez.simpleapi.sql.mysql.config.MysqlConfig;

class AbstractSqlTableInitializationTest {
    @Test
    void deferredExistingPoolConstructorDoesNotNeedSubclassState() {
        MySQL mysql = mock(MySQL.class);
        ConnectionManager manager = mock(ConnectionManager.class);
        when(mysql.getConnectionManager()).thenReturn(manager);
        when(manager.getDbType()).thenReturn(DbType.MYSQL);
        DeferredTable table = new DeferredTable("votes", mysql);
        assertFalse(table.initialized);
        table.initialized = true;
        assertEquals("votes", table.getTableName());
    }
    @Test
    void authenticationFailureStopsConstructorBeforeSubclassHooksOrQueries() {
        RejectingTable.hookCalls = 0;
        RejectingDriver.connectionAttempts = 0;
        MysqlConfig config = config();
        config.setDriver(RejectingDriver.class.getName());

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new RejectingTable("votes", config, true));
        assertTrue(hasCause(failure, AUTH_FAILURE));
        assertEquals(0, RejectingTable.hookCalls);
        assertTrue(RejectingDriver.connectionAttempts > 0);
    }

    @Test void databaseSelectionFailureClosesPoolWithoutCallingSubclassHooks() throws Exception {
        SQLException denied = new SQLException("controlled database selection rejected", "42000");
        RejectingTable.hookCalls = 0;
        try (var managers = mockConstruction(ConnectionManager.class, (manager, context) -> {
            when(manager.open()).thenReturn(true);
            Connection connection = mock(Connection.class);
            when(manager.getConnectionChecked()).thenReturn(connection);
            when(connection.prepareStatement(anyString())).thenThrow(denied);
        })) {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> new RejectingTable("votes", config(), true));
            assertSame(denied, failure.getCause());
            assertEquals(0, RejectingTable.hookCalls);
            verify(managers.constructed().get(0)).close();
        }
    }

    @Test void uncheckedDatabaseSelectionFailuresKeepIdentityAndCleanupFailures() throws Exception {
        for (Throwable failure : new Throwable[] {new IllegalArgumentException("driver rejected selection"),
                new LinkageError("driver linkage failure")}) {
            RuntimeException cleanupFailure = new IllegalStateException("controlled cleanup failure");
            RejectingTable.hookCalls = 0;
            try (var managers = mockConstruction(ConnectionManager.class, (manager, context) -> {
                when(manager.open()).thenReturn(true);
                Connection connection = mock(Connection.class);
                when(manager.getConnectionChecked()).thenReturn(connection);
                when(connection.prepareStatement(anyString())).thenThrow(failure);
                doThrow(cleanupFailure).when(manager).close();
            })) {
                Throwable thrown = assertThrows(failure.getClass(),
                        () -> new RejectingTable("votes", config(), true));
                assertSame(failure, thrown);
                assertArrayEquals(new Throwable[] {cleanupFailure}, thrown.getSuppressed());
                assertEquals(0, RejectingTable.hookCalls);
                verify(managers.constructed().get(0)).close();
                verify(managers.constructed().get(0).getConnectionChecked()).close();
            }
        }
    }

    @Test void databaseSelectionEscapesQuotedDatabaseNames() throws Exception {
        MysqlConfig config = config();
        config.setDatabase("vote`data");
        try (var managers = mockConstruction(ConnectionManager.class, (manager, context) -> {
            when(manager.open()).thenReturn(true);
            Connection connection = mock(Connection.class);
            when(manager.getConnectionChecked()).thenReturn(connection);
            when(connection.prepareStatement("USE `vote``data`;"))
                    .thenReturn(mock(java.sql.PreparedStatement.class));
        })) {
            AbstractSqlTable table = new AbstractSqlTable("votes", config, true, true) {
                public String getPrimaryKeyColumn() { return "id"; }
                public String buildCreateTableSql(DbType type) { return "CREATE TABLE votes (id TEXT)"; }
                public void logSevere(String message) { fail("early hook"); }
                public void logInfo(String message) { fail("early hook"); }
                public void debug(Throwable failure) { fail("early hook"); }
                public void debug(String message) { fail("early hook"); }
            };
            verify(managers.constructed().get(0).getConnectionChecked())
                    .prepareStatement("USE `vote``data`;");
            table.close();
        }
    }

    private static MysqlConfig config() {
        MysqlConfig config = new MysqlConfig();
        config.setHostName("127.0.0.1");
        config.setPort(3306);
        config.setUser("user");
        config.setPass("password");
        config.setDatabase("database");
        config.setMinimumIdle(0);
        config.setMaxThreads(1);
        config.setConnectionTimeout(1000);
        return config;
    }

    private static boolean hasCause(Throwable failure, String message) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (message.equals(current.getMessage())) return true;
        }
        return false;
    }

    private static final String AUTH_FAILURE = "controlled authentication rejected";

    private static final class DeferredTable extends AbstractSqlTable {
        boolean initialized;
        DeferredTable(String name, MySQL mysql) { super(name, mysql, true); }
        public String getPrimaryKeyColumn() { return "id"; }
        public String buildCreateTableSql(DbType type) { return "CREATE TABLE votes (id TEXT)"; }
        public void logSevere(String message) { if (!initialized) fail("early hook"); }
        public void logInfo(String message) { if (!initialized) fail("early hook"); }
        public void debug(Throwable failure) { if (!initialized) fail("early hook"); }
        public void debug(String message) { if (!initialized) fail("early hook"); }
    }

    private static final class RejectingTable extends AbstractSqlTable {
        static int hookCalls;

        RejectingTable(String name, MysqlConfig config, boolean debug) {
            super(name, config, debug);
        }

        public String getPrimaryKeyColumn() { return "id"; }
        public String buildCreateTableSql(DbType type) { hookCalls++; return "CREATE TABLE votes (id TEXT)"; }
        public void logSevere(String message) { hookCalls++; }
        public void logInfo(String message) { hookCalls++; }
        public void debug(Throwable failure) { hookCalls++; }
        public void debug(String message) { hookCalls++; }
    }

    public static final class RejectingDriver implements Driver {
        static int connectionAttempts;

        public Connection connect(String url, Properties properties) throws SQLException {
            connectionAttempts++;
            throw new SQLException(AUTH_FAILURE);
        }
        public boolean acceptsURL(String url) { return url != null && url.startsWith("jdbc:"); }
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties properties) { return new DriverPropertyInfo[0]; }
        public int getMajorVersion() { return 1; }
        public int getMinorVersion() { return 0; }
        public boolean jdbcCompliant() { return false; }
        public Logger getParentLogger() { return Logger.getGlobal(); }
    }
}
