package com.bencodez.simpleapi.servercomm.pluginmessage;

import com.bencodez.simpleapi.servercomm.codec.JsonEnvelope;

public abstract class PluginMessageHandler {

	private final String subChannel;

	public PluginMessageHandler(String subChannel) {
		this.subChannel = subChannel;
	}

	public PluginMessageHandler() {
		this.subChannel = null;
	}

	public String getSubChannel() {
		return subChannel;
	}

	public abstract void onReceive(JsonEnvelope envelope);
}
