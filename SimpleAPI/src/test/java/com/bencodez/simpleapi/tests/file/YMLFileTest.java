package com.bencodez.simpleapi.tests.file;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;

import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import com.bencodez.simpleapi.file.YMLFile;
import com.bencodez.simpleapi.scheduler.BukkitScheduler;

public class YMLFileTest {

	@TempDir
	File tempDir;

	@Test
	public void testBasicYMLFileOperations() throws Exception {
		File tempFile = new File(tempDir, "test.yml");

		JavaPlugin mockPlugin = Mockito.mock(JavaPlugin.class);
		BukkitScheduler mockScheduler = Mockito.mock(BukkitScheduler.class);

		YMLFile ymlFile = new YMLFile(mockPlugin, tempFile, mockScheduler) {
			@Override
			public void onFileCreation() {
				// no-op
			}
		};

		// Setup should create file
		ymlFile.setup();
		assertTrue(tempFile.exists());
		assertTrue(ymlFile.isJustCreated());

		// Write value
		ymlFile.setValue("key", "value");

		// Reload
		ymlFile.reloadData();

		// Verify
		assertEquals("value", ymlFile.getData().getString("key"));
		assertFalse(ymlFile.isFailedToRead());
	}

	@Test
	public void testIgnoreCaseRead() {
		File tempFile = new File(tempDir, "case.yml");

		JavaPlugin plugin = Mockito.mock(JavaPlugin.class);
		BukkitScheduler scheduler = Mockito.mock(BukkitScheduler.class);

		YMLFile ymlFile = new YMLFile(plugin, tempFile, scheduler) {
			@Override
			public void onFileCreation() {
			}
		};

		ymlFile.setIgnoreCase(true);
		ymlFile.setup();

		ymlFile.getData().set("Rewards.Commands.Console", true);

		assertTrue(ymlFile.getData().getBoolean("rewards.commands.console"));
		assertTrue(ymlFile.getData().getBoolean("REWARDS.COMMANDS.CONSOLE"));
	}

	@Test
	public void testIgnoreCaseWrite() {
		File tempFile = new File(tempDir, "case-write.yml");

		JavaPlugin plugin = Mockito.mock(JavaPlugin.class);
		BukkitScheduler scheduler = Mockito.mock(BukkitScheduler.class);

		YMLFile ymlFile = new YMLFile(plugin, tempFile, scheduler) {
			@Override
			public void onFileCreation() {
			}
		};

		ymlFile.setIgnoreCase(true);
		ymlFile.setup();

		ymlFile.getData().set("rewards.commands.console", true);

		assertTrue(ymlFile.getData().getBoolean("Rewards.Commands.Console"));
	}

	@Test
	public void testToggleIgnoreCaseAfterLoad() {
		File tempFile = new File(tempDir, "toggle.yml");

		JavaPlugin plugin = Mockito.mock(JavaPlugin.class);
		BukkitScheduler scheduler = Mockito.mock(BukkitScheduler.class);

		YMLFile ymlFile = new YMLFile(plugin, tempFile, scheduler) {
			@Override
			public void onFileCreation() {
			}
		};

		ymlFile.setup();
		ymlFile.getData().set("Rewards.Commands.Console", true);

		// Default: case-sensitive
		assertFalse(ymlFile.getData().getBoolean("rewards.commands.console"));

		ymlFile.setIgnoreCase(true);

		// Now case-insensitive
		assertTrue(ymlFile.getData().getBoolean("rewards.commands.console"));
	}

}
