package com.bencodez.simpleapi.tests.sql.mysql;

import com.bencodez.simpleapi.sql.mysql.ConnectionManager;
import com.bencodez.simpleapi.sql.mysql.MySQL;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.sql.SQLException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class MySQLDisconnectTest {
    static Stream<Throwable[]> failures() {
        return Stream.of(
                pair(null, null),
                pair(new IllegalStateException("pool close"), null),
                pair(new LinkageError("pool close"), null),
                pair(null, new IllegalStateException("worker shutdown")),
                pair(null, new LinkageError("worker shutdown")),
                pair(new IllegalStateException("pool close"), new LinkageError("worker shutdown")),
                pair(new LinkageError("pool close"), new IllegalStateException("worker shutdown")));
    }
    private static Throwable[] pair(Throwable pool, Throwable worker) { return new Throwable[] {pool, worker}; }
    @Test void everyCleanupIsAttemptedAndPrimaryFailureIsPreserved() throws Exception {
        for (Throwable[] failures : failures().toList()) checkCleanup(failures[0], failures[1]);
    }
    private void checkCleanup(Throwable poolFailure, Throwable workerFailure) throws Exception {
        var worker = mock(ExecutorService.class); var manager = mock(ConnectionManager.class);
        if (poolFailure != null) doThrow(poolFailure).when(manager).close();
        if (workerFailure != null) doThrow(workerFailure).when(worker).shutdown();
        try (var executors = mockStatic(Executors.class)) {
            executors.when(() -> Executors.newFixedThreadPool(anyInt())).thenReturn(worker);
            var mysql = mysql();
            var field = MySQL.class.getDeclaredField("connectionManager"); field.setAccessible(true); field.set(mysql, manager);
            Throwable primary = poolFailure == null ? workerFailure : poolFailure;
            if (primary == null) assertDoesNotThrow(mysql::disconnect);
            else {
                Throwable thrown = assertThrows(primary.getClass(), mysql::disconnect);
                assertSame(primary, thrown);
                if (poolFailure != null && workerFailure != null)
                    assertArrayEquals(new Throwable[] { workerFailure }, thrown.getSuppressed());
                else assertEquals(0, thrown.getSuppressed().length);
            }
            verify(manager).close(); verify(worker).shutdown();
        }
    }
    @Test void disconnectBeforeConnectAndRepeatedDisconnectAreSafe() {
        var mysql = mysql();
        assertDoesNotThrow(mysql::disconnect); assertDoesNotThrow(mysql::disconnect);
        assertTrue(mysql.getThreadPool().isShutdown());
    }
    private static MySQL mysql() {
        return new MySQL(1) {
            public void debug(SQLException failure) { fail("Unexpected diagnostics"); }
            public void debug(String message) { fail("Unexpected diagnostics"); }
            public void severe(String message) { fail("Unexpected diagnostics"); }
        };
    }
}
