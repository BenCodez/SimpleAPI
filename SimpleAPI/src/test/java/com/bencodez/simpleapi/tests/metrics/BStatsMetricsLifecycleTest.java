package com.bencodez.simpleapi.tests.metrics;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.InvocationTargetException;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.RunnableScheduledFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import com.bencodez.simpleapi.metrics.BStatsMetrics;

class BStatsMetricsLifecycleTest {
    @Test void disablingOneOwnerDoesNotRetireEnabledSiblingOrPreventLaterStartup() throws Exception {
        try (var fixture = new Fixture()) {
            var firstEnabled = new AtomicBoolean(true);
            var first = fixture.owner(true, firstEnabled::get);
            var sibling = fixture.owner(true, () -> true);
            first.tick();
            sibling.tick();
            assertEquals(1, first.handoffs.size());
            assertEquals(1, sibling.handoffs.size());
            firstEnabled.set(false);
            first.periodicTick();
            assertTrue(first.scheduler.isShutdown());
            assertFalse(sibling.scheduler.isShutdown(), "Disabled owner retired its enabled sibling");
            assertEquals("accepted", sibling.scheduler.submit(() -> "accepted").get(5, TimeUnit.SECONDS));
            sibling.periodicTick();
            assertEquals(2, sibling.handoffs.size());
            var replacement = fixture.owner(true, () -> true);
            replacement.tick();
            assertEquals(1, replacement.handoffs.size());
            assertNotSame(first.scheduler, replacement.scheduler);
        }
    }

    @Test void disabledAtStartupSchedulesNoWorkAndDoesNotDisableAnotherOwner() throws Exception {
        try (var fixture = new Fixture()) {
            var disabled = fixture.owner(false, () -> true);
            assertTrue(disabled.commands.isEmpty());
            assertEquals(0, disabled.scheduler.getPoolSize());
            var enabled = fixture.owner(true, () -> true);
            enabled.tick();
            assertEquals(1, enabled.handoffs.size());
        }
    }

    @Test void retirementCancelsRemainingDelaysAndTerminatesWithoutWaitingForReportingInterval() throws Exception {
        try (var fixture = new Fixture()) {
            var enabled = new AtomicBoolean(true);
            var owner = fixture.owner(true, enabled::get);
            enabled.set(false);
            owner.periodicTick();
            assertTrue(owner.scheduler.isShutdown());
            assertTrue(owner.scheduler.awaitTermination(5, TimeUnit.SECONDS), "Retired metrics thread stayed alive");
            assertTrue(owner.scheduler.getQueue().isEmpty());
            assertTrue(owner.handoffs.isEmpty());
            var next = fixture.owner(true, () -> true);
            next.tick();
            assertEquals(1, next.handoffs.size());
        }
    }

    @Test void handoffAcceptedBeforeDisableCannotCollectOrRejectAfterRetirement() throws Exception {
        try (var fixture = new Fixture()) {
            var enabled = new AtomicBoolean(true);
            var owner = fixture.owner(true, enabled::get);
            owner.tick();
            Runnable accepted = owner.handoffs.getFirst();
            enabled.set(false);
            owner.periodicTick();
            assertDoesNotThrow(accepted::run);
            assertEquals(0, owner.collections.get());
        }
    }

