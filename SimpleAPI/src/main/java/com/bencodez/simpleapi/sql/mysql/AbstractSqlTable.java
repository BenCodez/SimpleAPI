package com.bencodez.simpleapi.sql.mysql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.bencodez.simpleapi.sql.DataType;
import com.bencodez.simpleapi.sql.mysql.config.MysqlConfig;
import com.bencodez.simpleapi.sql.mysql.queries.Query;

public abstract class AbstractSqlTable {

	// ---- Required subclass hooks ----

	/** Used for cache + containsKey/etc. Example: "uuid" or "server". */
	public abstract String getPrimaryKeyColumn();

	/**
	 * Build the CREATE TABLE IF NOT EXISTS (or equivalent) for the chosen DbType.
	 * You can use {@link #qi(String)} to safely quote identifiers.
	 */
	public abstract String buildCreateTableSql(DbType dbType);

	/** Logging hooks (platform-specific implementations live in subclasses). */
	public abstract void logSevere(String msg);

	public abstract void logInfo(String msg);

	public abstract void debug(Throwable t);

	public abstract void debug(String messasge);

	// ---- Core state ----

	public final MySQL mysql;
	public final DbType dbType;

	public final String tableName;

	public final Object updateLock = new Object();
	public final Object addColumnLock = new Object();
	public final Object checkColumnLock = new Object();

	public final List<String> columns = Collections.synchronizedList(new ArrayList<>());
	public final Set<String> primaryKeys = ConcurrentHashMap.newKeySet();

	/**
	 * If you want int-vs-string decoding in getExact-like methods, subclasses can use this.
	 */
	public final List<String> intColumns = Collections.synchronizedList(new ArrayList<>());

	// ---- Constructors ----

	/**
	 * Use an already-connected MySQL wrapper (shared pool).
	 *
	 * @param deferInit if true, does NOT call ensureTable/loadBasicCaches. Subclass must call {@link #init()}
	 *                  after it finishes setting fields used by buildCreateTableSql/log hooks.
	 */
	public AbstractSqlTable(String tableName, MySQL existingMysql, boolean deferInit) {
		this.dbType = existingMysql.getConnectionManager().getDbType();
		this.tableName = tableName;
		this.mysql = existingMysql;

		if (!deferInit) {
			init();
		}
	}

	/**
	 * Create + connect a new pool from config (works on Spigot/Bungee/Velocity). (immediate init)
	 */
	public AbstractSqlTable(String baseTableName, MysqlConfig config, boolean debug) {
		this(baseTableName, config, debug, false);
	}

	/**
	 * Create + connect a new pool from config (works on Spigot/Bungee/Velocity).
	 *
	 * @param deferInit if true, does NOT call ensureTable/loadBasicCaches. Subclass must call {@link #init()}
	 *                  after it finishes setting fields used by buildCreateTableSql/log hooks.
	 */
	public AbstractSqlTable(String baseTableName, MysqlConfig config, boolean debug, boolean deferInit) {
		if (config == null) {
			throw new IllegalArgumentException("config cannot be null");
		}

		DbType type = config.getDbType();
		this.dbType = (type == null ? DbType.MYSQL : type);

		String resolved = baseTableName;
		if (config.hasTableNameSet()) {
			resolved = config.getTableName();
		}
		if (config.getTablePrefix() != null) {
			resolved = config.getTablePrefix() + resolved;
		}
		this.tableName = resolved;

		if (config.getPoolName() == null || config.getPoolName().isEmpty()) {
			config.setPoolName("SimpleAPI-" + resolved);
		}

		// Create the SimpleAPI wrapper; delegate logging to this table
		this.mysql = new com.bencodez.simpleapi.sql.mysql.MySQL(Math.max(1, config.getMaxThreads())) {
			@Override
			public void debug(SQLException e) {
				if (debug) {
					AbstractSqlTable.this.debug(e);
				}
			}

			@Override
			public void debug(String msg) {
				if (debug) {
					AbstractSqlTable.this.debug(msg);
				}
			}

			@Override
			public void severe(String string) {
				AbstractSqlTable.this.logSevere(string);
			}
		};

		boolean ok = this.mysql.connect(config);
		if (!ok) {
			logSevere("Failed to connect to database. host=" + config.getHostName() + " db=" + config.getDatabase());
		}

		// Keep the old behavior: USE db for MySQL/MariaDB
		if (this.dbType == DbType.MYSQL || this.dbType == DbType.MARIADB) {
			try {
				new Query(mysql, "USE `" + config.getDatabase() + "`;").executeUpdate();
			} catch (SQLException e) {
				logSevere("Failed to send USE database query: " + config.getDatabase()
						+ " (DB may still work depending on JDBC URL). Error: " + e.getMessage());
				debug(e);
			}
		}

		if (!deferInit) {
			init();
		}
	}

