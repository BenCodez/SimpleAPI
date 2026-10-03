package com.bencodez.simpleapi.sql.mysql;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.sql.*;
import java.util.Properties;
import java.util.concurrent.*;
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
        assertThrows(SQLException.class, manager::getConnectionChecked);
        assertNull(manager.getConnection());
        assertNull(manager.getDataSource());
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
