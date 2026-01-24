package com.bencodez.simpleapi.servercomm.sockets;

import com.bencodez.simpleapi.servercomm.codec.JsonEnvelope;

import lombok.Getter;

public abstract class SocketReceiver {
	private final String ident;

	@Getter
	private int socketDelay = 0;

	public SocketReceiver() {
		this.ident = "";
	}

	public SocketReceiver(String ident) {
		this.ident = ident == null ? "" : ident;
	}

	public final void onReceive(JsonEnvelope envelope) {
		if (envelope == null) {
			return;
		}
		if (ident.isEmpty() || ident.equalsIgnoreCase(envelope.getSubChannel())) {
			onReceiveEnvelope(envelope);
		}
	}

	public abstract void onReceiveEnvelope(JsonEnvelope envelope);

	public SocketReceiver setSocketDelay(int delay) {
		this.socketDelay = delay;
		return this;
	}
}
