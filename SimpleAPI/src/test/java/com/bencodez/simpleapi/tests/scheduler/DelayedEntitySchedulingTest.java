package com.bencodez.simpleapi.tests.scheduler;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.bukkit.Server;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.bencodez.simpleapi.scheduler.BukkitScheduler;
import com.tcoded.folialib.FoliaLib;
import com.tcoded.folialib.impl.ServerImplementation;
import com.tcoded.folialib.impl.SpigotImplementation;

class DelayedEntitySchedulingTest {
    private JavaPlugin plugin;
    private org.bukkit.scheduler.BukkitScheduler nativeScheduler;
    private FoliaLib library;
    private BukkitScheduler facade;
    private Entity entity;

    @BeforeEach void setup() {
        plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        nativeScheduler = mock(org.bukkit.scheduler.BukkitScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(nativeScheduler);
        library = mock(FoliaLib.class);
        when(library.getPlugin()).thenReturn(plugin);
        ServerImplementation implementation = new SpigotImplementation(library);
        when(library.getImpl()).thenReturn(implementation);
        facade = mock(BukkitScheduler.class, CALLS_REAL_METHODS);
        when(facade.getFoliaLib()).thenReturn(library);
        entity = mock(Entity.class);
        when(entity.isValid()).thenReturn(true);
    }

    @Test void validSpigotEntityQueuesExactlyOnceAndPreservesSeconds() {
        Queue<Consumer<BukkitTask>> queued = captureTasks();
        Runnable action = mock(Runnable.class);
        facade.runTaskLater(plugin, action, 2, entity);
        verify(nativeScheduler).runTaskLater(eq(plugin), any(Consumer.class), eq(40L));
        verifyNoInteractions(action);
        assertEquals(1, queued.size());
        queued.remove().accept(mock(BukkitTask.class));
        verify(action, times(1)).run();
        assertTrue(queued.isEmpty());
    }

    @Test void invalidSpigotEntityDoesNotScheduleOrRun() {
        when(entity.isValid()).thenReturn(false);
        Runnable action = mock(Runnable.class);
        facade.runTaskLater(plugin, action, 1, entity);
        verifyNoInteractions(nativeScheduler, action);
    }

    @Test void nullEntityRetainsNormalGlobalDelay() {
        Queue<Consumer<BukkitTask>> queued = captureTasks();
        Runnable action = mock(Runnable.class);
        facade.runTaskLater(plugin, action, 3, (Entity) null);
        verify(nativeScheduler).runTaskLater(eq(plugin), any(Consumer.class), eq(60L));
        verifyNoInteractions(action);
        queued.remove().accept(mock(BukkitTask.class));
        verify(action).run();
    }

    @Test void foliaUsesTheOriginalEntitySchedulerAndTimeUnit() {
        ServerImplementation ownerScheduler = mock(ServerImplementation.class);
        when(library.getImpl()).thenReturn(ownerScheduler);
        when(library.isFolia()).thenReturn(true);
        Runnable action = mock(Runnable.class);
        facade.runTaskLater(plugin, action, 2, entity);
        verify(ownerScheduler).runAtEntityLater(eq(entity), any(Consumer.class), eq(2L), eq(TimeUnit.SECONDS));
        verifyNoInteractions(entity, nativeScheduler, action);
    }

    @Test void rejectedNativeSchedulingPropagatesWithoutRetryOrInlineExecution() {
        RejectedExecutionException rejection = new RejectedExecutionException("synthetic rejection");
        doThrow(rejection).when(nativeScheduler).runTaskLater(eq(plugin), any(Consumer.class), anyLong());
        Runnable action = mock(Runnable.class);
        assertSame(rejection, assertThrows(RejectedExecutionException.class,
                () -> facade.runTaskLater(plugin, action, 1, entity)));
        verify(nativeScheduler, times(1)).runTaskLater(eq(plugin), any(Consumer.class), eq(20L));
        verifyNoInteractions(action);
    }

    @Test void callbackFailureIsNotRetried() {
        Queue<Consumer<BukkitTask>> queued = captureTasks();
        Runnable action = mock(Runnable.class);
        IllegalStateException failure = new IllegalStateException("synthetic task failure");
        doThrow(failure).when(action).run();
        facade.runTaskLater(plugin, action, 1, entity);
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> queued.remove().accept(mock(BukkitTask.class))));
        verify(action, times(1)).run();
        assertTrue(queued.isEmpty());
    }

    @Test void separateEntitiesKeepIndependentDelayedActions() {
        Queue<Consumer<BukkitTask>> queued = captureTasks();
        Entity other = mock(Entity.class);
        when(other.isValid()).thenReturn(true);
        Runnable first = mock(Runnable.class), second = mock(Runnable.class);
        facade.runTaskLater(plugin, first, 1, entity);
        facade.runTaskLater(plugin, second, 1, other);
        assertEquals(2, queued.size());
        queued.remove().accept(mock(BukkitTask.class));
        verify(first).run();
        verifyNoInteractions(second);
        queued.remove().accept(mock(BukkitTask.class));
        verify(second).run();
        assertTrue(queued.isEmpty());
    }

    @SuppressWarnings("unchecked")
    private Queue<Consumer<BukkitTask>> captureTasks() {
        Queue<Consumer<BukkitTask>> tasks = new ArrayDeque<>();
        doAnswer(invocation -> {
            tasks.add((Consumer<BukkitTask>) invocation.getArgument(1));
            return null;
        }).when(nativeScheduler).runTaskLater(eq(plugin), any(Consumer.class), anyLong());
        return tasks;
    }
}
