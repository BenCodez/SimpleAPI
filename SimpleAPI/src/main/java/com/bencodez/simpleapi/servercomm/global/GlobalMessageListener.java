package com.bencodez.simpleapi.servercomm.global;

import com.bencodez.simpleapi.servercomm.codec.JsonEnvelope;

import lombok.Getter;

public abstract class GlobalMessageListener {
	@Getter
	private String subChannel;

	public GlobalMessageListener(String subChannel) {
		this.subChannel = subChannel;
	}

	public abstract void onReceive(JsonEnvelope messageData);

	public void sendMessage(GlobalMessageHandler globalMessageHandler, JsonEnvelope messageData) {
		globalMessageHandler.sendMessage(messageData);
	}
}
