package com.bencodez.simpleapi.sql.mysql;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.sql.*;
import java.util.Properties;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;

class ConnectionManagerPoolTest {
    @Test void borrowTimeoutKeepsPoolAndRecoversAfterLeasesAreReleased() throws Exception {
        for (DbType type : DbType.values()) verifyBorrowRecovery(type);
    }

    private void verifyBorrowRecovery(DbType type) throws Exception {
        ConnectionManager manager = manager(type);
        HikariDataSource pool = mock(HikariDataSource.class);
        setPool(manager, pool);
        SQLException exhausted = new SQLTransientConnectionException("controlled pool exhaustion");
        Connection recovered = mock(Connection.class);
        when(pool.getConnection()).thenThrow(exhausted).thenThrow(exhausted).thenReturn(recovered);
        assertSame(exhausted, assertThrows(SQLException.class, manager::getConnectionChecked));
        assertNull(manager.getConnection());
        assertSame(pool, manager.getDataSource());
        verify(pool, never()).close();
        assertSame(recovered, manager.getConnectionChecked());
    }

    @Test void explicitReplacementClosesPredecessorAndShutdownClosesSuccessor() throws Exception {
        ConnectionManager manager = manager(DbType.MYSQL);
        try (var pools = mockConstruction(HikariDataSource.class)) {
            assertTrue(manager.open());
            HikariDataSource first = pools.constructed().get(0);
            assertTrue(manager.open());
            HikariDataSource second = pools.constructed().get(1);
            assertSame(second, manager.getDataSource());
            verify(first).close();
            verify(second, never()).close();
            manager.close();
            verify(second).close();
        }
    }

    @Test void publicPoolSetterPreservesCallerOwnership() {
        ConnectionManager manager = manager(DbType.MYSQL);
        HikariDataSource first = mock(HikariDataSource.class);
        HikariDataSource second = mock(HikariDataSource.class);
        manager.setDataSource(first);
        manager.setDataSource(first);
        verify(first, never()).close();
        manager.setDataSource(second);
        verify(first, never()).close();
        verify(second, never()).close();
        manager.setDataSource(null);
        verify(first, never()).close();
        verify(second, never()).close();
        assertTrue(manager.isClosed());
    }

    @Test void failedReplacementKeepsWorkingPredecessor() throws Exception {
        ConnectionManager manager = manager(DbType.MYSQL);
        HikariDataSource old = mock(HikariDataSource.class);
        Connection available = mock(Connection.class);
        when(old.getConnection()).thenReturn(available);
        setPool(manager, old);
        try (var pools = mockConstruction(HikariDataSource.class, (pool, context) -> {
            throw new IllegalStateException("controlled initialization failure");
        })) {
            assertFalse(manager.open());
            assertSame(old, manager.getDataSource());
            verify(old, never()).close();
            assertSame(available, manager.getConnectionChecked());
        }
    }

    @Test void initializationFailurePreservesLegacyNullAndCheckedFailure() throws Exception {
        ConnectionManager manager = manager(DbType.MYSQL);
        manager.setMysqlDriver("missing.probe.Driver");
        SQLException failure = assertThrows(SQLException.class, manager::getConnectionChecked);
        assertNotNull(failure.getCause());
        assertInstanceOf(ClassNotFoundException.class, failure.getCause());
        assertSame(failure.getCause(), manager.getLastOpenFailure());
        assertNull(manager.getConnection());
        assertNull(manager.getDataSource());
    }

