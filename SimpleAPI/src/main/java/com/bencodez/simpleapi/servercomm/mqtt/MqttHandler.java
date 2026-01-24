package com.bencodez.simpleapi.servercomm.mqtt;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.bencodez.simpleapi.servercomm.codec.JsonEnvelope;
import com.bencodez.simpleapi.servercomm.codec.JsonEnvelopeCodec;

/**
 * General-purpose MQTT communication handler with support for RPC and pub/sub.
 * Envelope-first (no delimiter protocols).
 */
public class MqttHandler {

	public interface EnvelopeHandler {
		void onEnvelope(String topic, JsonEnvelope envelope);
	}

	public interface RpcCallback {
		void onComplete(RpcResponse response, Exception error);
	}

	public static class RpcResponse {
		private final String requestId;
		private final JsonEnvelope envelope;

		public RpcResponse(String requestId, JsonEnvelope envelope) {
			this.requestId = requestId;
			this.envelope = envelope;
		}

		public String getRequestId() {
			return requestId;
		}

		public JsonEnvelope getEnvelope() {
			return envelope;
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

	public void publishEnvelope(String topic, JsonEnvelope envelope) throws Exception {
		mqtt.publishEnvelope(topic, envelope, defaultQos, false);
	}

	public void subscribeEnvelopes(String topicFilter, EnvelopeHandler handler) throws Exception {
		mqtt.subscribeEnvelopes(topicFilter, defaultQos, (topic, env) -> handler.onEnvelope(topic, env));
	}

	public void unsubscribe(String topicFilter) throws Exception {
		mqtt.unsubscribe(topicFilter);
	}

	/**
	 * RPC-style request: publishes an envelope to topic/{requestId}
	 * and expects the response on a separate subscription you wire to handleRpcEnvelopeResponse(...).
	 */
	public void requestEnvelope(String topic, JsonEnvelope envelope, long timeoutMillis, RpcCallback callback)
			throws Exception {
		String requestId = UUID.randomUUID().toString();
		pendingRpcs.put(requestId, callback);

		scheduler.schedule(() -> {
			RpcCallback cb = pendingRpcs.remove(requestId);
			if (cb != null) {
				cb.onComplete(null, new Exception("RPC timeout"));
			}
		}, timeoutMillis, TimeUnit.MILLISECONDS);

		publishEnvelope(topic + "/" + requestId, envelope);
	}

	/**
	 * Call this from your subscription handler when you receive an envelope on an RPC response topic.
	 */
	public void handleRpcEnvelopeResponse(String topic, JsonEnvelope envelope) {
		String[] parts = topic.split("/");
		if (parts.length == 0) {
			return;
		}
		String requestId = parts[parts.length - 1];
		RpcCallback cb = pendingRpcs.remove(requestId);
		if (cb != null) {
			cb.onComplete(new RpcResponse(requestId, envelope), null);
		}
	}

	/**
	 * Convenience if you still have raw payload string at the edge.
	 */
	public void handleRpcEnvelopeResponseRaw(String topic, String payload) {
		handleRpcEnvelopeResponse(topic, JsonEnvelopeCodec.decode(payload));
	}
}