	/**
	 * Call this ONLY if you constructed with deferInit=true.
	 */
	protected final void init() {
		ensureTable();
		loadBasicCaches();
	}

	// ---- Column copy helper ----

	public void copyColumnData(String columnFromName, String columnToName) {
		copyColumnData(columnFromName, columnToName, DataType.STRING);
	}

	public void copyColumnData(String columnFromName, String columnToName, DataType dataType) {
		if (columnFromName == null || columnFromName.isEmpty() || columnToName == null || columnToName.isEmpty()) {
			return;
		}

		checkColumn(columnFromName, dataType);
		checkColumn(columnToName, dataType);

		String sql = "UPDATE " + qi(tableName) + " SET " + qi(columnToName) + " = " + qi(columnFromName) + ";";
		try {
			new Query(mysql, sql).executeUpdate();
		} catch (SQLException e) {
			debug(e);
		}
	}

	// ---- Public helpers ----

	public final String getTableName() {
		return tableName;
	}

	public final MySQL getMysql() {
		return mysql;
	}

	public final DbType getDbType() {
		return dbType;
	}

	public void close() {
		mysql.disconnect();
	}

	public boolean containsKey(String key) {
		if (key == null || key.isEmpty()) {
			return false;
		}
		return primaryKeys.contains(key) || containsKeyQuery(key);
	}

