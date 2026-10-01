package com.bencodez.simpleapi.servercomm.mqtt;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.eclipse.paho.client.mqttv3.IMqttAsyncClient;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;

import com.bencodez.simpleapi.servercomm.codec.JsonEnvelope;
import com.bencodez.simpleapi.servercomm.codec.JsonEnvelopeCodec;

/** MQTT communication with owned, bounded reconnect and subscription recovery. */
public class MqttServerComm implements AutoCloseable {
    private static final long OPERATION_TIMEOUT = 30000;
    private static final int MAX_SUBSCRIPTIONS = 1024;
    private final String serverId;
    private final IMqttAsyncClient client;
    private final MqttConnectOptions connectOptions;
    private final ScheduledThreadPoolExecutor recoveryExecutor;
    private final Object lifecycle = new Object();
    private final LinkedHashMap<String, Subscription> subscriptions = new LinkedHashMap<>();
    private ScheduledFuture<?> recovery;
    private boolean recovering;
    private boolean recoveryRequested;
    private boolean wanted = true;
    private boolean closed;
    private long generation;
    private long retrySeconds = 1;

    public MqttServerComm(String serverId, String brokerUrl, MqttConnectOptions opts) throws MqttException {
        this(serverId, new MqttAsyncClient(brokerUrl, serverId), opts, worker(serverId));
    }

    public MqttServerComm(String serverId, String brokerUrl, String username, String password) throws MqttException {
        this(serverId, brokerUrl, options(username, password));
    }

    private static MqttConnectOptions options(String username, String password) {
        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(true);
        if (username != null && !username.isEmpty()) options.setUserName(username);
        if (password != null && !password.isEmpty()) options.setPassword(password.toCharArray());
        return options;
    }

