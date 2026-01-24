package com.bencodez.simpleapi.servercomm.codec;

import com.google.gson.*;
import java.util.*;
import java.util.Map.Entry;

public final class JsonEnvelopeCodec {

	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

	/* ================= ENCODE ================= */

	public static String encode(JsonEnvelope env) {
		JsonObject root = new JsonObject();
		root.addProperty("t", env.getSubChannel());
		root.addProperty("v", env.getSchema());

		JsonObject fields = new JsonObject();
		for (Entry<String, String> e : env.getFields().entrySet()) {
			fields.addProperty(e.getKey(), e.getValue());
		}
		root.add("f", fields);

		return GSON.toJson(root);
	}

	/* ================= DECODE ================= */

	public static JsonEnvelope decode(String json) {
		JsonObject root = JsonParser.parseString(json).getAsJsonObject();

		String type = root.getAsJsonPrimitive("t").getAsString();
		int schema = root.has("v") ? root.getAsJsonPrimitive("v").getAsInt() : 1;

		Map<String, String> fields = new LinkedHashMap<>();
		JsonObject f = root.getAsJsonObject("f");
		if (f != null) {
			for (Entry<String, JsonElement> e : f.entrySet()) {
				fields.put(e.getKey(), e.getValue().getAsString());
			}
		}

		return new JsonEnvelope(type, schema, fields);
	}
}
