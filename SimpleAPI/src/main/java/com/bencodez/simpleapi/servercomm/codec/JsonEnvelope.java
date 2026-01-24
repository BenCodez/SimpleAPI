package com.bencodez.simpleapi.servercomm.codec;

import java.util.*;
import java.util.Map.Entry;

public final class JsonEnvelope {

	private final String subChannel;
	private final int schema;
	private final Map<String, String> fields;

	public JsonEnvelope(String subChannel, int schema, Map<String, String> fields) {
		this.subChannel = Objects.requireNonNull(subChannel, "subChannel");
		this.schema = schema;
		this.fields = new LinkedHashMap<>(fields); // preserve unknowns + order
	}

	/**
	 * SubChannel used for routing (maps directly to GlobalMessageHandler subChannel)
	 */
	public String getSubChannel() {
		return subChannel;
	}

	public int getSchema() {
		return schema;
	}

	public Map<String, String> getFields() {
		return Collections.unmodifiableMap(fields);
	}

	public Builder toBuilder() {
		return new Builder(subChannel).schema(schema).putAll(fields);
	}

	public static Builder builder(String subChannel) {
		return new Builder(subChannel);
	}

	public static final class Builder {
		private final String subChannel;
		private int schema = 1;
		private final Map<String, String> fields = new LinkedHashMap<>();

		private Builder(String subChannel) {
			this.subChannel = Objects.requireNonNull(subChannel, "subChannel");
		}

		public Builder schema(int schema) {
			this.schema = schema;
			return this;
		}

		public Builder put(String key, Object value) {
			if (key == null || key.isEmpty()) {
				throw new IllegalArgumentException("key is blank");
			}
			fields.put(key, value == null ? "" : String.valueOf(value));
			return this;
		}

		public Builder putAll(Map<String, String> map) {
			for (Entry<String, String> e : map.entrySet()) {
				put(e.getKey(), e.getValue());
			}
			return this;
		}

		public JsonEnvelope build() {
			return new JsonEnvelope(subChannel, schema, fields);
		}
	}
}
