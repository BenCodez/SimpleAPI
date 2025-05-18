package com.bencodez.simpleapi.servercomm.mqtt;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * General-purpose MQTT communication handler with support for RPC and pub/sub.
 */
public class MqttHandler {

    public interface MessageHandler {
        void onMessage(String topic, String payload);
    }

    public interface RpcCallback {
        void onComplete(RpcResponse response, Exception error);
    }

    public static class RpcResponse {
        private final String requestId;
        private final String payload;

        public RpcResponse(String requestId, String payload) {
            this.requestId = requestId;
            this.payload = payload;
        }

        public String getRequestId() {
            return requestId;
        }

        public String getPayload() {
            return payload;
        }
    }

    private final MqttServerComm mqtt;
    private final ConcurrentHashMap<String, RpcCallback> pendingRpcs = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private int defaultQos;

    public MqttHandler(MqttServerComm mqtt, int defaultQos) {
        this.mqtt = mqtt;
        setDefaultQos(defaultQos);
    }

    public MqttHandler(MqttServerComm mqtt) {
        this(mqtt, 2);
    }

    public void setDefaultQos(int qos) {
        if (qos < 0 || qos > 2) {
            throw new IllegalArgumentException("QoS must be 0, 1, or 2");
        }
        this.defaultQos = qos;
    }

    public void connect() throws Exception {
        mqtt.connect();
    }

    public void disconnect() throws Exception {
        mqtt.disconnect();
        scheduler.shutdownNow();
    }

    public boolean isConnected() {
        return mqtt.isConnected();
    }

    public void publish(String topic, String message) throws Exception {
        mqtt.publish(topic, message, defaultQos, false);
    }

    public void subscribe(String topicFilter, MessageHandler handler) throws Exception {
        mqtt.subscribe(topicFilter, defaultQos, (topic, msg) -> {
            handler.onMessage(topic, new String(msg.getPayload()));
        });
    }

    public void unsubscribe(String topicFilter) throws Exception {
        mqtt.unsubscribe(topicFilter);
    }

    public void request(String topic, String message, long timeoutMillis, RpcCallback callback) throws Exception {
        String requestId = UUID.randomUUID().toString();
        pendingRpcs.put(requestId, callback);
        scheduler.schedule(() -> {
            RpcCallback cb = pendingRpcs.remove(requestId);
            if (cb != null) {
                cb.onComplete(null, new Exception("RPC timeout"));
            }
        }, timeoutMillis, TimeUnit.MILLISECONDS);

        mqtt.publish(topic + "/" + requestId, message, defaultQos, false);
    }

    public void handleRpcResponse(String topic, String payload) {
        String[] parts = topic.split("/");
        if (parts.length == 0) return;
        String requestId = parts[parts.length - 1];
        RpcCallback cb = pendingRpcs.remove(requestId);
        if (cb != null) {
            cb.onComplete(new RpcResponse(requestId, payload), null);
        }
    }
}
