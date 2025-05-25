package com.bencodez.simpleapi.servercomm.global;

import java.util.ArrayList;

import lombok.Getter;

public abstract class GlobalMessageProxyListener {
	@Getter
	private String channel;

	public GlobalMessageProxyListener(String channel) {
		this.channel = channel;
	}

	public abstract void onReceive(ArrayList<String> message);

	public void sendMessage(GlobalMessageProxyHandler globalMessageHandler, String server, String channel,
			String... messageData) {
		globalMessageHandler.sendMessage(server, channel, messageData);
	}
}
