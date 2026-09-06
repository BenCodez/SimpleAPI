package com.bencodez.simpleapi.servercomm.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bencodez.simpleapi.servercomm.codec.JsonEnvelope;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HttpOutgoingQueueCapacityTest {
	@TempDir Path directory;

	@Test
	void quarantineDirectoriesCountTowardCapacityWithoutBlockingSameServerRetry() throws Exception {
		Path queueRoot = directory.resolve("outgoing");
		Files.createDirectory(queueRoot);
		HttpTransportProtocol.Delivery existingDelivery = null;
		for (int index = 0; index < 128; index++) {
			String serverId = "server-" + index;
			String deliveryId = UUID.nameUUIDFromBytes(serverId.getBytes(StandardCharsets.UTF_8)).toString();
			HttpTransportProtocol.Delivery delivery = new HttpTransportProtocol.Delivery(deliveryId,
					JsonEnvelope.builder("quarantined-" + index).build());
			Path serverDirectory = Files.createDirectory(queueRoot.resolve(serverId));
			Files.write(serverDirectory.resolve(".pending-" + deliveryId + ".json"),
					HttpTransportProtocol.storedDelivery(delivery));
			if (index == 0) existingDelivery = delivery;
		}

		HttpProxyTransportServer.DurableOutgoingQueue queue = new HttpProxyTransportServer.DurableOutgoingQueue(
				queueRoot, com.bencodez.simpleapi.file.DurableFiles::forceDirectory);
		queue.load();

		String newServerId = "server-128";
		HttpTransportProtocol.Delivery newDelivery = new HttpTransportProtocol.Delivery(UUID.randomUUID().toString(),
				JsonEnvelope.builder("new-server").build());
		HttpProxyTransportServer.BackendState newServer = new HttpProxyTransportServer.BackendState(
				newServerId, queue, (server, id) -> { });
		assertFalse(newServer.enqueue(newDelivery), "a new backend must be rejected at the durable directory cap");
		assertFalse(Files.exists(queueRoot.resolve(newServerId)), "rejected enqueue must not publish a backend directory");

		HttpProxyTransportServer.BackendState existingServer = new HttpProxyTransportServer.BackendState(
				"server-0", queue, (server, id) -> { });
		assertTrue(existingServer.enqueue(existingDelivery), "same-server retry must recover its quarantine at capacity");
		queue.close();

		HttpProxyTransportServer.DurableOutgoingQueue restarted = new HttpProxyTransportServer.DurableOutgoingQueue(
				queueRoot, com.bencodez.simpleapi.file.DurableFiles::forceDirectory);
		restarted.load();

		try (var servers = Files.list(queueRoot)) {
			assertEquals(128L, servers.count(), "restart must not create a 129th backend directory");
		}
		Set<String> deliveryIds = new HashSet<>();
		long durableFiles = 0;
		try (var servers = Files.list(queueRoot)) {
			for (Path serverDirectory : servers.toList()) {
				try (var messages = Files.list(serverDirectory)) {
					for (Path message : messages.toList()) {
						if (!message.getFileName().toString().endsWith(".json")) continue;
						durableFiles++;
						assertTrue(deliveryIds.add(HttpTransportProtocol.parseStoredDelivery(Files.readAllBytes(message)).id()),
								"restart must not duplicate a durable delivery");
					}
				}
			}
		}
		assertEquals(128L, durableFiles, "each seeded delivery must remain represented exactly once");
		assertEquals(128, deliveryIds.size());
		restarted.close();
	}
}
