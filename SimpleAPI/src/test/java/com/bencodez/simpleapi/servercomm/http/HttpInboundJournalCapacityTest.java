package com.bencodez.simpleapi.servercomm.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bencodez.simpleapi.servercomm.codec.JsonEnvelope;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HttpInboundJournalCapacityTest {
	@TempDir Path directory;

	@Test
	void restoresRunningInboundJournalsAndEnforcesCapacityAcrossRestart() throws Exception {
		Path outgoing = directory.resolve("outgoing");
		Files.createDirectory(outgoing);
		HttpTlsIdentity identity = HttpTlsIdentity.loadOrCreate(directory.resolve("identity"), "localhost");
		HttpEnrollmentAuthority authority = new HttpEnrollmentAuthority(identity, directory.resolve("authority"));
		seedRunningJournals(outgoing, 128);

		try (HttpProxyTransportServer proxy = new HttpProxyTransportServer(new InetSocketAddress("localhost", 0),
				identity, authority, outgoing, ignored -> { })) {
			assertEquals(128, proxy.backendCountForTest(), "validated journals must restore backend state");
			assertNotNull(proxy.backendStateForTest("server-0"));
			assertThrows(java.io.IOException.class, () -> proxy.backendStateForTest("server-128"),
					"a 129th backend must be rejected while durable journals occupy the cap");
		}

		try (HttpProxyTransportServer restarted = new HttpProxyTransportServer(new InetSocketAddress("localhost", 0),
				identity, authority, outgoing, ignored -> { })) {
			assertEquals(128, restarted.backendCountForTest(), "restart must retain the restored backend count");
			assertThrows(java.io.IOException.class, () -> restarted.backendStateForTest("server-128"),
					"the cap must remain enforced after restart");
		}
	}

	@Test
	void prunesEmptySafeJournalsAndAllowsAReplacementBackend() throws Exception {
		Path outgoing = directory.resolve("outgoing");
		Files.createDirectory(outgoing);
		HttpTlsIdentity identity = HttpTlsIdentity.loadOrCreate(directory.resolve("identity"), "localhost");
		HttpEnrollmentAuthority authority = new HttpEnrollmentAuthority(identity, directory.resolve("authority"));
		seedEmptyJournals(outgoing, 128);

		try (HttpProxyTransportServer proxy = new HttpProxyTransportServer(new InetSocketAddress("localhost", 0),
				identity, authority, outgoing, ignored -> { })) {
			assertEquals(0, proxy.backendCountForTest(), "empty journals are safe to prune at startup");
			assertNotNull(proxy.backendStateForTest("replacement"));
			assertEquals(1, proxy.backendCountForTest());
			assertFalse(Files.exists(outgoing.getParent().resolve("outgoing-incoming").resolve("server-0")));
		}
	}

	@Test
	void quarantineOnlyBackendIsIncludedInTheCombinedStartupCapacity() throws Exception {
		Path outgoing = directory.resolve("quarantine-outgoing");
		Files.createDirectory(outgoing);
		HttpTlsIdentity identity = HttpTlsIdentity.loadOrCreate(directory.resolve("quarantine-identity"), "localhost");
		HttpEnrollmentAuthority authority = new HttpEnrollmentAuthority(identity, directory.resolve("quarantine-authority"));
		seedRunningJournals(outgoing, 128);
		String serverId = "server-128";
		String deliveryId = UUID.nameUUIDFromBytes(serverId.getBytes(StandardCharsets.UTF_8)).toString();
		HttpTransportProtocol.Delivery delivery = new HttpTransportProtocol.Delivery(deliveryId,
				JsonEnvelope.builder("quarantined-retry").build());
		Path serverDirectory = Files.createDirectory(outgoing.resolve(serverId));
		Files.write(serverDirectory.resolve(".pending-" + deliveryId + ".json"),
				HttpTransportProtocol.storedDelivery(delivery));

		assertThrows(java.io.IOException.class, () -> new HttpProxyTransportServer(
				new InetSocketAddress("localhost", 0), identity, authority, outgoing, ignored -> { }),
				"129 distinct durable backend states must be rejected during startup rather than blocking a later retry");

		try (HttpProxyTransportServer.DurableOutgoingQueue queue = new HttpProxyTransportServer.DurableOutgoingQueue(
				outgoing, com.bencodez.simpleapi.file.DurableFiles::forceDirectory)) {
			var loaded = queue.load();
			assertTrue(loaded.containsKey(serverId), "quarantine-only state must reserve its backend identity");
			assertTrue(loaded.get(serverId).isEmpty(), "an unconfirmed quarantine must remain hidden from delivery");
		}
	}

	private static void seedRunningJournals(Path outgoing, int count) throws Exception {
		Path incoming = outgoing.getParent().resolve(outgoing.getFileName() + "-incoming");
		Files.createDirectory(incoming);
		for (int index = 0; index < count; index++) {
			String serverId = "server-" + index;
			HttpInboundDeliveryStore store = HttpInboundDeliveryStore.open(incoming, serverId);
			String deliveryId = UUID.nameUUIDFromBytes(serverId.getBytes(StandardCharsets.UTF_8)).toString();
			store.reserve(deliveryId);
			store.markRunning(deliveryId);
			store.seal();
		}
	}

	private static void seedEmptyJournals(Path outgoing, int count) throws Exception {
		Path incoming = outgoing.getParent().resolve(outgoing.getFileName() + "-incoming");
		Files.createDirectory(incoming);
		for (int index = 0; index < count; index++) {
			HttpInboundDeliveryStore store = HttpInboundDeliveryStore.open(incoming, "server-" + index);
			store.seal();
		}
	}
}
