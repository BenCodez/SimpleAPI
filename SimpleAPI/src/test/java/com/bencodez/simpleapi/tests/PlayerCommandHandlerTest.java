package com.bencodez.simpleapi.tests;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.bencodez.simpleapi.command.PlayerCommandHandler;
import com.bencodez.simpleapi.scheduler.BukkitScheduler;

class PlayerCommandHandlerTest {
	private JavaPlugin plugin;
	private BukkitScheduler scheduler;

	@BeforeEach
	void setUp() {
		plugin = mock(JavaPlugin.class);
		scheduler = mock(BukkitScheduler.class);
	}

	@Test
	void forceConsoleRejectsPlayers() {
		TestPlayerCommandHandler handler = new TestPlayerCommandHandler(true, true);
		Player player = mock(Player.class);

		assertTrue(handler.runCommand(player, new String[] { "command" }));
		assertTrue(handler.isForceConsole());
		verify(scheduler, never()).runTaskAsynchronously(org.mockito.ArgumentMatchers.eq(plugin),
				org.mockito.ArgumentMatchers.any(Runnable.class));
	}

	@Test
	void forceConsoleAllowsConsoleWhenConsoleIsAllowed() {
		TestPlayerCommandHandler handler = new TestPlayerCommandHandler(true, true);
		CommandSender console = mock(CommandSender.class);

		assertTrue(handler.runCommand(console, new String[] { "command" }));
		verify(scheduler).runTaskAsynchronously(org.mockito.ArgumentMatchers.eq(plugin),
				org.mockito.ArgumentMatchers.any(Runnable.class));
	}

	@Test
	void allowConsoleStillControlsConsoleForOrdinaryCommands() {
		TestPlayerCommandHandler consoleDisallowed = new TestPlayerCommandHandler(false, false);
		TestPlayerCommandHandler consoleAllowed = new TestPlayerCommandHandler(true, false);
		CommandSender console = mock(CommandSender.class);

		assertTrue(consoleDisallowed.runCommand(console, new String[] { "command" }));
		verify(scheduler, never()).runTaskAsynchronously(org.mockito.ArgumentMatchers.eq(plugin),
				org.mockito.ArgumentMatchers.any(Runnable.class));

		assertTrue(consoleAllowed.runCommand(console, new String[] { "command" }));
		verify(scheduler).runTaskAsynchronously(org.mockito.ArgumentMatchers.eq(plugin),
				org.mockito.ArgumentMatchers.any(Runnable.class));
	}

	@Test
	void existingConstructorsKeepTheirPreviousDefaults() {
		TestPlayerCommandHandler argsAndPerm = new TestPlayerCommandHandler(new String[] { "command" }, "");
		TestPlayerCommandHandler help = new TestPlayerCommandHandler(new String[] { "command" }, "", "help");
		TestPlayerCommandHandler allowConsole = new TestPlayerCommandHandler(new String[] { "command" }, "", "help",
				false);

		assertTrue(argsAndPerm.isAllowConsole());
		assertFalse(argsAndPerm.isForceConsole());
		assertTrue(help.isAllowConsole());
		assertFalse(help.isForceConsole());
		assertFalse(allowConsole.isAllowConsole());
		assertFalse(allowConsole.isForceConsole());
	}

	private class TestPlayerCommandHandler extends PlayerCommandHandler {
		TestPlayerCommandHandler(String[] args, String perm) {
			super(plugin, args, perm);
		}

		TestPlayerCommandHandler(String[] args, String perm, String helpMessage) {
			super(plugin, args, perm, helpMessage);
		}

		TestPlayerCommandHandler(String[] args, String perm, String helpMessage, boolean allowConsole) {
			super(plugin, args, perm, helpMessage, allowConsole);
		}

		TestPlayerCommandHandler(boolean allowConsole, boolean forceConsole) {
			super(plugin, new String[] { "command" }, "", "help", allowConsole, forceConsole);
		}

		@Override
		public void debug(String debug) {
		}

		@Override
		public void executeAll(CommandSender sender, String[] args) {
		}

		@Override
		public void executeSinglePlayer(CommandSender sender, String[] args) {
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
			return scheduler;
		}

		@Override
		public String getHelpLine() {
			return "Help line";
		}
	}
}
