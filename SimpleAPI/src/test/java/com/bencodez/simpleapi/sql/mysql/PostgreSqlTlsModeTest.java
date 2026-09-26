package com.bencodez.simpleapi.sql.mysql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PostgreSqlTlsModeTest {
	private ConnectionManager postgres() {
		ConnectionManager manager = new ConnectionManager("db.example.test", "5432", "user", "password", "votes");
		manager.setDbType(DbType.POSTGRESQL);
		return manager;
	}

	@Test void legacyModePreservesBothUseSslSettingsAndRawLine() {
		ConnectionManager manager = postgres();
		assertEquals("jdbc:postgresql://db.example.test:5432/votes?reWriteBatchedInserts=true",
				manager.buildJdbcUrl("org.postgresql.Driver"));
		manager.setUseSSL(true);
		assertEquals("jdbc:postgresql://db.example.test:5432/votes?reWriteBatchedInserts=true&sslmode=require",
				manager.buildJdbcUrl("org.postgresql.Driver"));
		manager.setStr("?sslmode=disable");
		assertEquals("jdbc:postgresql://db.example.test:5432/votes?sslmode=disable&reWriteBatchedInserts=true&sslmode=require",
				manager.buildJdbcUrl("org.postgresql.Driver"));
	}

	@Test void explicitModeControlsPostgresTlsRegardlessOfUseSsl() {
		ConnectionManager manager = postgres();
		manager.setPostgreSqlTlsMode(PostgreSqlTlsMode.VERIFY_FULL);
		manager.setStr("?sslrootcert=/etc/db/ca.pem&applicationName=votes");
		String verified = "jdbc:postgresql://db.example.test:5432/votes?sslrootcert=/etc/db/ca.pem"
				+ "&applicationName=votes&reWriteBatchedInserts=true&sslmode=verify-full&gssEncMode=disable";
		assertEquals(verified, manager.buildJdbcUrl("org.postgresql.Driver"));
		manager.setUseSSL(true);
		assertEquals(verified, manager.buildJdbcUrl("org.postgresql.Driver"));
		manager.setPostgreSqlTlsMode(PostgreSqlTlsMode.REQUIRE);
		manager.setStr("&applicationName=votes");
		assertEquals("jdbc:postgresql://db.example.test:5432/votes?reWriteBatchedInserts=true&sslmode=require&gssEncMode=disable&applicationName=votes",
				manager.buildJdbcUrl("org.postgresql.Driver"));
		manager.setPostgreSqlTlsMode(PostgreSqlTlsMode.DISABLE);
		manager.setStr("");
		assertEquals("jdbc:postgresql://db.example.test:5432/votes?reWriteBatchedInserts=true&sslmode=disable",
				manager.buildJdbcUrl("org.postgresql.Driver"));
	}

	@Test void explicitModeRejectsConflictingRawLineOptions() {
		ConnectionManager manager = postgres();
		manager.setPostgreSqlTlsMode(PostgreSqlTlsMode.VERIFY_FULL);
		for (String line : new String[] {"?sslmode=disable", "&SSLMODE=require", "ssl%6dode=disable",
				"?applicationName=votes&ssl=false", "&sslfactory=com.example.TrustAll",
				"sslhostnameverifier=com.example.TrustAll", "gssEncMode=prefer"}) {
			manager.setStr(line);
			assertThrows(IllegalArgumentException.class, () -> manager.buildJdbcUrl("org.postgresql.Driver"), line);
		}
		manager.setStr("?ssl%zz=disable");
		assertThrows(IllegalArgumentException.class, () -> manager.buildJdbcUrl("org.postgresql.Driver"));
	}

	@Test void mysqlAndMariaDbKeepLegacyUrlsAndRejectPostgresModes() {
		ConnectionManager manager = postgres();
		manager.setDbType(DbType.MYSQL);
		String mysqlUrl = manager.buildJdbcUrl("com.mysql.cj.jdbc.Driver");
		assertEquals("jdbc:mysql://db.example.test:5432/votes?useSSL=false&allowMultiQueries=true"
				+ "&rewriteBatchedStatements=true&useDynamicCharsetInfo=false&allowPublicKeyRetrieval=false"
				+ "&tcpKeepAlive=true&connectTimeout=10000&socketTimeout=30000&serverTimezone=UTC", mysqlUrl);
		manager.setPostgreSqlTlsMode(PostgreSqlTlsMode.VERIFY_FULL);
		assertThrows(IllegalArgumentException.class, () -> manager.buildJdbcUrl("com.mysql.cj.jdbc.Driver"));
		manager.setDbType(DbType.MARIADB);
		assertThrows(IllegalArgumentException.class, () -> manager.buildJdbcUrl("org.mariadb.jdbc.Driver"));
		manager.setPostgreSqlTlsMode(PostgreSqlTlsMode.LEGACY);
		assertEquals(mysqlUrl.replace("jdbc:mysql:", "jdbc:mariadb:"),
				manager.buildJdbcUrl("org.mariadb.jdbc.Driver"));
	}

	@Test void parsesModeNamesAndRejectsUnknownValues() {
		assertEquals(PostgreSqlTlsMode.LEGACY, PostgreSqlTlsMode.fromString(null));
		assertEquals(PostgreSqlTlsMode.LEGACY, PostgreSqlTlsMode.fromString(" "));
		assertEquals(PostgreSqlTlsMode.VERIFY_FULL, PostgreSqlTlsMode.fromString(" verify-full "));
		assertEquals(PostgreSqlTlsMode.VERIFY_FULL, PostgreSqlTlsMode.fromString("verify_full"));
		assertThrows(IllegalArgumentException.class, () -> PostgreSqlTlsMode.fromString("verify-ca"));
	}
}
