package com.bencodez.simpleapi.tests.sql.mysql;

import com.bencodez.simpleapi.sql.mysql.ConnectionManager;
import com.bencodez.simpleapi.tests.support.TestReflection;

/** Exercises the original package-private URL builder without changing its visibility. */
final class SqlTestAccess {
    private SqlTestAccess() { }
    static String jdbcUrl(ConnectionManager manager, String driver) {
        return TestReflection.call(manager, "buildJdbcUrl", driver);
    }
}
