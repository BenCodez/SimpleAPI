package com.bencodez.simpleapi.valuerequest;

import java.util.HashMap;
import java.util.Map;

import lombok.Getter;

/**
 * Stores values returned from a multi-value request.
 */
@Getter
public class MultiValueResult {

	private final Map<String, Object> values = new HashMap<String, Object>();

	public void set(String id, Object value) {
		values.put(id, value);
	}

	public String getString(String id) {
		Object value = values.get(id);
		return value != null ? String.valueOf(value) : null;
	}

	public Number getNumber(String id) {
		Object value = values.get(id);
		if (value instanceof Number) {
			return (Number) value;
		}
		return null;
	}

	public Boolean getBoolean(String id) {
		Object value = values.get(id);
		if (value instanceof Boolean) {
			return (Boolean) value;
		}
		return null;
	}
}