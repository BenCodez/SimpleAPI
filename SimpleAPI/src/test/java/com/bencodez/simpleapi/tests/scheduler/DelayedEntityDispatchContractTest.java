package com.bencodez.simpleapi.tests.scheduler;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.function.Consumer;

import org.bukkit.Server;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;

import com.bencodez.simpleapi.scheduler.BukkitScheduler;
import com.tcoded.folialib.FoliaLib;
import com.tcoded.folialib.impl.SpigotImplementation;
import com.tcoded.folialib.util.FoliaLibOptions;

class DelayedEntityDispatchContractTest {
    @Test
    @SuppressWarnings("unchecked")
    void validEntityQueuesOnceAndRunsAfterDelay() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        org.bukkit.scheduler.BukkitScheduler nativeScheduler = mock(org.bukkit.scheduler.BukkitScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(nativeScheduler);

        FoliaLib library = mock(FoliaLib.class);
        FoliaLibOptions options = mock(FoliaLibOptions.class);
        when(options.useIsValidOnNonFolia()).thenReturn(true);
        when(library.getOptions()).thenReturn(options);
        when(library.getPlugin()).thenReturn(plugin);
        // Exercise the actual current backend, including its overloads and seconds conversion.
        SpigotImplementation backend = new SpigotImplementation(library);
        when(library.getScheduler()).thenReturn(backend);
        BukkitScheduler facade = mock(BukkitScheduler.class, CALLS_REAL_METHODS);
        when(facade.getFoliaLib()).thenReturn(library);
        Entity entity = mock(Entity.class);
        when(entity.isValid()).thenReturn(true);
        Runnable action = mock(Runnable.class);

        Queue<Consumer<BukkitTask>> callbacks = new ArrayDeque<>();
        doAnswer(invocation -> {
            callbacks.add((Consumer<BukkitTask>) invocation.getArgument(1));
            return null;
        }).when(nativeScheduler).runTaskLater(eq(plugin), any(Consumer.class), anyLong());

        facade.runTaskLater(plugin, action, 2, entity);

        verify(nativeScheduler, times(1)).runTaskLater(eq(plugin), any(Consumer.class), eq(40L));
        verifyNoInteractions(action);
        assertEquals(1, callbacks.size());
        callbacks.remove().accept(mock(BukkitTask.class));
        verify(action, times(1)).run();
        assertTrue(callbacks.isEmpty());
        verifyNoMoreInteractions(nativeScheduler, action);
    }
}
