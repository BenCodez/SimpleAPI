package com.bencodez.simpleapi.servercomm.sockets;

import java.io.DataOutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import com.bencodez.simpleapi.encryption.EncryptionHandler;
import com.bencodez.simpleapi.servercomm.codec.JsonEnvelope;
import com.bencodez.simpleapi.servercomm.codec.JsonEnvelopeCodec;

public class ClientHandler {
	private Socket clientSocket;
	private boolean debug = false;
	private final EncryptionHandler encryptionHandler;
	private final String host;
	private final int port;

	public ClientHandler(String host, int port, EncryptionHandler handle) {
		this.host = host;
		this.port = port;
		this.encryptionHandler = handle;
	}

	public ClientHandler(String host, int port, EncryptionHandler handle, boolean debug) {
		this.host = host;
		this.port = port;
		this.encryptionHandler = handle;
		this.debug = debug;
	}

	private void connect() {
		try {
			if (clientSocket != null) {
				clientSocket.close();
			}
			clientSocket = new Socket(host, port);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public void sendEnvelope(boolean debug, JsonEnvelope envelope) {
		String payload = JsonEnvelopeCodec.encode(envelope);

		if (debug) {
			System.out.println("Socket Sending Envelope: " + envelope.getSubChannel() + " " + envelope.getFields());
			System.out.println("Socket Sending Payload Bytes: " + payload.getBytes(StandardCharsets.UTF_8).length);
		}

		connect();
		if (clientSocket == null || clientSocket.isClosed()) {
			System.out.println("Failed to connect to " + host + ":" + port + " to send envelope: "
					+ envelope.getSubChannel());
			return;
		}

		String encrypted = encryptionHandler != null ? encryptionHandler.encrypt(payload) : payload;
		try (DataOutputStream ds = new DataOutputStream(clientSocket.getOutputStream())) {
			ds.writeUTF(encrypted);
		} catch (Exception e1) {
			e1.printStackTrace();
		} finally {
			stopConnection();
		}
	}

	public void sendEnvelope(JsonEnvelope envelope) {
		sendEnvelope(debug, envelope);
	}

	public void stopConnection() {
		try {
			if (clientSocket != null) {
				clientSocket.close();
				clientSocket = null;
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}
