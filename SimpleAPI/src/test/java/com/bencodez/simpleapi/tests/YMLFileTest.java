
package com.bencodez.simpleapi.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;

import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.bencodez.simpleapi.file.YMLFile;
import com.bencodez.simpleapi.scheduler.BukkitScheduler;

public class YMLFileTest {

	@Test
	public void testYMLFileOperations() throws Exception {
		// Create a test directory inside the target folder
		File targetDir = new File("target");
		if (!targetDir.exists()) {
			targetDir.mkdirs();
		}
		// Create a temporary file
		File tempFile = Files.createTempFile(targetDir.toPath(), "test", ".yml").toFile();
		tempFile.deleteOnExit();

		// Mock JavaPlugin
		JavaPlugin mockPlugin = Mockito.mock(JavaPlugin.class);

		// Mock BukkitScheduler
		BukkitScheduler mockScheduler = Mockito.mock(BukkitScheduler.class);

		// Create an instance of YMLFile with the mocked scheduler
		YMLFile ymlFile = new YMLFile(mockPlugin, tempFile, mockScheduler) {
			@Override
			public void onFileCreation() {

			}
		};

		// Perform setup
		ymlFile.setup();

		// Assert that the file was created
		assertTrue(tempFile.exists());

		// Set a value
		ymlFile.setValue("key", "value");

		// Reload data
		ymlFile.reloadData();

		// Assert that the value was saved and reloaded correctly
		assertEquals("value", ymlFile.getData().getString("key"));
	}
}
