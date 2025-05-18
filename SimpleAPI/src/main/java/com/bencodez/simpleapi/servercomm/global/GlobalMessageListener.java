package com.bencodez.simpleapi.servercomm.global;

import java.util.ArrayList;

import lombok.Getter;

public abstract class GlobalMessageListener {
	@Getter
	private String subChannel;

	public GlobalMessageListener(String subChannel) {
		this.subChannel = subChannel;
	}

	public abstract void onReceive(ArrayList<String> message);

	public void sendMessage(GlobalMessageHandler globalMessageHandler, String subChannel, String... messageData) {
		globalMessageHandler.sendMessage(subChannel, messageData);
	}
}
