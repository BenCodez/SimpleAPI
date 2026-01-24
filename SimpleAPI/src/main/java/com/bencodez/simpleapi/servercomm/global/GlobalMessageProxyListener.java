package com.bencodez.simpleapi.servercomm.global;

import com.bencodez.simpleapi.servercomm.codec.JsonEnvelope;

import lombok.Getter;

public abstract class GlobalMessageProxyListener {
	@Getter
	private String channel;

	public GlobalMessageProxyListener(String channel) {
		this.channel = channel;
	}

	public abstract void onReceive(JsonEnvelope message);

}
