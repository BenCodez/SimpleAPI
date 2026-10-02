package com.bencodez.simpleapi.tests.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;

import com.bencodez.simpleapi.file.velocity.VelocityYMLFile;

public class VelocityYMLFileTest {

	private static final String TEST_FILE_PATH = "target/test.yml";
	private File testFile;

	@BeforeEach
	public void setUp() throws IOException {
		testFile = new File(TEST_FILE_PATH);
		if (testFile.getParentFile() != null && !testFile.getParentFile().exists()) {
			testFile.getParentFile().mkdirs();
		}
		if (!testFile.exists()) {
			testFile.createNewFile();
		}
	}

	@AfterEach
	public void tearDown() throws IOException {
		Files.deleteIfExists(Paths.get(TEST_FILE_PATH));
	}

	@Test
	void malformedReloadPreservesActiveSnapshotAndDiskThenRecovers() throws Exception {
		Files.writeString(testFile.toPath(), "BungeeMethod: MQTT\nCustom:\n  label: administrator\n");
		VelocityYMLFile config = new VelocityYMLFile(testFile);
		ConfigurationNode active = config.getData();
		String malformed = "BungeeMethod: [broken\n";
		Files.writeString(testFile.toPath(), malformed);
		org.junit.jupiter.api.Assertions.assertThrows(java.io.UncheckedIOException.class, config::reload);
		org.junit.jupiter.api.Assertions.assertSame(active, config.getData());
		assertEquals("MQTT", config.getNode("BungeeMethod").getString());
		assertEquals("administrator", config.getNode("Custom", "label").getString());
		assertEquals(malformed, Files.readString(testFile.toPath()));
		Files.writeString(testFile.toPath(), "BungeeMethod: REDIS\nCustom:\n  label: corrected\n");
		config.reload();
		assertEquals("REDIS", config.getNode("BungeeMethod").getString());
		assertEquals("corrected", config.getNode("Custom", "label").getString());
	}

	@Test
	void missingOrUnreadableReloadDoesNotPublishStartupDefaults() throws Exception {
		Files.writeString(testFile.toPath(), "BungeeMethod: MQTT\n");
		VelocityYMLFile config = new VelocityYMLFile(testFile);
		ConfigurationNode active = config.getData();
		Files.delete(testFile.toPath());
		org.junit.jupiter.api.Assertions.assertThrows(java.io.UncheckedIOException.class, config::reload);
		org.junit.jupiter.api.Assertions.assertSame(active, config.getData());
		Files.createDirectory(testFile.toPath());
		org.junit.jupiter.api.Assertions.assertThrows(java.io.UncheckedIOException.class, config::reload);
		org.junit.jupiter.api.Assertions.assertSame(active, config.getData());
	}

	@Test
	void missingStartupFileAndValidEmptyReloadKeepDefaultSemantics() throws Exception {
		Files.delete(testFile.toPath());
		VelocityYMLFile config = new VelocityYMLFile(testFile);
		assertTrue(testFile.isFile());
		assertEquals("default", config.getNode("missing").getString("default"));
		Files.writeString(testFile.toPath(), "BungeeMethod: MQTT\n");
		config.reload();
		assertEquals("MQTT", config.getNode("BungeeMethod").getString());
		Files.writeString(testFile.toPath(), "");
		config.reload();
		assertEquals("PLUGINMESSAGING", config.getNode("BungeeMethod").getString("PLUGINMESSAGING"));
	}

	@Test
	public void getBooleanReturnsDefaultWhenNodeDoesNotExist() {
		VelocityYMLFile velocityYMLFile = new VelocityYMLFile(testFile);
		ConfigurationNode node = velocityYMLFile.getNode("non", "existent", "path");
		assertFalse(velocityYMLFile.getBoolean(node, false));
	}

	@Test
	public void getBooleanReturnsValueWhenNodeExists() throws SerializationException {
		VelocityYMLFile velocityYMLFile = new VelocityYMLFile(testFile);
		ConfigurationNode node = velocityYMLFile.getNode("config", "enabled");
		node.set(true);
		assertTrue(velocityYMLFile.getBoolean(node, false));
	}

	@Test
	public void getIntReturnsDefaultWhenNodeDoesNotExist() {
		VelocityYMLFile velocityYMLFile = new VelocityYMLFile(testFile);
		ConfigurationNode node = velocityYMLFile.getNode("non", "existent", "path");
		assertEquals(42, velocityYMLFile.getInt(node, 42));
	}

	@Test
	public void getIntReturnsValueWhenNodeExists() throws SerializationException {
		VelocityYMLFile velocityYMLFile = new VelocityYMLFile(testFile);
		ConfigurationNode node = velocityYMLFile.getNode("config", "port");
		node.set(8080);
		assertEquals(8080, velocityYMLFile.getInt(node, 42));
	}

	@Test
	public void getStringReturnsDefaultWhenNodeDoesNotExist() {
		VelocityYMLFile velocityYMLFile = new VelocityYMLFile(testFile);
		ConfigurationNode node = velocityYMLFile.getNode("non", "existent", "path");
		assertEquals("default", velocityYMLFile.getString(node, "default"));
	}

	@Test
	public void getStringReturnsValueWhenNodeExists() throws SerializationException {
		VelocityYMLFile velocityYMLFile = new VelocityYMLFile(testFile);
		ConfigurationNode node = velocityYMLFile.getNode("config", "name");
		node.set("Velocity");
		assertEquals("Velocity", velocityYMLFile.getString(node, "default"));
	}

	@Test
	public void getStringListReturnsDefaultWhenNodeDoesNotExist() {
		VelocityYMLFile velocityYMLFile = new VelocityYMLFile(testFile);
		ConfigurationNode node = velocityYMLFile.getNode("non", "existent", "path");
		ArrayList<String> defaultList = new ArrayList<>(Arrays.asList("default"));
		assertEquals(defaultList, velocityYMLFile.getStringList(node, defaultList));
	}

	@Test
	public void getStringListReturnsValueWhenNodeExists() throws SerializationException {
		VelocityYMLFile velocityYMLFile = new VelocityYMLFile(testFile);
		ConfigurationNode node = velocityYMLFile.getNode("config", "list");
		ArrayList<String> list = new ArrayList<>(Arrays.asList("item1", "item2"));
		node.set(list);
		assertEquals(list, velocityYMLFile.getStringList(node, new ArrayList<>(Arrays.asList("default"))));
	}

	@Test
	public void getKeysReturnsEmptyListWhenNodeDoesNotExist() {
		VelocityYMLFile velocityYMLFile = new VelocityYMLFile(testFile);
		ConfigurationNode node = velocityYMLFile.getNode("non", "existent", "path");
		assertTrue(velocityYMLFile.getKeys(node).isEmpty());
	}

	@Test
	public void getKeysReturnsListOfKeysWhenNodeExists() throws SerializationException {
		VelocityYMLFile velocityYMLFile = new VelocityYMLFile(testFile);
		ConfigurationNode node = velocityYMLFile.getNode("config");

		node.node("key1").set("value1");
		node.node("key2").set("value2");

		List<String> keys = velocityYMLFile.getKeys(node);
		assertTrue(keys.contains("key1"));
		assertTrue(keys.contains("key2"));
	}
}
