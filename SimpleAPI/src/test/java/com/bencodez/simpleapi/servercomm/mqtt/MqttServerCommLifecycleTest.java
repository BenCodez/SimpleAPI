package com.bencodez.simpleapi.servercomm.mqtt;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayDeque;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.paho.client.mqttv3.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MqttServerCommLifecycleTest {
    private static final class Timer extends ScheduledThreadPoolExecutor {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        final ArrayDeque<Long> delays = new ArrayDeque<>();
        Timer() { super(1); }
        @Override public ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) {
            tasks.add(task); delays.add(unit.toSeconds(delay));
            return mock(ScheduledFuture.class);
        }
        void runNext() { delays.remove(); tasks.remove().run(); }
    }
    private static final class Fixture implements AutoCloseable {
        final IMqttAsyncClient client = mock(IMqttAsyncClient.class);
        final IMqttToken token = mock(IMqttToken.class);
        final AtomicBoolean connected = new AtomicBoolean();
        final Timer timer = new Timer();
        final MqttServerComm comm;
        final MqttCallbackExtended callback;
        Fixture() throws Exception {
            when(client.isConnected()).thenAnswer(call -> connected.get());
            when(client.connect(any())).thenAnswer(call -> { connected.set(true); return token; });
            when(client.disconnect()).thenAnswer(call -> { connected.set(false); return token; });
            when(client.subscribe(anyString(), anyInt(), any(IMqttMessageListener.class))).thenReturn(token);
            when(client.subscribe(anyString(), anyInt(), isNull(), any(IMqttActionListener.class), any(IMqttMessageListener.class))).thenReturn(token);
            comm = new MqttServerComm("node", client, new MqttConnectOptions(), timer);
            ArgumentCaptor<MqttCallback> captured = ArgumentCaptor.forClass(MqttCallback.class);
            verify(client).setCallback(captured.capture());
            callback = (MqttCallbackExtended) captured.getValue();
            timer.runNext();
        }
        @Override public void close() throws Exception { comm.close(); timer.shutdownNow(); }
    }
    @Test void connectionLossRestoresOriginalListenersWithoutManualReconnect() throws Exception {
        try (Fixture f = new Fixture()) {
            MqttServerComm.MessageListener listener = mock(MqttServerComm.MessageListener.class);
            f.comm.subscribe("votes", 1, listener);
            f.connected.set(false);
            f.callback.connectionLost(new Exception());
            f.callback.connectionLost(new Exception());
            assertEquals(1, f.timer.tasks.size());
            f.timer.runNext();
            verify(f.client, times(2)).connect(any());
            ArgumentCaptor<IMqttMessageListener> restored = ArgumentCaptor.forClass(IMqttMessageListener.class);
            verify(f.client).subscribe(eq("votes"), eq(1), restored.capture());
            MqttMessage vote = new MqttMessage();
            restored.getValue().messageArrived("votes", vote);
            verify(listener, times(1)).messageArrived("votes", vote);
            assertTrue(f.timer.tasks.isEmpty());
        }
    }
    @Test void failedReconnectBackoffIsBoundedAndOnlyOneTaskIsOwned() throws Exception {
        try (Fixture f = new Fixture()) {
            f.connected.set(false);
            doThrow(new MqttException(32103)).when(f.client).connect(any());
            f.callback.connectionLost(new Exception());
            for (int i = 0; i < 12; i++) {
                assertEquals(1, f.timer.tasks.size());
                assertTrue(f.timer.delays.peek() <= 60);
                f.timer.runNext();
            }
        }
    }
    @Test void cancelledRetryCannotReconnectAfterDisconnectOrClose() throws Exception {
        try (Fixture f = new Fixture()) {
            f.connected.set(false);
            f.callback.connectionLost(new Exception());
            f.comm.disconnect();
            f.timer.runNext(); // Model already accepted task racing cancellation.
            verify(f.client, times(1)).connect(any());
            f.callback.connectionLost(new Exception());
            assertTrue(f.timer.tasks.isEmpty());
            f.comm.close();
            f.callback.connectComplete(true, "ignored");
            assertTrue(f.timer.tasks.isEmpty());
            assertTrue(f.timer.isShutdown());
        }
    }
    @Test void disconnectDuringConnectCompletionDoesNotResurrectTransport() throws Exception {
        try (Fixture f = new Fixture()) {
            f.connected.set(false);
            f.callback.connectionLost(new Exception());
            doAnswer(call -> {
                f.connected.set(false);
                f.comm.disconnect();
                f.connected.set(true); // Late CONNECT completion after stop.
                return null;
            }).when(f.token).waitForCompletion(30000);
            f.timer.runNext();
            assertFalse(f.connected.get());
            verify(f.client).disconnect();
            assertTrue(f.timer.tasks.isEmpty());
        }
    }
    @Test void unsubscribeRemovesRecoveryIntent() throws Exception {
        try (Fixture f = new Fixture()) {
            f.comm.subscribe("old", 1, (topic, message) -> {});
            f.comm.unsubscribe("old");
            f.connected.set(false);
            f.callback.connectionLost(new Exception());
            f.timer.runNext();
            verify(f.client, never()).subscribe(eq("old"), anyInt(), any(IMqttMessageListener.class));
        }
    }
    @Test void failedSubackIsRetriedAndCapacityDoesNotGrowWithoutBound() throws Exception {
        try (Fixture f = new Fixture()) {
            f.comm.subscribe("votes", 1, (topic, message) -> {});
            ArgumentCaptor<IMqttActionListener> action = ArgumentCaptor.forClass(IMqttActionListener.class);
            verify(f.client).subscribe(eq("votes"), eq(1), isNull(), action.capture(), any(IMqttMessageListener.class));
            action.getValue().onFailure(f.token, new Exception());
            f.timer.runNext();
            verify(f.client).subscribe(eq("votes"), eq(1), any(IMqttMessageListener.class));
            for (int i = 1; i < 1024; i++) f.comm.subscribe("topic/" + i, 1, (topic, message) -> {});
            assertThrows(IllegalStateException.class, () -> f.comm.subscribe("overflow", 1, (topic, message) -> {}));
            f.comm.subscribe("votes", 2, (topic, message) -> {}); // Replacement remains possible at capacity.
        }
    }
}
