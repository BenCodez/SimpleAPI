package com.bencodez.simpleapi.tests;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.bencodez.simpleapi.servercomm.codec.JsonEnvelope;
import com.bencodez.simpleapi.servercomm.codec.JsonEnvelopeCodec;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class JsonEnvelopeCodecTest {

	@Test
	public void encode_basicShape_hasExpectedKeys() {
		JsonEnvelope env = JsonEnvelope.builder("ping")
				.schema(2)
				.put("a", "1")
				.build();

		String json = JsonEnvelopeCodec.encode(env);

		JsonObject root = JsonParser.parseString(json).getAsJsonObject();
		assertEquals("ping", root.getAsJsonPrimitive("t").getAsString());
		assertEquals(2, root.getAsJsonPrimitive("v").getAsInt());

		JsonObject f = root.getAsJsonObject("f");
		assertNotNull(f);
		assertEquals("1", f.getAsJsonPrimitive("a").getAsString());
	}

	@Test
	public void encode_preservesFieldOrder_inJsonObject() {
		JsonEnvelope env = JsonEnvelope.builder("sc")
				.put("first", "1")
				.put("second", "2")
				.put("third", "3")
				.build();

		String json = JsonEnvelopeCodec.encode(env);
		JsonObject root = JsonParser.parseString(json).getAsJsonObject();
		JsonObject f = root.getAsJsonObject("f");

		assertArrayEquals(
				new String[] { "first", "second", "third" },
				f.keySet().toArray(new String[0])
		);
	}

	@Test
	public void encode_decode_roundTrip_keepsData() {
		Map<String, String> fields = new LinkedHashMap<>();
		fields.put("a", "1");
		fields.put("b", "hello");
		fields.put("c", "");

		JsonEnvelope env = new JsonEnvelope("route", 9, fields);

		String json = JsonEnvelopeCodec.encode(env);
		JsonEnvelope decoded = JsonEnvelopeCodec.decode(json);

		assertEquals("route", decoded.getSubChannel());
		assertEquals(9, decoded.getSchema());
		assertEquals(fields, decoded.getFields());
	}

	@Test
	public void decode_missingSchema_defaultsTo1() {
		String json = "{\"t\":\"abc\",\"f\":{\"x\":\"y\"}}";

		JsonEnvelope decoded = JsonEnvelopeCodec.decode(json);

		assertEquals("abc", decoded.getSubChannel());
		assertEquals(1, decoded.getSchema());

		Map<String, String> expected = new LinkedHashMap<>();
		expected.put("x", "y");
		assertEquals(expected, decoded.getFields());
	}

	@Test
	public void decode_missingFieldsObject_resultsInEmptyFields() {
		String json = "{\"t\":\"abc\",\"v\":3}";

		JsonEnvelope decoded = JsonEnvelopeCodec.decode(json);

		assertEquals("abc", decoded.getSubChannel());
		assertEquals(3, decoded.getSchema());
		assertTrue(decoded.getFields().isEmpty());
	}

	@Test
	public void encode_disableHtmlEscaping_doesNotEscapeAngleBrackets() {
		JsonEnvelope env = JsonEnvelope.builder("sc")
				.put("msg", "<tag>")
				.build();

		String json = JsonEnvelopeCodec.encode(env);

		assertTrue(json.contains("<tag>"));
		assertFalse(json.contains("\\u003c"));
		assertFalse(json.contains("\\u003e"));

		JsonEnvelope decoded = JsonEnvelopeCodec.decode(json);
		assertEquals("<tag>", decoded.getFields().get("msg"));
	}
}