    private static ScheduledThreadPoolExecutor worker(String serverId) {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "simpleapi-mqtt-recovery");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setKeepAliveTime(1, TimeUnit.SECONDS);
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    // Package-private injection for deterministic lifecycle tests; production uses Paho.
    MqttServerComm(String serverId, IMqttAsyncClient client, MqttConnectOptions options,
            ScheduledThreadPoolExecutor executor) throws MqttException {
        this.serverId = serverId;
        this.client = Objects.requireNonNull(client);
        this.connectOptions = Objects.requireNonNull(options);
        this.recoveryExecutor = Objects.requireNonNull(executor);
        // Own reconnect scheduling: Paho's automatic timer has no public cancellation
        // contract for disconnect() invoked while its client is already disconnected.
        options.setAutomaticReconnect(false);
        client.setCallback(new MqttCallbackExtended() {
            @Override public void connectComplete(boolean reconnect, String serverURI) { requestRecovery(0); }
            @Override public void connectionLost(Throwable cause) {
                synchronized (lifecycle) { recoveryRequested = true; }
                requestRecovery(1);
            }
            @Override public void messageArrived(String topic, MqttMessage message) { }
            @Override public void deliveryComplete(IMqttDeliveryToken token) { }
        });
        try { connect(); }
        catch (MqttException | RuntimeException failure) {
            try { close(); } catch (MqttException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    public String getServerId() { return serverId; }
    public boolean isConnected() { return client.isConnected(); }

    public void connect() throws MqttException {
        IMqttToken token;
        synchronized (lifecycle) {
            requireOpen();
            wanted = true;
            token = client.isConnected() ? null : client.connect(connectOptions);
        }
        if (token != null) token.waitForCompletion(OPERATION_TIMEOUT);
        synchronized (lifecycle) { stopUnwantedConnection(); }
        requestRecovery(0);
    }

    public void disconnect() throws MqttException {
        synchronized (lifecycle) {
            wanted = false;
            generation++;
            if (recovery != null) recovery.cancel(false);
            recovery = null;
        }
        if (client.isConnected()) client.disconnect().waitForCompletion(5000);
        else client.disconnectForcibly(0, 1000);
    }

    @Override public void close() throws MqttException {
        synchronized (lifecycle) { if (closed) return; closed = true; }
        try { disconnect(); }
        finally {
            recoveryExecutor.shutdownNow();
            if (client instanceof MqttAsyncClient paho) paho.close(true);
            else client.close();
        }
    }

    public void publish(String topic, String payload, int qos, boolean retained) throws MqttException {
        MqttMessage message = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
        message.setQos(qos);
        message.setRetained(retained);
        client.publish(topic, message);
    }

    public void publishEnvelope(String topic, JsonEnvelope envelope, int qos, boolean retained) throws MqttException {
        publish(topic, JsonEnvelopeCodec.encode(envelope), qos, retained);
    }

    public void subscribe(String topicFilter, int qos, MessageListener listener) throws MqttException {
        Objects.requireNonNull(topicFilter); Objects.requireNonNull(listener);
        if (qos < 0 || qos > 2) throw new IllegalArgumentException("QoS must be 0, 1, or 2");
        synchronized (lifecycle) {
            requireOpen();
            if (!subscriptions.containsKey(topicFilter) && subscriptions.size() >= MAX_SUBSCRIPTIONS)
                throw new IllegalStateException("MQTT subscription capacity reached");
            Subscription subscription = new Subscription(topicFilter, qos, listener);
            subscriptions.put(topicFilter, subscription);
            try {
                client.subscribe(topicFilter, qos, null, new org.eclipse.paho.client.mqttv3.IMqttActionListener() {
                    @Override public void onSuccess(IMqttToken token) { }
                    @Override public void onFailure(IMqttToken token, Throwable failure) {
                        synchronized (lifecycle) { recoveryRequested = true; }
                        requestRecovery(1);
                    }
                }, (topic, message) -> listener.messageArrived(topic, message));
            }
            catch (MqttException failure) { requestRecovery(1); throw failure; }
        }
    }

    public void subscribeEnvelopes(String topicFilter, int qos, EnvelopeListener listener) throws MqttException {
        subscribe(topicFilter, qos, (topic, message) -> listener.envelopeArrived(topic,
                JsonEnvelopeCodec.decode(new String(message.getPayload(), StandardCharsets.UTF_8))));
    }

    public void unsubscribe(String topicFilter) throws MqttException {
        synchronized (lifecycle) {
            requireOpen();
            subscriptions.remove(topicFilter);
            client.unsubscribe(topicFilter);
        }
    }

    private IMqttToken subscribeNow(Subscription subscription) throws MqttException {
        return client.subscribe(subscription.topic(), subscription.qos(), (topic, message) ->
                subscription.listener().messageArrived(topic, message));
    }

    private void requireOpen() throws MqttException {
        if (closed) throw new MqttException(MqttException.REASON_CODE_CLIENT_CLOSED);
    }

    private void requestRecovery(long delay) {
        synchronized (lifecycle) {
            if (closed || !wanted || recovering || recovery != null) return;
            try { recovery = recoveryExecutor.schedule(this::recover, delay, TimeUnit.SECONDS); }
            catch (java.util.concurrent.RejectedExecutionException rejected) {
                // Only close shuts down this owned executor. Desired subscriptions remain owned.
                if (!closed) throw rejected;
            }
        }
    }

    private void recover() {
        long admitted;
        synchronized (lifecycle) {
            recovery = null;
            if (closed || !wanted) return;
            recovering = true;
            recoveryRequested = false;
            admitted = generation;
        }
        boolean success = false;
        try {
            IMqttToken connection;
            Subscription[] desired;
            synchronized (lifecycle) {
                if (!current(admitted)) return;
                connection = client.isConnected() ? null : client.connect(connectOptions);
            }
            if (connection != null) connection.waitForCompletion(OPERATION_TIMEOUT);
            synchronized (lifecycle) {
                if (!current(admitted)) return;
                desired = subscriptions.values().toArray(Subscription[]::new);
            }
            for (Subscription subscription : desired) {
                IMqttToken token;
                synchronized (lifecycle) {
                    if (!current(admitted)) return;
                    if (subscriptions.get(subscription.topic()) != subscription) continue;
                    // Admission is serialized with unsubscribe; only token waiting is outside the lock.
                    token = subscribeNow(subscription);
                }
                token.waitForCompletion(OPERATION_TIMEOUT);
            }
            success = client.isConnected();
        } catch (MqttException failure) {
            // Retry both connection and SUBACK failures with bounded backoff.
        } finally {
            synchronized (lifecycle) {
                recovering = false;
                stopUnwantedConnection();
                if (success && current(admitted) && !recoveryRequested) retrySeconds = 1;
                else {
                    long delay = retrySeconds;
                    retrySeconds = Math.min(60, retrySeconds * 2);
                    requestRecovery(delay);
                }
            }
        }
    }

    // A CONNECT admitted before disconnect may finish after cancellation. Its
    // completion must not silently resurrect a deliberately stopped transport.
    private void stopUnwantedConnection() {
        if (!wanted && !closed && client.isConnected()) {
            try { client.disconnect(); }
            catch (MqttException failure) {
                // Force the local connection closed if orderly admission is unavailable.
                try { client.disconnectForcibly(0, 1000); }
                catch (MqttException cleanup) { failure.addSuppressed(cleanup); }
            }
        }
    }

    private boolean current(long admitted) { return !closed && wanted && generation == admitted; }
    private record Subscription(String topic, int qos, MessageListener listener) { }
    public interface MessageListener { void messageArrived(String topic, MqttMessage message) throws Exception; }
    public interface EnvelopeListener { void envelopeArrived(String topic, JsonEnvelope envelope) throws Exception; }
}
