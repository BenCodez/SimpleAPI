package com.bencodez.simpleapi.servercomm.global;

import java.util.ArrayList;

public abstract class GlobalMessageProxyHandler {

	private ArrayList<GlobalMessageListener> globalMessageListeners = new ArrayList<>();

	public GlobalMessageProxyHandler() {
	}

	public void addListener(GlobalMessageListener globalMessageListener) {
		globalMessageListeners.add(globalMessageListener);
	}

	public void onMessage(String subChannel, ArrayList<String> message) {
		for (GlobalMessageListener listener : globalMessageListeners) {
			if (listener.getSubChannel().equalsIgnoreCase(subChannel)) {
				listener.onReceive(message);
			}
		}
	}

	public abstract void sendMessage(String server,String subChannel, String... messageData);
}
