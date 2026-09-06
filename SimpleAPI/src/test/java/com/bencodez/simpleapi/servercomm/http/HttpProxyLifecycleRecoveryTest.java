package com.bencodez.simpleapi.servercomm.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bencodez.simpleapi.servercomm.codec.JsonEnvelope;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HttpProxyLifecycleRecoveryTest {
	@TempDir Path directory;

	@Test
	void callbackOwnedCloseCompletesItsInboundJournalBeforeItIsSealed() throws Exception {
		Path outgoing = directory.resolve("outgoing");
		HttpTlsIdentity identity = HttpTlsIdentity.loadOrCreate(directory.resolve("proxy"), "localhost");
		HttpEnrollmentAuthority authority = new HttpEnrollmentAuthority(identity, directory.resolve("authority"));
		AtomicReference<HttpProxyTransportServer> proxy = new AtomicReference<>();
		AtomicReference<String> deliveryId = new AtomicReference<>();
		CountDownLatch callbackStarted = new CountDownLatch(1), callbackReturned = new CountDownLatch(1);
		HttpProxyTransportServer server = new HttpProxyTransportServer(new InetSocketAddress("localhost", 0), identity,
				authority, outgoing, received -> {
				deliveryId.set(received.messageId());
				callbackStarted.countDown();
				proxy.get().close();
				callbackReturned.countDown();
			});
		proxy.set(server);
		try {
			server.start();
			HttpConnectionCode code = authority.createConnectionCode("lobby-1", server.endpoint("localhost"),
					Duration.ofMinutes(5));
			HttpClientCredentialStore.ClientCredential credential = HttpBackendTransportConnector.enroll(code, "lobby-1",
					directory.resolve("client"));
			try (HttpBackendTransportConnector connector = new HttpBackendTransportConnector(code, "lobby-1", credential,
					ignored -> { })) {
				connector.start();
				assertTrue(connector.awaitFirstResponse(System.nanoTime() + TimeUnit.SECONDS.toNanos(8)));
				assertTrue(connector.send(JsonEnvelope.builder("callback-close").build()));
				assertTrue(callbackStarted.await(8, TimeUnit.SECONDS));
				assertTrue(callbackReturned.await(3, TimeUnit.SECONDS),
						"a callback must not wait for shutdown of its own handler worker");
			}
			server.close();
			assertNotNull(deliveryId.get());
			assertEquals(HttpInboundDeliveryStore.State.COMPLETED,
					HttpInboundDeliveryStore.inspect(directory.resolve("outgoing-incoming"), "lobby-1").state(deliveryId.get()),
					"the callback completion must be durable before close seals the journal");
		} finally {
			server.close();
		}
	}

	@Test
	void failedNewBackendSetupCleansItsEmptyDirectoryBeforeRetry() throws Exception {
		Path root = directory.resolve("outgoing");
		AtomicBoolean failFirstBackendPublication = new AtomicBoolean(true);
		HttpProxyTransportServer.DurableOutgoingQueue queue = new HttpProxyTransportServer.DurableOutgoingQueue(root,
				forced -> {
					if (forced.equals(root) && failFirstBackendPublication.getAndSet(false))
						throw new IOException("injected backend directory publication failure");
				});
		HttpProxyTransportServer.BackendState state = new HttpProxyTransportServer.BackendState("lobby-1", queue,
				(server, id) -> { });
		HttpTransportProtocol.Delivery delivery = new HttpTransportProtocol.Delivery(UUID.randomUUID().toString(),
				JsonEnvelope.builder("retry-directory").build());

		assertFalse(state.enqueue(delivery));
		assertFalse(Files.exists(root.resolve("lobby-1")),
				"a failed setup must not leave an empty backend directory consuming the global bound");
		assertTrue(state.enqueue(delivery));
		queue.close();
	}

	@Test
	void restartPrunesAccumulatedEmptyBackendDirectoriesBeforeApplyingTheCap() throws Exception {
		Path root = directory.resolve("outgoing");
		HttpProxyTransportServer.DurableOutgoingQueue initial = new HttpProxyTransportServer.DurableOutgoingQueue(root,
				ignored -> { });
		initial.close();
		for (int index = 0; index < 128; index++) Files.createDirectory(root.resolve("server-" + index));

		HttpProxyTransportServer.DurableOutgoingQueue restarted = new HttpProxyTransportServer.DurableOutgoingQueue(root,
				ignored -> { });
		assertTrue(restarted.load().isEmpty());
		HttpProxyTransportServer.BackendState state = new HttpProxyTransportServer.BackendState("replacement", restarted,
				(server, id) -> { });
		assertTrue(state.enqueue(new HttpTransportProtocol.Delivery(UUID.randomUUID().toString(),
				JsonEnvelope.builder("recovered-capacity").build())));
		assertTrue(Files.isDirectory(root.resolve("replacement")));
		restarted.close();
	}

	@Test
	void capPruningPreservesAnEmptyDirectoryUntilItsUnconfirmedAckCanRetry() throws Exception {
		Path root = directory.resolve("outgoing");
		Path lobbyDirectory = root.resolve("lobby-1");
		HttpProxyTransportServer.DurableOutgoingQueue queue = new HttpProxyTransportServer.DurableOutgoingQueue(root,
				ignored -> { });
		HttpProxyTransportServer.BackendState lobby = new HttpProxyTransportServer.BackendState("lobby-1", queue,
				(server, id) -> { });
		HttpTransportProtocol.Delivery delivery = new HttpTransportProtocol.Delivery(UUID.randomUUID().toString(),
				JsonEnvelope.builder("ack-retry").build());
		assertTrue(lobby.enqueue(delivery));

		AtomicBoolean failFinalAckForce = new AtomicBoolean(true);
		try (var forces = org.mockito.Mockito.mockStatic(com.bencodez.simpleapi.file.DurableFiles.class,
				org.mockito.Mockito.CALLS_REAL_METHODS)) {
			forces.when(() -> com.bencodez.simpleapi.file.DurableFiles.forceDirectory(lobbyDirectory)).thenAnswer(call -> {
				if (failFinalAckForce.getAndSet(false)) throw new IOException("injected acknowledgement force failure");
				return call.callRealMethod();
			});
			assertThrows(IOException.class, () -> lobby.acknowledge(List.of(delivery.id())));
			assertTrue(Files.isDirectory(lobbyDirectory), "the unlink has happened but its directory fsync is unresolved");

			HttpProxyTransportServer.BackendState replacement = new HttpProxyTransportServer.BackendState("replacement",
					queue, (server, id) -> { });
			assertTrue(replacement.enqueue(new HttpTransportProtocol.Delivery(UUID.randomUUID().toString(),
					JsonEnvelope.builder("trigger-cap-count").build())));
			assertTrue(Files.isDirectory(lobbyDirectory),
					"capacity pruning must retain the directory indexed by the failed acknowledgement");
			lobby.acknowledge(List.of(delivery.id()));
		}
		assertFalse(Files.exists(lobbyDirectory), "the same acknowledgement retry must be able to finish cleanup");
		queue.close();
	}
}
