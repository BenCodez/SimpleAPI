package com.bencodez.simpleapi.servercomm.sockets;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import com.bencodez.simpleapi.encryption.EncryptionHandler;
import com.bencodez.simpleapi.servercomm.codec.JsonEnvelope;
import com.bencodez.simpleapi.servercomm.codec.JsonEnvelopeCodec;

import lombok.Getter;

public abstract class SocketServer extends Thread {

	@Getter
	private final boolean debug;

	private final EncryptionHandler encryptionHandler;

	@Getter
	private final String host;

	@Getter
	private final int port;

	private volatile boolean running = true;

	private ServerSocket server;

	public SocketServer(String threadName, String host, int port, EncryptionHandler handle, boolean debug) {
		super(threadName);
		this.host = host;
		this.port = port;
		this.encryptionHandler = handle;
		this.debug = debug;
		try {
			server = new ServerSocket();
			server.bind(new InetSocketAddress(host, port));
			start();
		} catch (IOException e) {
			System.out.println("Failed to bind to " + host + ":" + port);
			e.printStackTrace();
			close();
		}
	}

	private void restartServer() {
		if (restartCount > 5) {
			logger("Failed to restart server socket on " + host + ":" + port + " after 5 attempts, closing server");
			close();
			return;
		}
		try {
			server.close();
			server = new ServerSocket();
			server.bind(new InetSocketAddress(host, port));
		} catch (Exception e) {
			logger("Failed to restart server socket on " + host + ":" + port);
			e.printStackTrace();
		}

		restartCount++;
	}

	public void close() {
		try {
			running = false;
			if (server != null) {
				server.close();
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public abstract void logger(String str);

	public abstract void onReceive(JsonEnvelope envelope);

	private int restartCount = 0;

	@Override
	public void run() {
		while (running) {
			try {
				Socket socket = server.accept();
				socket.setSoTimeout(5000);
				DataInputStream dis = new DataInputStream(socket.getInputStream());

				String raw = dis.readUTF();
				String decrypted = encryptionHandler != null ? encryptionHandler.decrypt(raw) : raw;

				if (debug) {
					logger("Debug: Socket Receiving Raw Bytes: " + decrypted.getBytes(StandardCharsets.UTF_8).length);
					logger("Debug: Socket Receiving: " + decrypted);
				}

				JsonEnvelope env = JsonEnvelopeCodec.decode(decrypted);
				onReceive(env);

				dis.close();
				socket.close();
			} catch (EOFException e) {
				logger("Error occurred while receiving socket message, enable debug to see more: " + e.getMessage());
				if (debug) {
					e.printStackTrace();
				}
			} catch (Exception ex) {
				logger("Error occurred while receiving socket message");
				ex.printStackTrace();
				restartServer();
			}
		}
	}
}
