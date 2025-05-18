package com.bencodez.simpleapi.servercomm.mqtt;

import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttAsyncClient;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;

/**
 * Very basic MQTT client wrapper using Eclipse Paho.
 * Provides connect/disconnect, publish, subscribe, and serverId access.
 */
public class MqttServerComm {

    private final String serverId;
    private final IMqttAsyncClient client;

    public MqttServerComm(String serverId, String brokerUrl, MqttConnectOptions opts) throws MqttException {
        this.serverId = serverId;
        this.client = new MqttAsyncClient(brokerUrl, serverId);
        this.client.connect(opts).waitForCompletion();
    }

    public String getServerId() {
        return serverId;
    }

    public void connect() throws MqttException {
        if (!client.isConnected()) {
            client.reconnect();
        }
    }

    public void disconnect() throws MqttException {
        if (client.isConnected()) {
            client.disconnect();
        }
    }

    public boolean isConnected() {
        return client.isConnected();
    }

    public void publish(String topic, String payload, int qos, boolean retained) throws MqttException {
        MqttMessage msg = new MqttMessage(payload.getBytes());
        msg.setQos(qos);
        msg.setRetained(retained);
        client.publish(topic, msg);
    }

    public void subscribe(String topicFilter, int qos, MessageListener listener) throws MqttException {
        client.subscribe(topicFilter, qos, null, new IMqttActionListener() {
            @Override
            public void onSuccess(IMqttToken asyncActionToken) { /* no‑op */ }
            @Override
            public void onFailure(IMqttToken asyncActionToken, Throwable exception) { exception.printStackTrace(); }
        }, (topic, msg) -> listener.messageArrived(topic, msg));
    }

    public void unsubscribe(String topicFilter) throws MqttException {
        client.unsubscribe(topicFilter);
    }

    /** Simple bridge from Paho’s callback to our handler. */
    public interface MessageListener {
        void messageArrived(String topic, MqttMessage message) throws Exception;
    }
}
