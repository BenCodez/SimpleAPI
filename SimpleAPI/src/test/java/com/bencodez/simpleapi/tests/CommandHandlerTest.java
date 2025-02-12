
package com.bencodez.simpleapi.tests;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.bencodez.simpleapi.command.CommandHandler;
import com.bencodez.simpleapi.scheduler.BukkitScheduler;

public class CommandHandlerTest {

	private JavaPlugin mockPlugin;
	private CommandHandler commandHandler;
	private CommandSender mockSender;
	@SuppressWarnings("unused")
	private Player mockPlayer;
	private BukkitScheduler mockScheduler;

	@BeforeEach
	public void setup() {
		mockPlugin = mock(JavaPlugin.class);
		mockSender = mock(CommandSender.class);
		mockPlayer = mock(Player.class);
		mockScheduler = mock(BukkitScheduler.class);
		commandHandler = new CommandHandler(mockPlugin) {
			@Override
			public void debug(String debug) {
			}

			@Override
			public void execute(CommandSender sender, String[] args) {
			}

			@Override
			public String formatNoPerms() {
				return "No permission";
			}

			@Override
			public String formatNotNumber() {
				return "Not a number";
			}

			@Override
			public BukkitScheduler getBukkitScheduler() {
				return mockScheduler;
			}

			@Override
			public String getHelpLine() {
				return "Help line";
			}
		};
	}

	@Test
	public void runCommandWithCorrectArgs() {
		String[] args = { "arg1", "arg2" };
		commandHandler.withArgs("arg1", "arg2");
		commandHandler.withPerm(""); // Ensure perm is initialized
		assertTrue(commandHandler.runCommand(mockSender, args));
	}

	@Test
	public void runCommandWithIncorrectArgs() {
		String[] args = { "arg1" };
		commandHandler.withArgs("arg1", "arg2");
		commandHandler.withPerm(""); // Ensure perm is initialized
		assertFalse(commandHandler.runCommand(mockSender, args));
	}

	@Test
	public void runCommandWithNoPermission() {
		String[] args = { "arg1", "arg2" };
		commandHandler.withArgs("arg1", "arg2");
		commandHandler.withPerm("some.permission");
		when(mockSender.hasPermission("some.permission")).thenReturn(false);
		assertTrue(commandHandler.runCommand(mockSender, args));
	}

	@Test
	public void runCommandWithPermission() {
		String[] args = { "arg1", "arg2" };
		commandHandler.withArgs("arg1", "arg2");
		commandHandler.withPerm("some.permission");
		when(mockSender.hasPermission("some.permission")).thenReturn(true);
		assertTrue(commandHandler.runCommand(mockSender, args));
	}

	@Test
	public void runCommandAsConsoleWhenNotAllowed() {
		String[] args = { "arg1", "arg2" };
		commandHandler.withArgs("arg1", "arg2");
		commandHandler.noConsole();
		assertTrue(commandHandler.runCommand(mockSender, args));
	}

	@Test
	public void runCommandAsPlayerWhenForcedConsole() {
		String[] args = { "arg1", "arg2" };
		commandHandler.withArgs("arg1", "arg2");
		commandHandler.setForceConsole(true);
		doNothing().when(mockSender).sendMessage(anyString()); // Use doNothing() for void method
		assertTrue(commandHandler.runCommand(mockSender, args));
	}

	@Test
	public void getTabCompleteOptionsWithPermission() {
		String[] args = { "arg1" };
		commandHandler.withArgs("arg1");
		commandHandler.withPerm(""); // Ensure perm is initialized
		ConcurrentHashMap<String, ArrayList<String>> tabCompleteOptions = new ConcurrentHashMap<>();
		tabCompleteOptions.put("arg1", new ArrayList<>(Arrays.asList("option1", "option2")));
		when(mockSender.hasPermission(anyString())).thenReturn(true);
		ArrayList<String> options = commandHandler.getTabCompleteOptions(mockSender, args, 0, tabCompleteOptions);
		assertEquals(Arrays.asList("option1", "option2"), options);
	}

	@Test
	public void getTabCompleteOptionsWithoutPermission() {
		String[] args = { "arg1" };
		commandHandler.withArgs("arg1");
		commandHandler.withPerm("some.permission"); // Ensure perm is initialized
		ConcurrentHashMap<String, ArrayList<String>> tabCompleteOptions = new ConcurrentHashMap<>();
		tabCompleteOptions.put("arg1", new ArrayList<>(Arrays.asList("option1", "option2")));

		CommandHandler spyCommandHandler = spy(commandHandler);
		doReturn(false).when(spyCommandHandler).hasPerm(mockSender);

		ArrayList<String> options = spyCommandHandler.getTabCompleteOptions(mockSender, args, 0, tabCompleteOptions);
		assertTrue(options.isEmpty());
	}
}