    @Test void retirementRacingCollectionDoesNotLeakExecutorRejection() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var firstCollection = new AtomicBoolean(true);
        var worker = Executors.newSingleThreadExecutor();
        try (var fixture = new Fixture()) {
            var enabled = new AtomicBoolean(true);
            var owner = fixture.owner(true, enabled::get, () -> {
                if (firstCollection.compareAndSet(true, false)) {
                    entered.countDown();
                    try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                }
            });
            owner.tick();
            var collection = worker.submit(owner.handoffs.getFirst());
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                enabled.set(false);
                owner.periodicTick();
            } finally { release.countDown(); }
            assertDoesNotThrow(() -> collection.get(5, TimeUnit.SECONDS));
            assertTrue(owner.scheduler.isShutdown());
        } finally {
            release.countDown();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test void partialStartupReleasesItsExecutorAndPreservesFailure() throws Exception {
        try (var fixture = new Fixture(); var construction = mockConstruction(ScheduledThreadPoolExecutor.class,
                (scheduler, context) -> when(scheduler.scheduleAtFixedRate(any(Runnable.class), anyLong(),
                        anyLong(), any(TimeUnit.class))).thenThrow(new RejectedExecutionException("synthetic rejection")))) {
            var failure = assertThrows(AssertionError.class, () -> fixture.owner(true, () -> true));
            assertInstanceOf(RejectedExecutionException.class, failure.getCause());
            assertEquals(1, construction.constructed().size());
            verify(construction.constructed().getFirst()).shutdownNow();
        }
    }

    @Test void rejectedHandoffRetiresDisabledOwnerButAllowsEnabledOwnerToRetry() throws Exception {
        try (var fixture = new Fixture()) {
            var enabled = new AtomicBoolean(true);
            var reject = new AtomicBoolean(true);
            var owner = fixture.owner(true, enabled::get, () -> { }, task -> {
                if (reject.get()) throw new RejectedExecutionException("synthetic handoff rejection");
            });
            owner.periodicTick();
            assertFalse(owner.scheduler.isShutdown());
            reject.set(false);
            owner.periodicTick();
            var duringHandoff = new AtomicBoolean(true);
            var retiring = fixture.owner(true, duringHandoff::get, () -> { }, task -> {
                duringHandoff.set(false);
                throw new RejectedExecutionException("synthetic disable rejection");
            });
            retiring.periodicTick();
            assertTrue(retiring.scheduler.awaitTermination(5, TimeUnit.SECONDS));
            assertFalse(owner.scheduler.isShutdown());
        }
    }

    @Test void explicitRetirementSurvivesSameOwnerReenable() throws Exception {
        try (var fixture = new Fixture()) {
            var enabled = new AtomicBoolean(true);
            var old = fixture.owner(true, enabled::get);
            old.tick();
            enabled.set(false);
            fixture.type.getMethod("shutdown").invoke(old.instance);
            enabled.set(true);
            var replacement = fixture.owner(true, enabled::get);
            replacement.tick();
            old.handoffs.getFirst().run();
            assertEquals(0, old.collections.get());
            assertTrue(old.scheduler.awaitTermination(5, TimeUnit.SECONDS));
            assertFalse(replacement.scheduler.isShutdown());
        }
    }

    @Test void bukkitDisableListenerRetiresOnlyItsOwnerAndUnregisters() throws Exception {
        var plugin = mock(org.bukkit.plugin.Plugin.class);
        var sibling = mock(org.bukkit.plugin.Plugin.class);
        var metrics = mock(BStatsMetrics.MetricsBase.class);
        var type = Class.forName(BStatsMetrics.class.getName() + "$MetricsLifecycle");
        var constructor = type.getDeclaredConstructor(org.bukkit.plugin.Plugin.class, BStatsMetrics.MetricsBase.class);
        constructor.setAccessible(true);
        var listener = (org.bukkit.event.Listener) constructor.newInstance(plugin, metrics);
        var callback = type.getDeclaredMethod("onDisable", org.bukkit.event.server.PluginDisableEvent.class);
        callback.setAccessible(true);
        try (var handlers = mockStatic(org.bukkit.event.HandlerList.class)) {
            callback.invoke(listener, new org.bukkit.event.server.PluginDisableEvent(sibling));
            verifyNoInteractions(metrics);
            handlers.verifyNoInteractions();
            callback.invoke(listener, new org.bukkit.event.server.PluginDisableEvent(plugin));
            verify(metrics).shutdown();
            handlers.verify(() -> org.bukkit.event.HandlerList.unregisterAll(listener));
        }
    }

    @Test void constructorPreservesOptOutBeforePluginEnable(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        var plugin = constructorPlugin(root, false);
        try (var events = mockStatic(org.bukkit.event.server.PluginDisableEvent.class)) {
            assertDoesNotThrow(() -> new BStatsMetrics(plugin, 1));
            events.verifyNoInteractions();
        }
    }

    @Test void constructorBeforeEnableRegistersAndDisableRetiresOldOwnerAcrossReenable(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        var plugin = constructorPlugin(root, true);
        var handlers = new org.bukkit.event.HandlerList();
        try (var events = mockStatic(org.bukkit.event.server.PluginDisableEvent.class)) {
            events.when(org.bukkit.event.server.PluginDisableEvent::getHandlerList).thenReturn(handlers);
            var metrics = new BStatsMetrics(plugin, 1);
            var field = BStatsMetrics.class.getDeclaredField("metricsBase");
            field.setAccessible(true);
            var base = (BStatsMetrics.MetricsBase) field.get(metrics);
            var schedulerField = BStatsMetrics.MetricsBase.class.getDeclaredField("scheduler");
            schedulerField.setAccessible(true);
            var scheduler = (ScheduledThreadPoolExecutor) schedulerField.get(base);
            try {
                assertEquals(1, handlers.getRegisteredListeners().length);
                when(plugin.isEnabled()).thenReturn(true);
                handlers.getRegisteredListeners()[0].callEvent(new org.bukkit.event.server.PluginDisableEvent(plugin));
                assertTrue(scheduler.awaitTermination(5, TimeUnit.SECONDS));
                when(plugin.isEnabled()).thenReturn(false);
                when(plugin.isEnabled()).thenReturn(true);
                assertTrue(scheduler.isShutdown());
            } finally { base.shutdown(); org.bukkit.event.HandlerList.unregisterAll(plugin); }
        }
    }

    @Test void constructorRegistrationFailureReleasesExecutor(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        var plugin = constructorPlugin(root, true);
        var handlers = mock(org.bukkit.event.HandlerList.class);
        doThrow(new IllegalStateException("synthetic registration failure")).when(handlers)
                .register(any(org.bukkit.plugin.RegisteredListener.class));
        try (var events = mockStatic(org.bukkit.event.server.PluginDisableEvent.class);
                var construction = mockConstruction(ScheduledThreadPoolExecutor.class)) {
            events.when(org.bukkit.event.server.PluginDisableEvent::getHandlerList).thenReturn(handlers);
            assertThrows(IllegalStateException.class, () -> new BStatsMetrics(plugin, 1));
            assertEquals(1, construction.constructed().size());
            verify(construction.constructed().getFirst()).shutdown();
        }
    }

    private org.bukkit.plugin.java.JavaPlugin constructorPlugin(java.nio.file.Path root, boolean enabled) throws Exception {
        var config = root.resolve("bStats/config.yml");
        java.nio.file.Files.createDirectories(config.getParent());
        java.nio.file.Files.writeString(config, "enabled: " + enabled + "\nserverUuid: synthetic-server\n");
        var plugin = mock(org.bukkit.plugin.java.JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(root.resolve("owner").toFile());
        when(plugin.isEnabled()).thenReturn(false);
        return plugin;
    }

    /** Each test loads the real nested API afresh; no static scheduler state leaks between tests. */
    private static final class Fixture implements AutoCloseable {
        private final URLClassLoader loader = new URLClassLoader(new java.net.URL[] {
                BStatsMetrics.MetricsBase.class.getProtectionDomain().getCodeSource().getLocation()
        }, ClassLoader.getPlatformClassLoader());
        private final Class<?> type = loader.loadClass(BStatsMetrics.MetricsBase.class.getName());
        private final List<Owner> owners = new ArrayList<>();
        private final Set<Runnable> seen = Collections.newSetFromMap(new IdentityHashMap<>());

        Fixture() throws Exception { }

        Owner owner(boolean enabled, Supplier<Boolean> serviceEnabled) throws Exception {
            return owner(enabled, serviceEnabled, () -> { });
        }

        Owner owner(boolean enabled, Supplier<Boolean> serviceEnabled, Runnable beforeCollection) throws Exception {
            return owner(enabled, serviceEnabled, beforeCollection, null);
        }

        Owner owner(boolean enabled, Supplier<Boolean> serviceEnabled, Runnable beforeCollection,
                Consumer<Runnable> handoff) throws Exception {
            var handoffs = new ArrayList<Runnable>();
            var collections = new AtomicInteger();
            Consumer<Object> collect = ignored -> { beforeCollection.run(); collections.incrementAndGet(); };
            Object instance;
            try {
                instance = type.getConstructor(String.class, String.class, int.class, boolean.class,
                        Consumer.class, Consumer.class, Consumer.class, Supplier.class, BiConsumer.class,
                        Consumer.class, boolean.class, boolean.class, boolean.class)
                        .newInstance("bukkit", "synthetic-server", 1, enabled, collect, collect,
                                (Consumer<Runnable>) (task -> { if (handoff != null) handoff.accept(task); handoffs.add(task); }), serviceEnabled,
                                (BiConsumer<String, Throwable>) (message, error) -> { },
                                (Consumer<String>) message -> { }, false, false, false);
            } catch (InvocationTargetException failure) {
                throw new AssertionError("Metrics owner construction failed", failure.getCause());
            }
            var field = type.getDeclaredField("scheduler");
            field.setAccessible(true);
            var scheduler = (ScheduledThreadPoolExecutor) field.get(instance);
            var commands = scheduler.getQueue().stream().filter(command -> !seen.contains(command)).toList();
            seen.addAll(commands);
            var owner = new Owner(instance, scheduler, commands, handoffs, collections);
            owners.add(owner);
            assertEquals(enabled ? 2 : 0, commands.size(), "Unexpected reporting schedule");
            return owner;
        }

        @Override public void close() throws Exception {
            try {
                for (var owner : owners) owner.scheduler.shutdownNow();
                for (var owner : owners) assertTrue(owner.scheduler.awaitTermination(5, TimeUnit.SECONDS));
            } finally { loader.close(); }
        }
    }

    private record Owner(Object instance, ScheduledThreadPoolExecutor scheduler, List<Runnable> commands,
            List<Runnable> handoffs, AtomicInteger collections) {
        void tick() { run(false); }
        void periodicTick() { run(true); }
        private void run(boolean periodic) {
            var task = commands.stream().map(command -> (RunnableScheduledFuture<?>) command)
                    .filter(command -> command.isPeriodic() == periodic).findFirst().orElseThrow();
            assertTrue(scheduler.remove(task), "Expected pending reporting task");
            task.run();
            // Scheduled futures capture callback exceptions; surface them in the test.
            if (!periodic && !task.isCancelled()) assertDoesNotThrow(() -> task.get(5, TimeUnit.SECONDS));
        }
    }
}
