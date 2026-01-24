package com.bencodez.simpleapi.servercomm.pluginmessage;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import com.bencodez.simpleapi.encryption.EncryptionHandler;
import com.bencodez.simpleapi.servercomm.codec.JsonEnvelope;
import com.bencodez.simpleapi.servercomm.codec.JsonEnvelopeCodec;
import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteStreams;

import lombok.Getter;
import lombok.Setter;

public class PluginMessage implements PluginMessageListener {

	// Plugin messaging hard limit is Short.MAX_VALUE (~32767)
	private static final int PLUGIN_MESSAGE_HARD_LIMIT = 32767;

	// Safety margin to account for channel + UTF overhead
	private static final int PLUGIN_MESSAGE_SOFT_LIMIT = 30000;

	@Getter
	@Setter
	private boolean debug = false;

	@Getter
	@Setter
	private EncryptionHandler encryptionHandler;

	private final JavaPlugin plugin;
	private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();

	private final ArrayList<PluginMessageHandler> pluginMessages = new ArrayList<>();

	@Getter
	@Setter
	private String bungeeChannel;

	public PluginMessage(JavaPlugin plugin, String bungeeChannel) {
		this.plugin = plugin;
		this.bungeeChannel = bungeeChannel;
	}

	public void add(PluginMessageHandler handle) {
		pluginMessages.add(handle);
	}

	public ArrayList<PluginMessageHandler> getPluginMessages() {
		return pluginMessages;
	}

	public void shutdown() {
		timer.shutdown();
	}

	@Override
	public void onPluginMessageReceived(String channel, Player player, byte[] message) {
		if (!channel.equals(bungeeChannel)) {
			return;
		}

		ByteArrayDataInput in = ByteStreams.newDataInput(message);

		final String subChannel;
		try {
			subChannel = (encryptionHandler != null) ? encryptionHandler.decrypt(in.readUTF()) : in.readUTF();
		} catch (Exception e) {
			if (debug) {
				e.printStackTrace();
			}
			plugin.getLogger().warning("Error reading plugin message subChannel: " + e.getMessage());
			return;
		}

		final int size = in.readInt();
		if (size < 0 || size > message.length) {
			plugin.getLogger().warning("Invalid message size: " + size);
			return;
		}

		final String payload;
		try {
			payload = (encryptionHandler != null) ? encryptionHandler.decrypt(in.readUTF()) : in.readUTF();
		} catch (Exception e) {
			if (debug) {
				e.printStackTrace();
			}
			plugin.getLogger().warning("Error reading plugin message payload: " + e.getMessage());
			return;
		}

		timer.submit(() -> {
			try {
				JsonEnvelope envelope = JsonEnvelopeCodec.decode(payload);

				// Optional safety: ensure outer routing matches inner routing
				if (!subChannel.equalsIgnoreCase(envelope.getSubChannel())) {
					if (debug) {
						plugin.getLogger().warning("PluginMessage subChannel mismatch: header=" + subChannel
								+ " envelope=" + envelope.getSubChannel());
					}
					return;
				}

				onReceive(envelope);
			} catch (Exception e) {
				if (debug) {
					e.printStackTrace();
				}
				plugin.getLogger().warning("Error decoding plugin message payload: " + e.getMessage());
			}
		});
	}

	public void onReceive(JsonEnvelope envelope) {
		if (debug) {
			plugin.getLogger()
					.info("BungeeDebug: Received envelope: " + envelope.getSubChannel() + " " + envelope.getFields());
		}
		for (PluginMessageHandler handle : pluginMessages) {
			if (handle.getSubChannel() == null || handle.getSubChannel().equalsIgnoreCase(envelope.getSubChannel())) {
				handle.onReceive(envelope);
			}
		}
	}

	public void sendEnvelope(JsonEnvelope envelope) {
		final String subChannel = envelope.getSubChannel();
		final String payload = JsonEnvelopeCodec.encode(envelope);

		byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);

		// Estimate total message size on the wire
		// writeUTF adds 2 bytes length prefix per UTF string
		int estimatedSize = 2 + subChannel.getBytes(StandardCharsets.UTF_8).length + // subChannel UTF
				4 + // int payload length
				2 + payloadBytes.length; // payload UTF

		if (estimatedSize > PLUGIN_MESSAGE_SOFT_LIMIT) {
			plugin.getLogger().warning("[PluginMessage] Payload nearing plugin messaging limit (" + estimatedSize
					+ " bytes) subChannel=" + subChannel + " — consider Redis instead");
		}

		if (estimatedSize > PLUGIN_MESSAGE_HARD_LIMIT) {
			plugin.getLogger()
					.severe("[PluginMessage] Payload TOO LARGE for plugin messaging (" + estimatedSize + " bytes, max="
							+ PLUGIN_MESSAGE_HARD_LIMIT + ") subChannel=" + subChannel + " — message NOT sent");
			return; // hard stop
		}

		ByteArrayOutputStream byteOutStream = new ByteArrayOutputStream();
		DataOutputStream out = new DataOutputStream(byteOutStream);

		try {
			if (encryptionHandler != null) {
				out.writeUTF(encryptionHandler.encrypt(subChannel));
			} else {
				out.writeUTF(subChannel);
			}

			// Keep an int for sanity checks (UTF-8 byte length of payload)
			out.writeInt(payloadBytes.length);

			if (encryptionHandler != null) {
				out.writeUTF(encryptionHandler.encrypt(payload));
			} else {
				out.writeUTF(payload);
			}

			if (debug) {
				plugin.getLogger().info("BungeeDebug: Sending envelope (" + estimatedSize + " bytes): " + subChannel
						+ " " + envelope.getFields());
			}

			Bukkit.getServer().sendPluginMessage(plugin, bungeeChannel, byteOutStream.toByteArray());
			out.close();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

}
