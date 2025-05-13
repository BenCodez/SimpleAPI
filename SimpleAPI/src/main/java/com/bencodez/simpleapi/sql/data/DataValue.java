package com.bencodez.simpleapi.sql.data;

import com.bencodez.simpleapi.sql.DataType;

public interface DataValue {
	public boolean getBoolean();

	public int getInt();

	public String getString();

	public DataType getType();

	public String getTypeName();

	public boolean isBoolean();

	public boolean isInt();

	public boolean isString();
}