	public boolean containsKeyQuery(String key) {
		String sql = "SELECT 1 FROM " + qi(tableName) + " WHERE " + qi(getPrimaryKeyColumn()) + " = ? LIMIT 1;";
		try (Connection conn = mysql.getConnectionManager().getConnection();
				PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setString(1, key);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next();
			}
		} catch (SQLException e) {
			debug(e);
			return false;
		}
	}

	public List<String> getColumns() {
		if (columns.isEmpty()) {
			loadBasicCaches();
		}
		return columns;
	}

	public Set<String> getPrimaryKeys() {
		if (primaryKeys.isEmpty()) {
			loadBasicCaches();
		}
		return primaryKeys;
	}

	public void clearCaches() {
		columns.clear();
		primaryKeys.clear();
		loadBasicCaches();
	}

	// ---- Column management ----

	public void checkColumn(String column, DataType dataType) {
		if (column == null || column.isEmpty()) {
			return;
		}
		synchronized (checkColumnLock) {
			if (!containsIgnoreCase(getColumns(), column) && !containsIgnoreCase(getColumnsQuery(), column)) {
				addColumn(column, dataType);
			}
		}
	}

	/**
	 * FIXED: do not always add TEXT. Use DataType to pick a better initial type,
	 * so Postgres doesn't end up with vote_time/cached_total as TEXT.
	 */
	public void addColumn(String column, DataType dataType) {
		synchronized (addColumnLock) {
			String type = sqlTypeForAddColumn(dataType);
			String sql = "ALTER TABLE " + qi(tableName) + " ADD COLUMN " + qi(column) + " " + type + ";";
			try {
				new Query(mysql, sql).executeUpdate();
				columns.add(column);

				if (dataType == DataType.INTEGER && !intColumns.contains(column)) {
					intColumns.add(column);
				}
			} catch (SQLException e) {
				debug(e);
			}
		}
	}

	private String sqlTypeForAddColumn(DataType dataType) {
		if (dataType == null) {
			return stringTextType();
		}

		switch (dataType) {
		case INTEGER:
			// timestamps in millis fit in BIGINT
			return "BIGINT";
		case STRING:
		default:
			return stringTextType();
		}
	}

	public void alterColumnType(String column, String newType) {
		if (column == null || column.isEmpty() || newType == null || newType.isEmpty()) {
			return;
		}

		checkColumn(column, DataType.STRING);

		boolean needsAlter = true;
		try {
			needsAlter = columnNeedsAlter(column, newType);
		} catch (Exception e) {
			debug("Failed to inspect column " + tableName + "." + column + " - running ALTER anyway");
			debug(e instanceof Throwable ? (Throwable) e : new RuntimeException(String.valueOf(e)));
			needsAlter = true;
		}

		if (!needsAlter) {
			debug("Column " + tableName + "." + column + " already matches " + newType + " (skip ALTER)");
			if (newType.toUpperCase().contains("INT") && !intColumns.contains(column)) {
				intColumns.add(column);
			}
			return;
		}

		if (dbType == DbType.POSTGRESQL) {
			alterColumnTypePostgres(column, newType);
		} else {
			String sql = "ALTER TABLE " + qi(tableName) + " MODIFY " + qi(column) + " " + normaliseTypeForDb(newType)
					+ ";";
			try {
				new Query(mysql, sql).executeUpdateAsync();
			} catch (SQLException e) {
				debug(e);
			}
		}

		if (newType.toUpperCase().contains("INT") && !intColumns.contains(column)) {
			intColumns.add(column);
		}
	}

	/**
	 * FIXED: no external Connection needed.
	 */
	public boolean columnNeedsAlter(String column, String newType) throws SQLException {
		try (Connection conn = mysql.getConnectionManager().getConnection()) {
			if (dbType == DbType.POSTGRESQL) {
				return columnNeedsAlterPostgres(conn, column, newType);
			}
			return columnNeedsAlterMySql(conn, column, newType);
		}
	}

	// ---- Postgres ALTER helpers ----

	private void alterColumnTypePostgres(String column, String definition) {
		PostgresColumnDef def = parsePostgresDefinition(definition);

		// Important Postgres rules:
		// - "NULL" is NOT valid in TYPE clause. We ignore it (NULL is default anyway).
		// - text -> bigint/int/uuid often needs USING ...::type

		String usingExpr = buildPostgresUsingExpression(column, def.type);

		String typeSql = "ALTER TABLE " + qi(tableName) + " ALTER COLUMN " + qi(column) + " TYPE " + def.type
				+ (usingExpr != null ? " USING " + usingExpr : "") + ";";
		try {
			new Query(mysql, typeSql).executeUpdateAsync();
		} catch (SQLException e) {
			debug(e);
		}

		// NOT NULL: only apply if explicitly requested
		if (def.notNull) {
			String nnSql = "ALTER TABLE " + qi(tableName) + " ALTER COLUMN " + qi(column) + " SET NOT NULL;";
			try {
				new Query(mysql, nnSql).executeUpdateAsync();
			} catch (SQLException e) {
				debug(e);
			}
		}

		// DEFAULT: only apply if explicitly provided
		if (def.defaultExpr != null && !def.defaultExpr.trim().isEmpty()) {
			String dSql = "ALTER TABLE " + qi(tableName) + " ALTER COLUMN " + qi(column) + " SET DEFAULT "
					+ def.defaultExpr + ";";
			try {
				new Query(mysql, dSql).executeUpdateAsync();
			} catch (SQLException e) {
				debug(e);
			}
		}
	}

	/**
	 * Returns a USING expression for casts that Postgres won't do automatically.
	 *
	 * Numeric casts are guarded so junk text doesn't explode the migration.
	 * Empty-string becomes NULL for uuid/varchar numeric conversions.
	 */
	private String buildPostgresUsingExpression(String column, String targetType) {
		if (targetType == null || targetType.trim().isEmpty()) {
			return null;
		}

		String t = targetType.trim();
		String upper = t.toUpperCase();
		String col = qi(column);

		// UUID
		if (upper.equals("UUID")) {
			return "NULLIF(" + col + ", '')::uuid";
		}

		// BIGINT
		if (upper.equals("BIGINT")) {
			// If it's numeric text -> cast, else 0 (keeps your defaults sane)
			return "CASE WHEN trim(" + col + ") ~ '^-?\\d+$' THEN trim(" + col + ")::bigint ELSE 0 END";
		}

		// INT / INTEGER
		if (upper.equals("INT") || upper.equals("INTEGER")) {
			return "CASE WHEN trim(" + col + ") ~ '^-?\\d+$' THEN trim(" + col + ")::integer ELSE 0 END";
		}

		// SMALLINT
		if (upper.equals("SMALLINT")) {
			return "CASE WHEN trim(" + col + ") ~ '^-?\\d+$' THEN trim(" + col + ")::smallint ELSE 0 END";
		}

		// VARCHAR(n) / CHARACTER VARYING(n)
		if (upper.startsWith("VARCHAR(") || upper.startsWith("CHARACTER VARYING(")) {
			// cast is always safe
			return "CAST(" + col + " AS " + t + ")";
		}

		// TEXT
		if (upper.equals("TEXT")) {
			return "CAST(" + col + " AS text)";
		}

		// Default: attempt a cast (often safe)
		return "CAST(" + col + " AS " + t + ")";
	}

	private static final class PostgresColumnDef {
		final String type; // ex: BIGINT, VARCHAR(64), UUID
		final boolean notNull; // true if "NOT NULL" present
		final String defaultExpr; // raw default expression

		private PostgresColumnDef(String type, boolean notNull, String defaultExpr) {
			this.type = type;
			this.notNull = notNull;
			this.defaultExpr = defaultExpr;
		}
	}

	private PostgresColumnDef parsePostgresDefinition(String definition) {
		String raw = normaliseTypeForDb(definition).trim();
		String upper = raw.toUpperCase();

		boolean notNull = upper.contains(" NOT NULL");

		String defaultExpr = null;
		int defIdx = indexOfDefaultKeyword(upper);
		if (defIdx >= 0) {
			defaultExpr = raw.substring(defIdx + "DEFAULT".length()).trim();
			if (defaultExpr.startsWith("=")) {
				defaultExpr = defaultExpr.substring(1).trim();
			}
		}

		// TYPE is only up to NOT NULL / DEFAULT. Also explicitly ignore "NULL".
		int cut = raw.length();

		int nnIdx = upper.indexOf(" NOT NULL");
		if (nnIdx >= 0) {
			cut = Math.min(cut, nnIdx);
		}
		if (defIdx >= 0) {
			cut = Math.min(cut, defIdx);
		}

		// Some callers pass "... NULL" (MySQL-ish). Postgres doesn't want it.
		int nullIdx = upper.indexOf(" NULL");
		if (nullIdx >= 0) {
			cut = Math.min(cut, nullIdx);
		}

		String type = raw.substring(0, cut).trim();
		if (type.isEmpty()) {
			type = "TEXT";
		}

		return new PostgresColumnDef(type, notNull, defaultExpr);
	}

	private static int indexOfDefaultKeyword(String upper) {
		int idx = upper.indexOf(" DEFAULT ");
		if (idx >= 0) {
			return idx + 1;
		}
		idx = upper.indexOf(" DEFAULT\t");
		if (idx >= 0) {
			return idx + 1;
		}
		idx = upper.indexOf(" DEFAULT\n");
		if (idx >= 0) {
			return idx + 1;
		}
		idx = upper.indexOf(" DEFAULT");
		if (idx >= 0 && idx + " DEFAULT".length() == upper.length()) {
			return idx + 1;
		}
		return -1;
	}

	// ---- Internals ----

	public final void ensureTable() {
		String sql = buildCreateTableSql(dbType);
		if (sql == null || sql.trim().isEmpty()) {
			throw new IllegalStateException("buildCreateTableSql returned empty for " + getClass().getName());
		}
		try {
			new Query(mysql, sql).executeUpdate();
		} catch (SQLException e) {
			debug(e);
		}
	}

	public final void loadBasicCaches() {
		columns.clear();
		columns.addAll(getColumnsQuery());

		primaryKeys.clear();
		primaryKeys.addAll(getPrimaryKeysQuery());
	}

	public List<String> getPrimaryKeysQuery() {
		List<String> keys = new ArrayList<>();
		String sql = "SELECT " + qi(getPrimaryKeyColumn()) + " FROM " + qi(tableName) + ";";
		try (Connection conn = mysql.getConnectionManager().getConnection();
				PreparedStatement ps = conn.prepareStatement(sql);
				ResultSet rs = ps.executeQuery()) {
			while (rs.next()) {
				String v = rs.getString(1);
				if (v != null && !v.isEmpty()) {
					keys.add(v);
				}
			}
		} catch (SQLException e) {
			debug(e);
		}
		return keys;
	}

	public List<String> getColumnsQuery() {
		List<String> out = new ArrayList<>();
		if (dbType == DbType.POSTGRESQL) {
			String sql = "SELECT column_name FROM information_schema.columns "
					+ "WHERE table_schema = current_schema() AND table_name = ?;";
			try (Connection conn = mysql.getConnectionManager().getConnection();
					PreparedStatement ps = conn.prepareStatement(sql)) {
				ps.setString(1, tableName);
				try (ResultSet rs = ps.executeQuery()) {
					while (rs.next()) {
						out.add(rs.getString(1));
					}
				}
			} catch (SQLException e) {
				debug(e);
			}
			return out;
		}

		String sql = "SHOW COLUMNS FROM " + qi(tableName) + ";";
		try (Connection conn = mysql.getConnectionManager().getConnection();
				PreparedStatement ps = conn.prepareStatement(sql);
				ResultSet rs = ps.executeQuery()) {
			while (rs.next()) {
				out.add(rs.getString(1));
			}
		} catch (SQLException e) {
			debug(e);
		}
		return out;
	}

	// ---- SQL helpers ----

	public final String qi(String identifier) {
		if (identifier == null) {
			return "";
		}
		if (dbType == DbType.POSTGRESQL) {
			return "\"" + identifier.replace("\"", "\"\"") + "\"";
		}
		return "`" + identifier.replace("`", "``") + "`";
	}

	public final String stringTextType() {
		return (dbType == DbType.POSTGRESQL) ? "TEXT" : "text";
	}

	public final String normaliseTypeForDb(String type) {
		if (type == null) {
			return "";
		}
		String t = type.trim();
		if (dbType == DbType.POSTGRESQL) {
			String u = t.toUpperCase();
			if (u.equals("MEDIUMTEXT") || u.equals("LONGTEXT")) {
				return "TEXT";
			}
		}
		return t;
	}

	public static boolean containsIgnoreCase(List<String> list, String value) {
		if (list == null || value == null) {
			return false;
		}
		for (String s : list) {
			if (s != null && s.equalsIgnoreCase(value)) {
				return true;
			}
		}
		return false;
	}

	public final String bestUuidType() {
		return (dbType == DbType.POSTGRESQL) ? "UUID" : "VARCHAR(37)";
	}

	// ---- existing private helpers below (unchanged) ----

	private boolean columnNeedsAlterMySql(Connection conn, String column, String newType) throws SQLException {
		String sql = "SELECT DATA_TYPE, CHARACTER_MAXIMUM_LENGTH, COLUMN_DEFAULT FROM information_schema.COLUMNS "
				+ "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?;";

		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setString(1, tableName);
			ps.setString(2, column);
			try (ResultSet rs = ps.executeQuery()) {
				if (!rs.next()) {
					return true;
				}

				String dataType = rs.getString("DATA_TYPE");
				Object lenObj = rs.getObject("CHARACTER_MAXIMUM_LENGTH");
				Long length = (lenObj instanceof Number) ? ((Number) lenObj).longValue() : null;
				String defaultVal = rs.getString("COLUMN_DEFAULT");

				String typeUpper = newType.toUpperCase().trim();

				if (typeUpper.equals("MEDIUMTEXT")) {
					return !dataType.equalsIgnoreCase("mediumtext");
				}
				if (typeUpper.equals("LONGTEXT")) {
					return !dataType.equalsIgnoreCase("longtext");
				}
				if (typeUpper.equals("TEXT")) {
					return !dataType.equalsIgnoreCase("text");
				}

				if (typeUpper.startsWith("VARCHAR(")) {
					int open = typeUpper.indexOf('(');
					int close = typeUpper.indexOf(')', open + 1);
					if (open != -1 && close != -1) {
						int expectedLen;
						try {
							expectedLen = Integer.parseInt(typeUpper.substring(open + 1, close));
						} catch (NumberFormatException ex) {
							return true;
						}
						boolean typeMatches = dataType.equalsIgnoreCase("varchar");
						boolean lengthMatches = (length != null && length == expectedLen);
						return !(typeMatches && lengthMatches);
					}
					return true;
				}

				if (typeUpper.startsWith("INT")) {
					boolean isIntFamily = dataType.equalsIgnoreCase("int") || dataType.equalsIgnoreCase("integer")
							|| dataType.equalsIgnoreCase("mediumint") || dataType.equalsIgnoreCase("smallint")
							|| dataType.equalsIgnoreCase("tinyint") || dataType.equalsIgnoreCase("bigint");

					boolean expectDefaultZero = typeUpper.contains("DEFAULT '0'") || typeUpper.contains("DEFAULT 0");
					if (!expectDefaultZero) {
						return !isIntFamily;
					}
					boolean defaultIsZero = defaultVal != null && defaultVal.trim().equals("0");
					return !(isIntFamily && defaultIsZero);
				}

				return true;
			}
		}
	}

	private boolean columnNeedsAlterPostgres(Connection conn, String column, String newType) throws SQLException {
		String sql = "SELECT data_type, character_maximum_length FROM information_schema.columns "
				+ "WHERE table_schema = current_schema() AND table_name = ? AND column_name = ?;";

		String raw = normaliseTypeForDb(newType).trim();
		String upper = raw.toUpperCase();

		int cut = raw.length();
		int nnIdx = upper.indexOf(" NOT NULL");
		if (nnIdx >= 0) {
			cut = Math.min(cut, nnIdx);
		}
		int defIdx = upper.indexOf(" DEFAULT");
		if (defIdx >= 0) {
			cut = Math.min(cut, defIdx);
		}
		int nullIdx = upper.indexOf(" NULL");
		if (nullIdx >= 0) {
			cut = Math.min(cut, nullIdx);
		}

		String base = raw.substring(0, cut).trim();
		String baseUpper = base.toUpperCase();

		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setString(1, tableName);
			ps.setString(2, column);

			try (ResultSet rs = ps.executeQuery()) {
				if (!rs.next()) {
					return true;
				}

				String dataType = rs.getString(1); // text, bigint, integer, uuid, character varying...
				Object lenObj = rs.getObject(2);
				Long length = (lenObj instanceof Number) ? ((Number) lenObj).longValue() : null;

				if (baseUpper.equals("LONGTEXT") || baseUpper.equals("MEDIUMTEXT") || baseUpper.equals("TEXT")) {
					return !dataType.equalsIgnoreCase("text");
				}

				if (baseUpper.equals("UUID")) {
					return !dataType.equalsIgnoreCase("uuid");
				}

				if (baseUpper.startsWith("VARCHAR(")) {
					int open = baseUpper.indexOf('(');
					int close = baseUpper.indexOf(')', open + 1);
					if (open != -1 && close != -1) {
						int expectedLen;
						try {
							expectedLen = Integer.parseInt(baseUpper.substring(open + 1, close));
						} catch (NumberFormatException ex) {
							return true;
						}
						boolean typeMatches = dataType.equalsIgnoreCase("character varying");
						boolean lengthMatches = (length != null && length == expectedLen);
						return !(typeMatches && lengthMatches);
					}
					return true;
				}

				if (baseUpper.equals("BIGINT")) {
					return !dataType.equalsIgnoreCase("bigint");
				}

				if (baseUpper.equals("INT") || baseUpper.equals("INTEGER")) {
					return !dataType.equalsIgnoreCase("integer");
				}

				if (baseUpper.equals("SMALLINT")) {
					return !dataType.equalsIgnoreCase("smallint");
				}

				return true;
			}
		}
	}
}
