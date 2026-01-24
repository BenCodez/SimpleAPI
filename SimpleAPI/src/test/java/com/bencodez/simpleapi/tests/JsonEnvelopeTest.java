package com.bencodez.simpleapi.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.bencodez.simpleapi.servercomm.codec.JsonEnvelope;

public class JsonEnvelopeTest {

	@Test
	public void builder_defaults_schemaTo1() {
		JsonEnvelope env = JsonEnvelope.builder("test").build();
		assertEquals("test", env.getSubChannel());
		assertEquals(1, env.getSchema());
		assertTrue(env.getFields().isEmpty());
	}

	@Test
	public void builder_put_nullValue_becomesEmptyString() {
		JsonEnvelope env = JsonEnvelope.builder("sc").put("k", null).build();
		assertEquals("", env.getFields().get("k"));
	}

	@Test
	public void builder_put_objectValue_toStringStored() {
		JsonEnvelope env = JsonEnvelope.builder("sc").put("n", 123).put("b", true).build();
		assertEquals("123", env.getFields().get("n"));
		assertEquals("true", env.getFields().get("b"));
	}

	@Test
	public void builder_put_blankKey_throws() {
		assertThrows(IllegalArgumentException.class, () -> JsonEnvelope.builder("sc").put("", "x"));
		assertThrows(IllegalArgumentException.class, () -> JsonEnvelope.builder("sc").put(null, "x"));
	}

	@Test
	public void constructor_preservesInsertionOrder_andCopiesMap() {
		Map<String, String> input = new LinkedHashMap<>();
		input.put("a", "1");
		input.put("b", "2");

		JsonEnvelope env = new JsonEnvelope("sc", 7, input);

		// order preserved
		assertEquals("a", env.getFields().keySet().toArray()[0]);
		assertEquals("b", env.getFields().keySet().toArray()[1]);

		// defensive copy (mutating original should not affect env)
		input.put("c", "3");
		assertFalse(env.getFields().containsKey("c"));
	}

	@Test
	public void getFields_isUnmodifiable() {
		JsonEnvelope env = JsonEnvelope.builder("sc").put("a", "1").build();
		assertThrows(UnsupportedOperationException.class, () -> env.getFields().put("b", "2"));
	}

	@Test
	public void toBuilder_roundTrip_keepsAllValues() {
		JsonEnvelope original = JsonEnvelope.builder("sc")
				.schema(5)
				.put("a", "1")
				.put("b", "2")
				.build();

		JsonEnvelope rebuilt = original.toBuilder().build();

		assertEquals(original.getSubChannel(), rebuilt.getSubChannel());
		assertEquals(original.getSchema(), rebuilt.getSchema());
		assertEquals(original.getFields(), rebuilt.getFields());
	}
}
