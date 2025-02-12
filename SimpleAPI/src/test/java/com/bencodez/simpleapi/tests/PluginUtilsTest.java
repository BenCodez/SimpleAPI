
package com.bencodez.simpleapi.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.bukkit.Server;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.event.Listener;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.bencodez.simpleapi.utils.PluginUtils;

public class PluginUtilsTest {

    private JavaPlugin plugin;
    private CommandExecutor executor;
    private TabCompleter tabCompleter;
    @SuppressWarnings("unused")
	private Listener listener;
    private PluginManager pluginManager;

    @BeforeEach
    public void setUp() {
        plugin = mock(JavaPlugin.class);
        executor = mock(CommandExecutor.class);
        tabCompleter = mock(TabCompleter.class);
        listener = mock(Listener.class);
        pluginManager = mock(PluginManager.class);
        Server server = mock(Server.class);
        Mockito.when(plugin.getServer()).thenReturn(server);
        Mockito.when(server.getPluginManager()).thenReturn(pluginManager);
        Mockito.when(plugin.getCommand(Mockito.anyString())).thenReturn(mock(PluginCommand.class));
    }

    @Test
    public void getFreeMemory_ReturnsNonNegativeValue() {
        long freeMemory = PluginUtils.getFreeMemory();
        assertEquals(true, freeMemory >= 0);
    }

    @Test
    public void getMemory_ReturnsNonNegativeValue() {
        long totalMemory = PluginUtils.getMemory();
        assertEquals(true, totalMemory >= 0);
    }

    @Test
    public void registerCommands_WithTabCompleter() {
        PluginUtils.registerCommands(plugin, "testCommand", executor, tabCompleter);
        PluginCommand command = (PluginCommand) plugin.getCommand("testCommand");
        assertNotNull(command);
        verify(command).setExecutor(executor);
        verify(command).setTabCompleter(tabCompleter);
    }

    @Test
    public void registerCommands_WithoutTabCompleter() {
        PluginUtils.registerCommands(plugin, "testCommand", executor, null);
        PluginCommand command = (PluginCommand) plugin.getCommand("testCommand");
        assertNotNull(command);
        verify(command).setExecutor(executor);
    }
}
