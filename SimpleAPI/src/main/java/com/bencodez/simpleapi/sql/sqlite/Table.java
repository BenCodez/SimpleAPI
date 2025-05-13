
package com.bencodez.simpleapi.sql.sqlite;

import com.bencodez.simpleapi.sql.sqlite.db.SQLite;

public abstract class Table {
	public abstract String getName();

	public abstract String getQuery();

	public abstract SQLite getSqLite();

	public abstract void setSqLite(SQLite sqLite);
}
