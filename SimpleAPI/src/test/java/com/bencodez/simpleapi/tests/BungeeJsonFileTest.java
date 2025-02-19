package com.bencodez.simpleapi.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import com.bencodez.simpleapi.file.BungeeJsonFile;
import com.google.gson.JsonObject;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class BungeeJsonFileTest {

	private File testFile;
	private BungeeJsonFile bungeeJsonFile;

	@BeforeAll
	public void setup() {
		// Create a temporary file for testing
		testFile = new File("test.json");
		bungeeJsonFile = new BungeeJsonFile(testFile);
	}

	@AfterAll
	public void cleanup() {
		// Delete the test file after tests are done
		if (testFile.exists()) {
			testFile.delete();
		}
	}

	@Test
	public void testSetAndGetInt() {
		bungeeJsonFile.setInt("test.int", 42);
		int result = bungeeJsonFile.getInt("test.int", 0);
		assertEquals(42, result);
	}

	@Test
	public void testSetAndGetString() {
		bungeeJsonFile.setString("test.string", "Hello, World!");
		String result = bungeeJsonFile.getString("test.string", "");
		assertEquals("Hello, World!", result);
	}

	@Test
	public void testSetAndGetBoolean() {
		bungeeJsonFile.setBoolean("test.boolean", true);
		boolean result = bungeeJsonFile.getBoolean("test.boolean", false);
		assertTrue(result);
	}

	@Test
	public void testSetAndGetLong() {
		bungeeJsonFile.setLong("test.long", 123456789L);
		long result = bungeeJsonFile.getLong("test.long", 0L);
		assertEquals(123456789L, result);
	}

	@Test
	public void testSetAndGetStringList() {
		List<String> list = Arrays.asList("one", "two", "three");
		bungeeJsonFile.setStringList("test.list", list);
		List<String> result = bungeeJsonFile.getStringList("test.list", null);
		assertEquals(list, result);
	}

	@Test
	public void testRemove() {
		bungeeJsonFile.setString("test.remove", "to be removed");
		bungeeJsonFile.remove("test.remove");
		String result = bungeeJsonFile.getString("test.remove", null);
		assertNull(result);
	}

	@Test
	public void testGetKeys() {
		bungeeJsonFile.setString("test.keys.one", "1");
		bungeeJsonFile.setString("test.keys.two", "2");
		List<String> keys = bungeeJsonFile.getKeys("test.keys");
		assertTrue(keys.contains("one"));
		assertTrue(keys.contains("two"));
	}

	@Test
	public void testGetKeys2() {
		bungeeJsonFile.setString("test.keys1.test.test.one", "1");
		bungeeJsonFile.setString("test.keys1.test.test.two", "2");
		List<String> keys = bungeeJsonFile.getKeys("test.keys1.test.test");
		assertTrue(keys.contains("one"));
		assertTrue(keys.contains("two"));
	}

	@Test
	public void testGetKeysBase() {
		bungeeJsonFile.setString("keys.one", "1");
		bungeeJsonFile.setString("keys.two", "2");
		List<String> keys = bungeeJsonFile.getKeys("keys");
		assertTrue(keys.contains("one"));
		assertTrue(keys.contains("two"));
	}

	@Test
	public void testGetKeysInvalidPath() {
		List<String> keys = bungeeJsonFile.getKeys("invalid.path");
		assertNotNull(keys);
		assertTrue(keys.isEmpty());
	}

	@Test
	public void getKeysAfterValueRemoved() {
		bungeeJsonFile.setString("test.keys2.remove.one", "1");
		bungeeJsonFile.setString("test.keys2.remove.two", "2");
		bungeeJsonFile.remove("test.keys2.remove.one");
		List<String> keys = bungeeJsonFile.getKeys("test.keys2.remove");
		assertNotNull(keys);
		assertEquals(1, keys.size());
		assertTrue(keys.contains("two"));
	}

	@Test
	public void removeNonExistentKey() {
		bungeeJsonFile.remove("non.existent.key");
		String result = bungeeJsonFile.getString("non.existent.key", null);
		assertNull(result);
	}

	@Test
	public void removeNestedKey() {
		bungeeJsonFile.setString("nested.key.one", "value1");
		bungeeJsonFile.setString("nested.key.two", "value2");
		bungeeJsonFile.remove("nested.key.one");
		List<String> keys = bungeeJsonFile.getKeys("nested.key");
		assertNotNull(keys);
		assertEquals(1, keys.size());
		assertTrue(keys.contains("two"));
	}

	@Test
	public void removeAllKeysInNode() {
	    bungeeJsonFile.setString("node.key.one", "value1");
	    bungeeJsonFile.setString("node.key.two", "value2");
	    bungeeJsonFile.remove("node"); 
	    List<String> keys = bungeeJsonFile.getKeys("node");
	    assertNotNull(keys);
	    assertTrue(keys.isEmpty());
	}

	@Test
	public void getKeysAfterAllValuesRemoved() {
		bungeeJsonFile.setString("test.keys3.remove.all.one", "1");
		bungeeJsonFile.setString("test.keys3.remove.all.two", "2");
		bungeeJsonFile.remove("test.keys3.remove.all.one");
		bungeeJsonFile.remove("test.keys3.remove.all.two");
		List<String> keys = bungeeJsonFile.getKeys("test.keys3.remove.all");
		assertNotNull(keys);
		assertTrue(keys.isEmpty());
	}

	@Test
	public void testGetNode() {
		bungeeJsonFile.setString("test.node.key", "value");
		JsonObject node = bungeeJsonFile.getNode("test.node").getAsJsonObject();
		assertNotNull(node);
		assertEquals("value", node.get("key").getAsString());
	}

	@Test
	public void testReload() {
		bungeeJsonFile.setString("test.reload", "before reload");
		bungeeJsonFile.save();
		bungeeJsonFile.setString("test.reload", "after reload");
		bungeeJsonFile.reload();
		String result = bungeeJsonFile.getString("test.reload", "");
		assertEquals("before reload", result);
	}
}
