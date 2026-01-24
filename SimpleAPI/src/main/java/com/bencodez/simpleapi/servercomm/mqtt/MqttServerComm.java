package com.bencodez.simpleapi.servercomm.mqtt;

import java.nio.charset.StandardCharsets;

import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttAsyncClient;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;

import com.bencodez.simpleapi.servercomm.codec.JsonEnvelope;
import com.bencodez.simpleapi.servercomm.codec.JsonEnvelopeCodec;

/**
 * Very basic MQTT client wrapper using Eclipse Paho.
 * Provides connect/disconnect, publish, subscribe, and serverId access.
 * Supports username/password authentication.
 * Envelope helpers included.
 */
public class MqttServerComm {

	private final String serverId;
	private final IMqttAsyncClient client;
	private final MqttConnectOptions connectOptions;

	public MqttServerComm(String serverId, String brokerUrl, MqttConnectOptions opts) throws MqttException {
		this.serverId = serverId;
		this.client = new MqttAsyncClient(brokerUrl, serverId);
		this.connectOptions = opts;
		this.client.connect(connectOptions).waitForCompletion();
	}

	public MqttServerComm(String serverId, String brokerUrl, String username, String password) throws MqttException {
		this.serverId = serverId;
		this.client = new MqttAsyncClient(brokerUrl, serverId);
		this.connectOptions = new MqttConnectOptions();
		this.connectOptions.setCleanSession(true);

		if (username != null && !username.isEmpty()) {
			this.connectOptions.setUserName(username);
		}
		if (password != null && !password.isEmpty()) {
			this.connectOptions.setPassword(password.toCharArray());
		}

		this.client.connect(connectOptions).waitForCompletion();
	}

	public String getServerId() {
		return serverId;
	}

	public void connect() throws MqttException {
		if (!client.isConnected()) {
			client.connect(connectOptions).waitForCompletion();
		}
	}

	public void disconnect() throws MqttException {
		if (client.isConnected()) {
			client.disconnect().waitForCompletion();
		}
	}

	public boolean isConnected() {
		return client.isConnected();
	}

	public void publish(String topic, String payload, int qos, boolean retained) throws MqttException {
		MqttMessage msg = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
		msg.setQos(qos);
		msg.setRetained(retained);
		client.publish(topic, msg);
	}

	public void publishEnvelope(String topic, JsonEnvelope envelope, int qos, boolean retained) throws MqttException {
		publish(topic, JsonEnvelopeCodec.encode(envelope), qos, retained);
	}

	public void subscribe(String topicFilter, int qos, MessageListener listener) throws MqttException {
		client.subscribe(topicFilter, qos, null, new IMqttActionListener() {
			@Override
			public void onSuccess(IMqttToken asyncActionToken) {
			}

			@Override
			public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
				exception.printStackTrace();
			}
		}, (topic, msg) -> listener.messageArrived(topic, msg));
	}

	public void subscribeEnvelopes(String topicFilter, int qos, EnvelopeListener listener) throws MqttException {
		subscribe(topicFilter, qos, (topic, msg) -> {
			String payload = new String(msg.getPayload(), StandardCharsets.UTF_8);
			JsonEnvelope env = JsonEnvelopeCodec.decode(payload);
			listener.envelopeArrived(topic, env);
		});
	}

	public void unsubscribe(String topicFilter) throws MqttException {
		client.unsubscribe(topicFilter);
	}

	public interface MessageListener {
		void messageArrived(String topic, MqttMessage message) throws Exception;
	}

	public interface EnvelopeListener {
		void envelopeArrived(String topic, JsonEnvelope envelope) throws Exception;
	}
}
