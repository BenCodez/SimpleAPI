package com.bencodez.simpleapi.servercomm.global;

import java.util.ArrayList;

import com.bencodez.simpleapi.servercomm.codec.JsonEnvelope;

public abstract class GlobalMessageProxyHandler {

	private ArrayList<GlobalMessageListener> globalMessageListeners = new ArrayList<>();

	public GlobalMessageProxyHandler() {
	}

	public void addListener(GlobalMessageListener globalMessageListener) {
		globalMessageListeners.add(globalMessageListener);
	}

	public void onMessage(JsonEnvelope message) {
		for (GlobalMessageListener listener : globalMessageListeners) {
			if (listener.getSubChannel().equalsIgnoreCase(message.getSubChannel())) {
				listener.onReceive(message);
			}
		}
	}

	public abstract void sendMessage(String server, int delay, JsonEnvelope envelope);
}
