package com.bencodez.simpleapi.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.bencodez.simpleapi.file.velocity.VelocityYMLFile;

import ninja.leaping.configurate.ConfigurationNode;

public class VelocityYMLFileTest {

	@Test
	public void getBooleanReturnsDefaultWhenNodeDoesNotExist() {
		VelocityYMLFile velocityYMLFile = new VelocityYMLFile(new File("test.yml"));
		ConfigurationNode node = velocityYMLFile.getNode("non.existent.path");
		assertFalse(velocityYMLFile.getBoolean(node, false));
	}

	@Test
	public void getBooleanReturnsValueWhenNodeExists() {
		VelocityYMLFile velocityYMLFile = new VelocityYMLFile(new File("test.yml"));
		ConfigurationNode node = velocityYMLFile.getNode("config.enabled");
		node.setValue(true);
		assertTrue(velocityYMLFile.getBoolean(node, false));
	}

	@Test
	public void getIntReturnsDefaultWhenNodeDoesNotExist() {
		VelocityYMLFile velocityYMLFile = new VelocityYMLFile(new File("test.yml"));
		ConfigurationNode node = velocityYMLFile.getNode("non.existent.path");
		assertEquals(42, velocityYMLFile.getInt(node, 42));
	}

	@Test
	public void getIntReturnsValueWhenNodeExists() {
		VelocityYMLFile velocityYMLFile = new VelocityYMLFile(new File("test.yml"));
		ConfigurationNode node = velocityYMLFile.getNode("config.port");
		node.setValue(8080);
		assertEquals(8080, velocityYMLFile.getInt(node, 42));
	}

	@Test
	public void getStringReturnsDefaultWhenNodeDoesNotExist() {
		VelocityYMLFile velocityYMLFile = new VelocityYMLFile(new File("test.yml"));
		ConfigurationNode node = velocityYMLFile.getNode("non.existent.path");
		assertEquals("default", velocityYMLFile.getString(node, "default"));
	}

	@Test
	public void getStringReturnsValueWhenNodeExists() {
		VelocityYMLFile velocityYMLFile = new VelocityYMLFile(new File("test.yml"));
		ConfigurationNode node = velocityYMLFile.getNode("config.name");
		node.setValue("Velocity");
		assertEquals("Velocity", velocityYMLFile.getString(node, "default"));
	}

	@Test
	public void getStringListReturnsDefaultWhenNodeDoesNotExist() {
		VelocityYMLFile velocityYMLFile = new VelocityYMLFile(new File("test.yml"));
		ConfigurationNode node = velocityYMLFile.getNode("non.existent.path");
		ArrayList<String> defaultList = new ArrayList<>(Arrays.asList("default"));
		assertEquals(defaultList, velocityYMLFile.getStringList(node, defaultList));
	}

	@Test
	public void getStringListReturnsValueWhenNodeExists() {
		VelocityYMLFile velocityYMLFile = new VelocityYMLFile(new File("test.yml"));
		ConfigurationNode node = velocityYMLFile.getNode("config.list");
		ArrayList<String> list = new ArrayList<>(Arrays.asList("item1", "item2"));
		node.setValue(list);
		assertEquals(list, velocityYMLFile.getStringList(node, new ArrayList<>(Arrays.asList("default"))));
	}

	@Test
	public void getKeysReturnsEmptyListWhenNodeDoesNotExist() {
		VelocityYMLFile velocityYMLFile = new VelocityYMLFile(new File("test.yml"));
		ConfigurationNode node = velocityYMLFile.getNode("non.existent.path");
		assertTrue(velocityYMLFile.getKeys(node).isEmpty());
	}

	@Test
	public void getKeysReturnsListOfKeysWhenNodeExists() {
		VelocityYMLFile velocityYMLFile = new VelocityYMLFile(new File("test.yml"));
		ConfigurationNode node = velocityYMLFile.getNode("config");
		node.getNode("key1").setValue("value1");
		node.getNode("key2").setValue("value2");
		List<String> keys = velocityYMLFile.getKeys(node);
		assertTrue(keys.contains("key1"));
		assertTrue(keys.contains("key2"));
	}

}