    @Test void failedLegacyConnectRetainsCauseAndAllowsAsyncRecovery() throws Exception {
        MySQL mysql = new MySQL(1) {
            public void debug(SQLException e) { fail("failure diagnostics must not call subclass hooks"); }
            public void debug(String message) { fail("failure diagnostics must not call subclass hooks"); }
            public void severe(String message) { fail("failure diagnostics must not call subclass hooks"); }
        };
        var config = new com.bencodez.simpleapi.sql.mysql.config.MysqlConfig();
        config.setHostName("localhost");
        config.setPort(3306);
        config.setUser("test");
        config.setPass("secret");
        config.setDatabase("test");
        config.setDriver("missing.probe.Driver");
        try (var managers = mockConstruction(ConnectionManager.class, (manager, context) -> {
            when(manager.open()).thenReturn(false);
            when(manager.getLastOpenFailure()).thenReturn(new SQLException("controlled outage"));
            Connection recovered = mock(Connection.class);
            PreparedStatement statement = mock(PreparedStatement.class);
            when(manager.getConnectionChecked()).thenReturn(recovered);
            when(recovered.prepareStatement(anyString())).thenReturn(statement);
            when(statement.executeUpdate()).thenReturn(1);
            when(statement.executeBatch()).thenReturn(new int[] { 2 });
        })) {
            assertFalse(mysql.connect(config));
            assertNotNull(mysql.getLastConnectFailure());
            assertFalse(mysql.getThreadPool().isShutdown());
            var update = new CompletableFuture<Integer>();
            var batch = new CompletableFuture<int[]>();
            var query = new com.bencodez.simpleapi.sql.mysql.queries.Query(mysql, "UPDATE votes SET total=1");
            query.executeUpdateAsync((result, failure) -> {
                if (failure != null) update.completeExceptionally(failure); else update.complete(result);
            });
            query.executeBatchAsync((result, failure) -> {
                if (failure != null) batch.completeExceptionally(failure); else batch.complete(result);
            });
            assertEquals(1, update.get(2, TimeUnit.SECONDS));
            assertArrayEquals(new int[] { 2 }, batch.get(2, TimeUnit.SECONDS));
            verify(managers.constructed().get(0), times(2)).getConnectionChecked();
        } finally {
            mysql.disconnect();
            assertTrue(mysql.getThreadPool().awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test void externalConnectRetainsSuccessfulDebugCallback() {
        AtomicInteger callbacks = new AtomicInteger();
        MySQL mysql = new MySQL(1) {
            public void debug(SQLException e) { }
            public void debug(String message) { callbacks.incrementAndGet(); }
            public void severe(String message) { }
        };
        var config = new com.bencodez.simpleapi.sql.mysql.config.MysqlConfig();
        config.setDriver(TestDriver.class.getName());
        config.setDebug(true);
        try (var pools = mockConstruction(HikariDataSource.class)) {
            assertTrue(mysql.connect(config));
        }
        assertEquals(1, callbacks.get());
    }

    @Test void externalConnectRetainsFailureDebugWithoutCredentials() {
        java.util.List<String> messages = new java.util.ArrayList<>();
        MySQL mysql = new MySQL(1) {
            public void debug(SQLException e) { fail("unexpected exception callback"); }
            public void debug(String message) { messages.add(message); }
            public void severe(String message) { fail("unexpected severe callback"); }
        };
        var config = new com.bencodez.simpleapi.sql.mysql.config.MysqlConfig();
        config.setDebug(true);
        config.setPass("private-password");
        try (var managers = mockConstruction(ConnectionManager.class, (manager, context) -> {
            when(manager.open()).thenReturn(false);
            when(manager.getDbType()).thenReturn(DbType.MYSQL);
        })) {
            assertFalse(mysql.connect(config));
            assertEquals(java.util.List.of("DB connection failed. type=MYSQL Check host/port/credentials and timeouts."), messages);
            messages.clear();
            assertFalse(mysql.connect(config, false));
            assertTrue(messages.isEmpty());
        } finally {
            mysql.disconnect();
        }
    }

    @Test void closedPoolIsReopenedAndConnectionIsReturned() throws Exception {
        ConnectionManager manager = manager(DbType.MYSQL);
        HikariDataSource closed = mock(HikariDataSource.class);
        when(closed.isClosed()).thenReturn(true);
        setPool(manager, closed);
        Connection connection = mock(Connection.class);
        try (var pools = mockConstruction(HikariDataSource.class,
                (pool, context) -> when(pool.getConnection()).thenReturn(connection))) {
            assertSame(connection, manager.getConnectionChecked());
            assertSame(pools.constructed().get(0), manager.getDataSource());
            verify(closed).close();
        }
    }

    @Test void blockedBorrowDoesNotHoldLifecycleMonitor() throws Exception {
        ConnectionManager manager = manager(DbType.MYSQL);
        HikariDataSource pool = mock(HikariDataSource.class);
        setPool(manager, pool);
        CountDownLatch borrowed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(pool.getConnection()).thenAnswer(invocation -> {
            borrowed.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("release not signalled");
            throw new SQLTransientConnectionException("pool closed while waiting");
        });
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> borrow = workers.submit(() -> assertThrows(SQLException.class, manager::getConnectionChecked));
            assertTrue(borrowed.await(5, TimeUnit.SECONDS));
            workers.submit(manager::close).get(5, TimeUnit.SECONDS);
            verify(pool).close();
            release.countDown();
            borrow.get(5, TimeUnit.SECONDS);
            assertSame(pool, manager.getDataSource());
        } finally {
            release.countDown(); workers.shutdownNow();
        }
    }

    @Test void predecessorCleanupFailureDoesNotMisreportPublishedSuccessor() throws Exception {
        ConnectionManager manager = manager(DbType.MYSQL);
        HikariDataSource old = mock(HikariDataSource.class);
        doThrow(new IllegalStateException("controlled cleanup failure")).when(old).close();
        setPool(manager, old);
        try (var pools = mockConstruction(HikariDataSource.class)) {
            assertTrue(manager.open());
            assertSame(pools.constructed().get(0), manager.getDataSource());
        }
    }

    @Test void queryAcquisitionFailureUsesExistingSqlFailurePath() throws Exception {
        MySQL mysql = mock(MySQL.class);
        ConnectionManager manager = mock(ConnectionManager.class);
        when(mysql.getConnectionManager()).thenReturn(manager);
        when(manager.getConnectionChecked()).thenThrow(new SQLTransientConnectionException("exhausted"));
        var query = new com.bencodez.simpleapi.sql.mysql.queries.Query(mysql, "UPDATE example SET value=1");
        assertDoesNotThrow(query::executeUpdate);
        verify(manager).getConnectionChecked();
        verify(manager, never()).getConnection();
    }

    private static ConnectionManager manager(DbType type) {
        ConnectionManager result = new ConnectionManager("localhost", "3306", "test", "", "test");
        result.setDbType(type); result.setMysqlDriver(TestDriver.class.getName());
        return result;
    }
    private static void setPool(ConnectionManager manager, HikariDataSource pool) throws Exception {
        var field = ConnectionManager.class.getDeclaredField("dataSource");
        field.setAccessible(true); field.set(manager, pool);
    }
    public static class TestDriver implements Driver {
        public Connection connect(String u, Properties p) { return null; }
        public boolean acceptsURL(String u) { return true; }
        public DriverPropertyInfo[] getPropertyInfo(String u, Properties p) { return new DriverPropertyInfo[0]; }
        public int getMajorVersion() { return 1; } public int getMinorVersion() { return 0; }
        public boolean jdbcCompliant() { return false; }
        public Logger getParentLogger() { return Logger.getGlobal(); }
    }
}
