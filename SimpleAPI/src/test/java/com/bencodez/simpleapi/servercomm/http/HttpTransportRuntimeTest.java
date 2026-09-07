package com.bencodez.simpleapi.servercomm.http;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bencodez.simpleapi.servercomm.codec.JsonEnvelope;
import com.sun.net.httpserver.Headers;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HttpTransportRuntimeTest {
	@TempDir Path directory;

	@Test
	void publicConstructorsRequireDurableOutgoingDirectory() throws Exception {
		HttpTlsIdentity identity = HttpTlsIdentity.loadOrCreate(directory.resolve("guard-proxy"), "localhost");
		HttpEnrollmentAuthority authority = new HttpEnrollmentAuthority(identity, directory.resolve("guard-authority"));
		InetSocketAddress bind = new InetSocketAddress("localhost", 0);
		assertThrows(NullPointerException.class, () -> new HttpProxyTransportServer(bind, identity, authority,
				null, ignored -> { }));
		assertThrows(NullPointerException.class, () -> new HttpProxyTransportServer(bind, identity, authority,
				null, ignored -> { }, (serverId, deliveryId) -> { }));
	}

	@Test
	void endpointHelperSupportsIpv6Literals() throws Exception {
		HttpTlsIdentity identity = HttpTlsIdentity.loadOrCreate(directory.resolve("ipv6-proxy"), "::1");
		HttpEnrollmentAuthority authority = new HttpEnrollmentAuthority(identity, directory.resolve("ipv6-authority"));
		try (HttpProxyTransportServer server = new HttpProxyTransportServer(new InetSocketAddress("localhost", 0),
				identity, authority, directory.resolve("ipv6-outgoing"), ignored -> { })) {
			URI endpoint = server.endpoint("::1");
			assertTrue(endpoint.getHost() != null);
			assertTrue(endpoint.toASCIIString().startsWith("https://[::1]:"));
			assertDoesNotThrow(() -> new HttpConnectionCode("lobby-1", endpoint, identity.serverCertificatePin(),
					identity.caCertificatePin(), Instant.now().plusSeconds(60), "A".repeat(43)));
		}
	}

	@Test
	void enrollsThenDeliversBothDirectionsWithAuthenticatedIdentity() throws Exception {
		HttpTlsIdentity identity = HttpTlsIdentity.loadOrCreate(directory.resolve("proxy"), "localhost");
		HttpEnrollmentAuthority authority = new HttpEnrollmentAuthority(identity, directory.resolve("authority"));
		CountDownLatch proxyReceived = new CountDownLatch(1), backendReceived = new CountDownLatch(1);
		AtomicReference<HttpProxyTransportServer.ReceivedEnvelope> received = new AtomicReference<>();
		Path proxyOutgoing = directory.resolve("proxy-outgoing");
		try (HttpProxyTransportServer server = new HttpProxyTransportServer(new InetSocketAddress("localhost", 0), identity, authority,
				proxyOutgoing,
				message -> { received.set(message); proxyReceived.countDown(); })) {
			server.start();
			HttpConnectionCode code = authority.createConnectionCode("lobby-1", server.endpoint("localhost"), Duration.ofMinutes(5));
			HttpBackendTransportConnector.enroll(code, "lobby-1", directory.resolve("client"));
			try (HttpBackendTransportConnector connector = new HttpBackendTransportConnector(directory.resolve("client"),
					envelope -> backendReceived.countDown())) {
				connector.start();
				assertTrue(connector.awaitFirstResponse(System.nanoTime() + TimeUnit.SECONDS.toNanos(8)),
						"an authenticated transport response must make the connector ready");
				assertTrue(connector.send(JsonEnvelope.builder("to-proxy").put("server", "forged").build()));
				assertTrue(proxyReceived.await(8, TimeUnit.SECONDS));
				assertEquals("lobby-1", received.get().serverId());
				assertEquals("lobby-1", received.get().envelope().getFields().get("server"));
				long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
				while (connector.queuedOutgoing() != 0 && System.nanoTime() < deadline) Thread.sleep(10);
				assertEquals(0, connector.queuedOutgoing(), "proxy ACK must remove the exact outbound delivery ID");
				Path proxyInboundFence = directory.resolve("proxy-outgoing-incoming").resolve("lobby-1");
				long confirmationDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
				while (countRegularFiles(proxyInboundFence) != 0L && System.nanoTime() < confirmationDeadline) Thread.sleep(10);
				assertEquals(0L, countRegularFiles(proxyInboundFence),
						"the backend must durably confirm receipt of the proxy ACK");
				assertTrue(server.send("lobby-1", JsonEnvelope.builder("to-backend").build()));
				assertTrue(backendReceived.await(8, TimeUnit.SECONDS));
				long outgoingCleanupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
				while (Files.exists(proxyOutgoing.resolve("lobby-1")) && System.nanoTime() < outgoingCleanupDeadline)
					Thread.sleep(10);
				assertFalse(Files.exists(proxyOutgoing.resolve("lobby-1")),
						"acknowledging the final delivery must remove its empty backend directory");
				Path inboundFence = directory.resolve("client").resolve("http-transport-inbound-deliveries");
				long fenceDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
				while (countRegularFiles(inboundFence) != 0L && System.nanoTime() < fenceDeadline) Thread.sleep(20);
				assertEquals(0L, countRegularFiles(inboundFence), "a confirmed ACK must remove the backend replay fence");
			}
		}
	}

	@Test
	void responseBodyConsumptionRemainsBoundedByRequestTimeout() throws Exception {
		com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
				new InetSocketAddress("localhost", 0), 1);
		CountDownLatch release = new CountDownLatch(1);
		server.createContext("/stall", exchange -> {
			exchange.sendResponseHeaders(200, 8);
			try (var output = exchange.getResponseBody()) {
				output.write(1);
				output.flush();
				try { release.await(5, TimeUnit.SECONDS); }
				catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
			}
		});
		server.start();
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + server.getAddress().getPort()
					+ "/stall")).timeout(Duration.ofMillis(250)).GET().build();
			assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertThrows(java.io.IOException.class,
					() -> HttpBackendTransportConnector.sendLimited(HttpClient.newHttpClient(), request)));
		} finally {
			release.countDown();
			server.stop(0);
		}
	}

	@Test
	void stableProxyDeliveryIdsAreIdempotentAndAcknowledgedBeforeRemoval() throws Exception {
		AtomicLong acknowledged = new AtomicLong();
		HttpProxyTransportServer.BackendState state = new HttpProxyTransportServer.BackendState("lobby-1", null,
				(server, deliveryId) -> acknowledged.incrementAndGet());
		String deliveryId = java.util.UUID.randomUUID().toString();
		HttpTransportProtocol.Delivery delivery = new HttpTransportProtocol.Delivery(deliveryId,
				JsonEnvelope.builder("vote-party").build());
		assertTrue(state.enqueue(delivery));
		assertTrue(state.enqueue(delivery));
		assertFalse(state.enqueue(new HttpTransportProtocol.Delivery(deliveryId,
				JsonEnvelope.builder("different").build())));
		state.acknowledge(java.util.List.of(deliveryId));
		assertEquals(1L, acknowledged.get());
		assertTrue(state.await("lobby-1", java.util.UUID.randomUUID().toString(), 0).messages().isEmpty());
	}

	@Test
	void outgoingQueueRetriesDirectoryPublicationAfterForceFailure() throws Exception {
		Path queueRoot = directory.resolve("retry-outgoing-root");
		java.util.concurrent.atomic.AtomicBoolean failRootPublication = new java.util.concurrent.atomic.AtomicBoolean(true);
		assertThrows(java.io.IOException.class, () -> new HttpProxyTransportServer.DurableOutgoingQueue(queueRoot,
				ignored -> {
					if (failRootPublication.getAndSet(false))
						throw new java.io.IOException("injected root publication failure");
				}));
		assertTrue(Files.isDirectory(queueRoot), "the failed force occurs after the root name is published");
		AtomicLong parentForces = new AtomicLong();
		HttpProxyTransportServer.DurableOutgoingQueue queue = new HttpProxyTransportServer.DurableOutgoingQueue(
				queueRoot, forced -> {
					if (forced.equals(queueRoot.getParent())) parentForces.incrementAndGet();
				});
		assertEquals(1L, parentForces.get(), "reopening an existing root must retry its parent fsync");

		AtomicLong serverRootForces = new AtomicLong();
		java.util.concurrent.atomic.AtomicBoolean failServerPublication = new java.util.concurrent.atomic.AtomicBoolean(true);
		HttpProxyTransportServer.DurableOutgoingQueue serverQueue = new HttpProxyTransportServer.DurableOutgoingQueue(
				directory.resolve("retry-outgoing-server"), forced -> {
					if (forced.getFileName().toString().equals("retry-outgoing-server")) {
						serverRootForces.incrementAndGet();
						if (failServerPublication.getAndSet(false))
							throw new java.io.IOException("injected backend publication failure");
					}
				});
		HttpProxyTransportServer.BackendState state = new HttpProxyTransportServer.BackendState(
				"lobby-1", serverQueue, (server, id) -> { });
		HttpTransportProtocol.Delivery delivery = new HttpTransportProtocol.Delivery(
				java.util.UUID.randomUUID().toString(), JsonEnvelope.builder("retry-directory-force").build());
		assertFalse(state.enqueue(delivery));
		assertEquals(2L, serverRootForces.get(), "failed publication and empty-directory cleanup must both force the parent");
		assertTrue(state.enqueue(delivery));
		assertEquals(3L, serverRootForces.get(), "retrying backend directory creation must force its parent again");
		queue.close();
		serverQueue.close();
	}

	@Test
	void generatedSendExposesRecoverableIdAfterPublicationFailure() throws Exception {
		HttpTlsIdentity identity = HttpTlsIdentity.loadOrCreate(directory.resolve("generated-proxy"), "localhost");
		HttpEnrollmentAuthority authority = new HttpEnrollmentAuthority(identity, directory.resolve("generated-authority"));
		for (boolean persistentFailure : new boolean[] { false, true }) {
			Path queueRoot = directory.resolve("generated-outgoing-" + persistentFailure);
			JsonEnvelope envelope = JsonEnvelope.builder("generated-retry").build();
			String retryId;
			try (HttpProxyTransportServer server = new HttpProxyTransportServer(new InetSocketAddress("localhost", 0),
					identity, authority, queueRoot, ignored -> { })) {
				AtomicLong publicationForces = new AtomicLong();
				try (var forces = org.mockito.Mockito.mockStatic(com.bencodez.simpleapi.file.DurableFiles.class,
						org.mockito.Mockito.CALLS_REAL_METHODS)) {
					forces.when(() -> com.bencodez.simpleapi.file.DurableFiles.forceDirectory(queueRoot.resolve("lobby-1")))
							.thenAnswer(call -> {
								if (publicationForces.incrementAndGet() == 1 || persistentFailure)
									throw new java.io.IOException("injected publication failure");
								return call.callRealMethod();
							});
					HttpProxyTransportServer.DeliveryRetryException retry = assertThrows(
							HttpProxyTransportServer.DeliveryRetryException.class, () -> server.send("lobby-1", envelope));
					retryId = retry.deliveryId();
					assertEquals(retryId, java.util.UUID.fromString(retryId).toString());
				}
				assertTrue(server.backendStateForTest("lobby-1")
						.await("lobby-1", java.util.UUID.randomUUID().toString(), 0).messages().isEmpty());
			}
			try (HttpProxyTransportServer restarted = new HttpProxyTransportServer(new InetSocketAddress("localhost", 0),
					identity, authority, queueRoot, ignored -> { })) {
				assertTrue(restarted.backendStateForTest("lobby-1")
						.await("lobby-1", java.util.UUID.randomUUID().toString(), 0).messages().isEmpty());
				assertTrue(restarted.send("lobby-1", retryId, envelope));
				assertEquals(1L, countRegularFiles(queueRoot));
				var messages = restarted.backendStateForTest("lobby-1")
						.await("lobby-1", java.util.UUID.randomUUID().toString(), 0).messages();
				assertEquals(1, messages.size());
				assertEquals(retryId, messages.iterator().next().id());
				restarted.backendStateForTest("lobby-1").acknowledge(java.util.List.of(retryId));
				assertEquals(0L, countRegularFiles(queueRoot));
			}
		}
	}

	@Test
	void outgoingQueueRecoversWhenPromotionQuarantineRenameFails() throws Exception {
		Path queueRoot = directory.resolve("promotion-rename-failure");
		String deliveryId = java.util.UUID.randomUUID().toString();
		Path serverDirectory = queueRoot.resolve("lobby-1");
		Path pending = serverDirectory.resolve(".pending-" + deliveryId + ".json");
		AtomicLong serverForces = new AtomicLong();
		HttpProxyTransportServer.DurableOutgoingQueue queue = new HttpProxyTransportServer.DurableOutgoingQueue(queueRoot,
				forced -> {
					if (!forced.equals(serverDirectory)) return;
					long attempt = serverForces.incrementAndGet();
					if (attempt == 1L) throw new java.io.IOException("injected initial publication failure");
					if (attempt == 3L) {
						Files.createDirectory(pending);
						throw new java.io.IOException("injected promotion publication failure");
					}
				});
		HttpProxyTransportServer.BackendState state = new HttpProxyTransportServer.BackendState("lobby-1", queue,
				(server, id) -> { });
		HttpTransportProtocol.Delivery delivery = new HttpTransportProtocol.Delivery(deliveryId,
				JsonEnvelope.builder("promotion-rename-failure").build());
		assertFalse(state.enqueue(delivery));
		assertThrows(IllegalStateException.class, () -> state.enqueue(delivery));
		Files.delete(pending);
		assertTrue(state.enqueue(delivery), "same-ID retry must confirm the observed target instead of duplicating it");
		var quarantinedFiles = HttpProxyTransportServer.DurableOutgoingQueue.class.getDeclaredField("quarantinedFiles");
		quarantinedFiles.setAccessible(true);
		@SuppressWarnings("unchecked")
		var quarantined = (java.util.Map<String, java.util.Map<String, Path>>) quarantinedFiles.get(queue);
		assertFalse(quarantined.get("lobby-1").containsKey(deliveryId),
				"target fallback must clear the stale quarantine index");
		state.acknowledge(java.util.List.of(deliveryId));
		assertFalse(Files.exists(serverDirectory));
		queue.close();
	}

	@Test
	void publishedOutgoingDeliveryRemainsTrackedUntilDurabilityCanBeConfirmed() throws Exception {
		AtomicLong forceCalls = new AtomicLong();
		Path queueRoot = directory.resolve("uncertain-outgoing");
		HttpProxyTransportServer.DurableOutgoingQueue queue = new HttpProxyTransportServer.DurableOutgoingQueue(
				queueRoot, ignored -> {
					if (forceCalls.incrementAndGet() == 3L) throw new java.io.IOException("injected directory force failure");
				});
		HttpProxyTransportServer.BackendState state = new HttpProxyTransportServer.BackendState(
				"lobby-1", queue, (server, id) -> { });
		String deliveryId = java.util.UUID.randomUUID().toString();
		HttpTransportProtocol.Delivery delivery = new HttpTransportProtocol.Delivery(deliveryId,
				JsonEnvelope.builder("durable").build());

		assertFalse(state.enqueue(delivery), "post-publication failure must not confirm durable acceptance");
		assertTrue(state.await("lobby-1", java.util.UUID.randomUUID().toString(), 0).messages().isEmpty(),
				"an uncertain publication must remain hidden until its durability retry succeeds");
		assertEquals(1L, countRegularFiles(queueRoot));
		queue.close();
		HttpProxyTransportServer.DurableOutgoingQueue restartedQueue = new HttpProxyTransportServer.DurableOutgoingQueue(
				queueRoot, com.bencodez.simpleapi.file.DurableFiles::forceDirectory);
		assertEquals(java.util.List.of(), restartedQueue.load().get("lobby-1"),
				"a quarantine must reserve its backend without exposing an operation whose sender observed rejection");
		HttpProxyTransportServer.BackendState restarted = new HttpProxyTransportServer.BackendState(
				"lobby-1", restartedQueue, (server, id) -> { });
		assertTrue(restarted.await("lobby-1", java.util.UUID.randomUUID().toString(), 0).messages().isEmpty());
		assertTrue(restarted.enqueue(delivery), "same-ID retry must confirm the quarantined file");
		assertEquals(1L, countRegularFiles(queueRoot), "durability retry must not create a duplicate file");
		assertEquals(java.util.List.of(delivery),
				restarted.await("lobby-1", java.util.UUID.randomUUID().toString(), 0).messages());
		restartedQueue.close();
		HttpProxyTransportServer.DurableOutgoingQueue confirmedQueue = new HttpProxyTransportServer.DurableOutgoingQueue(
				queueRoot, com.bencodez.simpleapi.file.DurableFiles::forceDirectory);
		java.util.List<HttpTransportProtocol.Delivery> confirmed = confirmedQueue.load().get("lobby-1");
		assertEquals(1, confirmed.size());
		assertTrue(java.util.Arrays.equals(HttpTransportProtocol.storedDelivery(delivery),
				HttpTransportProtocol.storedDelivery(confirmed.get(0))),
				"a confirmed same-ID retry must become deliverable after restart");
		HttpProxyTransportServer.BackendState confirmedState = new HttpProxyTransportServer.BackendState(
				"lobby-1", confirmedQueue, (server, id) -> { });
		assertTrue(confirmedState.enqueue(delivery));
		confirmedState.acknowledge(java.util.List.of(deliveryId));
		assertEquals(0L, countRegularFiles(queueRoot), "the tracked published file must be removable by ACK");
		confirmedQueue.close();
	}

	@Test
	void acknowledgedBackendDropsItsEmptyOutgoingIndexes() throws Exception {
		Path queueRoot = directory.resolve("acknowledged-index-cleanup");
		try (HttpProxyTransportServer.DurableOutgoingQueue queue = new HttpProxyTransportServer.DurableOutgoingQueue(
				queueRoot, com.bencodez.simpleapi.file.DurableFiles::forceDirectory)) {
			HttpProxyTransportServer.BackendState state = new HttpProxyTransportServer.BackendState(
					"lobby-1", queue, (server, id) -> { });
			HttpTransportProtocol.Delivery delivery = new HttpTransportProtocol.Delivery(
					java.util.UUID.randomUUID().toString(), JsonEnvelope.builder("cleanup").build());
			assertTrue(state.enqueue(delivery));
			state.acknowledge(java.util.List.of(delivery.id()));

			var quarantinedField = HttpProxyTransportServer.DurableOutgoingQueue.class
					.getDeclaredField("quarantinedFiles");
			quarantinedField.setAccessible(true);
			@SuppressWarnings("unchecked")
			var quarantined = (java.util.Map<String, java.util.Map<String, Path>>) quarantinedField.get(queue);
			assertFalse(quarantined.containsKey("lobby-1"),
					"a deleted backend queue must not retain an empty quarantine index");
		}
	}

	@Test
	void unresolvedOutgoingPublicationDoesNotReportAFalseRejection() throws Exception {
		AtomicLong forceCalls = new AtomicLong();
		java.util.concurrent.atomic.AtomicBoolean failForces = new java.util.concurrent.atomic.AtomicBoolean(true);
		Path queueRoot = directory.resolve("unresolved-outgoing");
		HttpProxyTransportServer.DurableOutgoingQueue queue = new HttpProxyTransportServer.DurableOutgoingQueue(
				queueRoot, ignored -> {
					if (forceCalls.incrementAndGet() >= 3L && failForces.get())
						throw new java.io.IOException("persistent directory force failure");
				});
		HttpProxyTransportServer.BackendState state = new HttpProxyTransportServer.BackendState(
				"lobby-1", queue, (server, id) -> { });
		HttpTransportProtocol.Delivery delivery = new HttpTransportProtocol.Delivery(
				java.util.UUID.randomUUID().toString(), JsonEnvelope.builder("unresolved").build());
		assertThrows(IllegalStateException.class, () -> state.enqueue(delivery),
				"an indeterminate rollback must not be reported as a definitive false result");
		failForces.set(false);
		assertTrue(state.enqueue(delivery), "a same-ID retry must recover the observed quarantine");
		assertEquals(1L, countRegularFiles(queueRoot), "recovery must not leave duplicate queue entries");
		queue.close();
		HttpProxyTransportServer.DurableOutgoingQueue restarted = new HttpProxyTransportServer.DurableOutgoingQueue(
				queueRoot, com.bencodez.simpleapi.file.DurableFiles::forceDirectory);
		assertEquals(1, restarted.load().get("lobby-1").size());
		restarted.close();
	}

	@Test
	void proxyInboundCompletionSurvivesRestartBeforeAcknowledgement() throws Exception {
		Path root = directory.resolve("proxy-incoming");
		Files.createDirectory(root);
		String deliveryId = java.util.UUID.randomUUID().toString();
		HttpTransportProtocol.Delivery delivery = new HttpTransportProtocol.Delivery(deliveryId,
				JsonEnvelope.builder("backend-event").build());
		HttpInboundDeliveryStore firstStore = HttpInboundDeliveryStore.open(root, "lobby-1");
		HttpProxyTransportServer.BackendState first = new HttpProxyTransportServer.BackendState(
				"lobby-1", null, firstStore, (server, id) -> { });
		assertEquals(java.util.List.of(delivery), first.acceptIncoming(java.util.List.of(delivery)));
		first.beginIncoming(deliveryId);
		first.completeIncomingDurably(deliveryId);
		first.completeIncoming(deliveryId, true);
		firstStore.seal();

		HttpInboundDeliveryStore restartedStore = HttpInboundDeliveryStore.open(root, "lobby-1");
		HttpProxyTransportServer.BackendState restarted = new HttpProxyTransportServer.BackendState(
				"lobby-1", null, restartedStore, (server, id) -> { });
		assertTrue(restarted.acceptIncoming(java.util.List.of(delivery)).isEmpty(),
				"a completed callback must not run again after a lost response and proxy restart");
		HttpProxyTransportServer.Response response = restarted.await("lobby-1",
				java.util.UUID.randomUUID().toString(), 0);
		assertEquals(java.util.List.of(deliveryId), response.acks());
		restarted.confirmIncoming(response.acks());
		restartedStore.seal();
		HttpInboundDeliveryStore confirmedStore = HttpInboundDeliveryStore.open(root, "lobby-1");
		HttpProxyTransportServer.BackendState confirmed = new HttpProxyTransportServer.BackendState(
				"lobby-1", null, confirmedStore, (server, id) -> { });
		assertEquals(java.util.List.of(delivery), confirmed.acceptIncoming(java.util.List.of(delivery)),
				"only an acknowledgement confirmation may retire the durable replay fence");
	}

	@Test
	void inboundJournalHasOneWriterWhileReadOnlyInspectionRemainsAvailable() throws Exception {
		Path root = directory.resolve("exclusive-incoming");
		Files.createDirectory(root);
		String deliveryId = java.util.UUID.randomUUID().toString();
		HttpInboundDeliveryStore owner = HttpInboundDeliveryStore.open(root, "lobby-1");
		owner.reserve(deliveryId);
		assertThrows(java.io.IOException.class, () -> HttpInboundDeliveryStore.open(root, "lobby-1"),
				"a live journal owner must exclude a stale in-memory writer");
		assertEquals(HttpInboundDeliveryStore.State.RESERVED,
				HttpInboundDeliveryStore.inspect(root, "lobby-1").state(deliveryId));
		owner.seal();
		HttpInboundDeliveryStore successor = HttpInboundDeliveryStore.open(root, "lobby-1");
		successor.markRunning(deliveryId);
		successor.seal();
	}

	@Test
	void backendConnectorReleasesJournalOwnershipOnlyAfterClose() throws Exception {
		HttpTlsIdentity identity = HttpTlsIdentity.loadOrCreate(directory.resolve("owner-proxy"), "localhost");
		HttpTlsIdentity.IssuedClientCertificate issued = identity.issueClientCertificate("lobby-1");
		HttpConnectionCode code = new HttpConnectionCode("lobby-1", URI.create("https://localhost:8443/"),
				identity.serverCertificatePin(), identity.caCertificatePin(), Instant.now().plusSeconds(300), "A".repeat(43));
		Path credentials = directory.resolve("owner-client");
		HttpClientCredentialStore.saveEnrolled(credentials, code, issued);
		try (HttpBackendTransportConnector owner = new HttpBackendTransportConnector(credentials, ignored -> { })) {
			assertThrows(java.io.IOException.class, () -> new HttpBackendTransportConnector(credentials, ignored -> { }),
					"two live connectors must not own the same inbound delivery journal");
		}
		try (HttpBackendTransportConnector successor = new HttpBackendTransportConnector(credentials, ignored -> { })) {
			assertFalse(successor.pollerAlive());
		}
	}

	@Test
	void completedInboundPublicationRetriesForceBeforeProxyAcknowledgement() throws Exception {
		Path root = directory.resolve("proxy-incoming-force-retry");
		Files.createDirectory(root);
		String deliveryId = java.util.UUID.randomUUID().toString();
		Path inboundDirectory = root.resolve("lobby-1");
		Path completed = inboundDirectory.resolve(deliveryId + ".completed");
		HttpTransportProtocol.Delivery delivery = new HttpTransportProtocol.Delivery(deliveryId,
				JsonEnvelope.builder("backend-event").build());
		HttpInboundDeliveryStore store = HttpInboundDeliveryStore.open(root, "lobby-1");
		HttpProxyTransportServer.BackendState state = new HttpProxyTransportServer.BackendState(
				"lobby-1", null, store, (server, id) -> { });
		try (var forces = org.mockito.Mockito.mockStatic(com.bencodez.simpleapi.file.DurableFiles.class,
				org.mockito.Mockito.CALLS_REAL_METHODS)) {
			forces.when(() -> com.bencodez.simpleapi.file.DurableFiles.forceDirectory(inboundDirectory))
					.thenAnswer(call -> {
						if (Files.exists(completed)) throw new java.io.IOException("injected completed fsync failure");
						return call.callRealMethod();
					});
			assertEquals(java.util.List.of(delivery), state.acceptIncoming(java.util.List.of(delivery)));
			state.beginIncoming(deliveryId);
			assertThrows(com.bencodez.simpleapi.file.DurableFiles.PublishedException.class,
					() -> state.completeIncomingDurably(deliveryId));
			state.completeIncoming(deliveryId, false);
			assertEquals(HttpInboundDeliveryStore.State.COMPLETED, store.state(deliveryId));
			assertTrue(state.acceptIncoming(java.util.List.of(delivery)).isEmpty(),
					"a visible completed target must never rerun its callback while fsync is unresolved");
			assertTrue(state.await("lobby-1", java.util.UUID.randomUUID().toString(), 0).acks().isEmpty(),
					"a completed target must not be acknowledged before its fsync succeeds");
		}
		assertTrue(state.acceptIncoming(java.util.List.of(delivery)).isEmpty(),
				"recovered publication must be acknowledged rather than dispatched again");
		assertEquals(java.util.List.of(deliveryId), state.await("lobby-1", java.util.UUID.randomUUID().toString(), 0).acks());
	}

	@Test
	void reservedInboundPublicationRetriesBeforeProxyCallback() throws Exception {
		Path root = directory.resolve("reserved-force-proxy");
		Files.createDirectory(root);
		String deliveryId = java.util.UUID.randomUUID().toString();
		Path inboundDirectory = root.resolve("lobby-1");
		Path reserved = inboundDirectory.resolve(deliveryId + ".reserved");
		CountDownLatch firstForce = new CountDownLatch(1), secondForce = new CountDownLatch(1);
		java.util.concurrent.atomic.AtomicInteger forceCalls = new java.util.concurrent.atomic.AtomicInteger();
		java.util.concurrent.atomic.AtomicInteger callbacks = new java.util.concurrent.atomic.AtomicInteger();
		HttpTransportProtocol.Delivery delivery = new HttpTransportProtocol.Delivery(deliveryId, JsonEnvelope.builder("vote").build());
		HttpInboundDeliveryStore store = HttpInboundDeliveryStore.open(root, "lobby-1");
		HttpProxyTransportServer.BackendState state = new HttpProxyTransportServer.BackendState(
				"lobby-1", null, store, (server, id) -> { });
		try (var forces = org.mockito.Mockito.mockStatic(com.bencodez.simpleapi.file.DurableFiles.class,
				org.mockito.Mockito.CALLS_REAL_METHODS)) {
			forces.when(() -> com.bencodez.simpleapi.file.DurableFiles.forceDirectory(inboundDirectory))
					.thenAnswer(call -> {
						if (Files.exists(reserved)) {
							if (forceCalls.incrementAndGet() == 1) firstForce.countDown(); else secondForce.countDown();
							throw new java.io.IOException("injected reservation fsync failure");
						}
						return call.callRealMethod();
					});
			assertEquals(java.util.List.of(delivery), state.acceptIncoming(java.util.List.of(delivery)));
			assertThrows(com.bencodez.simpleapi.file.DurableFiles.PublishedException.class,
					() -> state.beginIncoming(deliveryId));
			state.completeIncoming(deliveryId, false);
			assertTrue(firstForce.await(2, TimeUnit.SECONDS));
			assertEquals(java.util.List.of(delivery), state.acceptIncoming(java.util.List.of(delivery)));
			assertThrows(java.io.IOException.class, () -> state.beginIncoming(deliveryId));
			state.completeIncoming(deliveryId, false);
			assertTrue(secondForce.await(2, TimeUnit.SECONDS));
			assertEquals(0, callbacks.get(), "an unconfirmed reservation must not enter the callback");
			assertTrue(state.await("lobby-1", java.util.UUID.randomUUID().toString(), 0).acks().isEmpty());
		}
		assertEquals(java.util.List.of(delivery), state.acceptIncoming(java.util.List.of(delivery)));
		state.beginIncoming(deliveryId);
		callbacks.incrementAndGet(); // Models the callback reached only after beginIncoming's durable RUNNING transition.
		state.completeIncomingDurably(deliveryId);
		state.completeIncoming(deliveryId, true);
		assertEquals(java.util.List.of(deliveryId), state.await("lobby-1", java.util.UUID.randomUUID().toString(), 0).acks());
		assertEquals(1, callbacks.get());
	}

	@Test
	void runningPublicationRollbackLeavesAReservationSafeAfterRestart() throws Exception {
		Path root = directory.resolve("running-rollback-restart");
		Files.createDirectory(root);
		String deliveryId = java.util.UUID.randomUUID().toString();
		Path inboundDirectory = root.resolve("lobby-1");
		Path running = inboundDirectory.resolve(deliveryId + ".running");
		HttpInboundDeliveryStore store = HttpInboundDeliveryStore.open(root, "lobby-1");
		try (var forces = org.mockito.Mockito.mockStatic(com.bencodez.simpleapi.file.DurableFiles.class,
				org.mockito.Mockito.CALLS_REAL_METHODS)) {
			java.util.concurrent.atomic.AtomicBoolean failed = new java.util.concurrent.atomic.AtomicBoolean();
			forces.when(() -> com.bencodez.simpleapi.file.DurableFiles.forceDirectory(inboundDirectory))
					.thenAnswer(call -> {
						if (Files.exists(running) && !failed.getAndSet(true))
							throw new java.io.IOException("injected running fsync failure");
						return call.callRealMethod();
					});
			store.reserve(deliveryId);
			assertThrows(com.bencodez.simpleapi.file.DurableFiles.PublishedException.class,
					() -> store.markRunning(deliveryId));
			assertEquals(HttpInboundDeliveryStore.State.RESERVED, store.state(deliveryId));
		}
		store.seal();
		assertEquals(HttpInboundDeliveryStore.State.RESERVED,
				HttpInboundDeliveryStore.inspect(root, "lobby-1").state(deliveryId),
				"a callback never exposed must be recoverable after a one-shot RUNNING force failure");
	}

	@Test
	void proxyRetriesKnownNotStartedRunningRollbackBeforeDispatch() throws Exception {
		Path root = directory.resolve("running-rollback-proxy");
		Files.createDirectory(root);
		String deliveryId = java.util.UUID.randomUUID().toString();
		Path inboundDirectory = root.resolve("lobby-1");
		Path running = inboundDirectory.resolve(deliveryId + ".running");
		AtomicInteger failures = new AtomicInteger(2), callbacks = new AtomicInteger();
		java.util.concurrent.atomic.AtomicBoolean rollbackStarted = new java.util.concurrent.atomic.AtomicBoolean();
		HttpTransportProtocol.Delivery delivery = new HttpTransportProtocol.Delivery(deliveryId, JsonEnvelope.builder("vote").build());
		HttpInboundDeliveryStore store = HttpInboundDeliveryStore.open(root, "lobby-1");
		HttpProxyTransportServer.BackendState state = new HttpProxyTransportServer.BackendState(
				"lobby-1", null, store, (server, id) -> { });
		try (var forces = org.mockito.Mockito.mockStatic(com.bencodez.simpleapi.file.DurableFiles.class,
				org.mockito.Mockito.CALLS_REAL_METHODS)) {
			forces.when(() -> com.bencodez.simpleapi.file.DurableFiles.forceDirectory(inboundDirectory))
					.thenAnswer(call -> {
						if (Files.exists(running)) rollbackStarted.set(true);
						if (rollbackStarted.get() && failures.getAndDecrement() > 0)
							throw new java.io.IOException("injected running or rollback fsync failure");
						return call.callRealMethod();
					});
			assertEquals(java.util.List.of(delivery), state.acceptIncoming(java.util.List.of(delivery)));
			assertThrows(com.bencodez.simpleapi.file.DurableFiles.PublishedException.class,
					() -> state.beginIncoming(deliveryId));
			state.completeIncoming(deliveryId, false);
			assertEquals(java.util.List.of(delivery), state.acceptIncoming(java.util.List.of(delivery)),
					"the in-process known-not-started RUNNING state must recover once its rollback force succeeds");
			state.beginIncoming(deliveryId);
			callbacks.incrementAndGet();
			state.completeIncomingDurably(deliveryId);
			state.completeIncoming(deliveryId, true);
		}
		assertEquals(1, callbacks.get(), "the callback becomes eligible exactly once after RUNNING is durable");
	}

	@Test
	void backendRetriesKnownNotStartedRunningRollbackBeforeDispatch() throws Exception {
		HttpTlsIdentity identity = HttpTlsIdentity.loadOrCreate(directory.resolve("running-backend-proxy"), "localhost");
		HttpTlsIdentity.IssuedClientCertificate issued = identity.issueClientCertificate("lobby-1");
		HttpConnectionCode code = new HttpConnectionCode("lobby-1", java.net.URI.create("https://localhost:8443/"),
				identity.serverCertificatePin(), identity.caCertificatePin(), Instant.now().plusSeconds(300), "D".repeat(43));
		Path clientDirectory = directory.resolve("running-backend-client");
		HttpClientCredentialStore.saveEnrolled(clientDirectory, code, issued);
		String deliveryId = java.util.UUID.randomUUID().toString();
		Path inboundDirectory = clientDirectory.resolve("http-transport-inbound-deliveries");
		Path running = inboundDirectory.resolve(deliveryId + ".running");
		AtomicInteger failures = new AtomicInteger(2), callbacks = new AtomicInteger();
		java.util.concurrent.atomic.AtomicBoolean rollbackStarted = new java.util.concurrent.atomic.AtomicBoolean();
		HttpTransportProtocol.Delivery delivery = new HttpTransportProtocol.Delivery(deliveryId, JsonEnvelope.builder("vote").build());
		try (var forces = org.mockito.Mockito.mockStatic(com.bencodez.simpleapi.file.DurableFiles.class,
				org.mockito.Mockito.CALLS_REAL_METHODS);
				HttpBackendTransportConnector connector = new HttpBackendTransportConnector(clientDirectory,
						ignored -> callbacks.incrementAndGet())) {
			forces.when(() -> com.bencodez.simpleapi.file.DurableFiles.forceDirectory(inboundDirectory))
					.thenAnswer(call -> {
						if (Files.exists(running)) rollbackStarted.set(true);
						if (rollbackStarted.get() && failures.getAndDecrement() > 0)
							throw new java.io.IOException("injected running or rollback fsync failure");
						return call.callRealMethod();
					});
			var inboundField = HttpBackendTransportConnector.class.getDeclaredField("inboundDeliveries");
			inboundField.setAccessible(true);
			HttpInboundDeliveryStore store = (HttpInboundDeliveryStore) inboundField.get(connector);
			store.reserve(deliveryId);
			assertThrows(com.bencodez.simpleapi.file.DurableFiles.PublishedException.class,
					() -> store.markRunning(deliveryId));
			java.util.List<HttpTransportProtocol.Delivery> retried = connector.accept(java.util.List.of(delivery));
			assertEquals(java.util.List.of(delivery), retried);
			connector.dispatch(retried.get(0));
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
			while (callbacks.get() == 0 && System.nanoTime() < deadline) Thread.sleep(5);
			assertEquals(1, callbacks.get());
		}
	}

	@Test
	void failedAcknowledgementCallbackRetainsProxyDelivery() throws Exception {
		HttpProxyTransportServer.BackendState state = new HttpProxyTransportServer.BackendState("lobby-1", null,
				(server, deliveryId) -> { throw new java.io.IOException("cache save failed"); });
		String deliveryId = java.util.UUID.randomUUID().toString();
		assertTrue(state.enqueue(new HttpTransportProtocol.Delivery(deliveryId,
				JsonEnvelope.builder("vote-party").build())));
		assertThrows(java.io.IOException.class, () -> state.acknowledge(java.util.List.of(deliveryId)));
		assertEquals(deliveryId, state.await("lobby-1", java.util.UUID.randomUUID().toString(), 0)
				.messages().iterator().next().id());
	}

	@Test
	void oppositeDirectionIdsUseSeparateAcknowledgementNamespaces() throws Exception {
		String deliveryId = java.util.UUID.randomUUID().toString();
		Path incomingRoot = directory.resolve("separate-ack-incoming");
		Files.createDirectory(incomingRoot);
		HttpInboundDeliveryStore incoming = HttpInboundDeliveryStore.open(incomingRoot, "lobby-1");
		HttpProxyTransportServer.BackendState state = new HttpProxyTransportServer.BackendState(
				"lobby-1", null, incoming, (server, id) -> { });
		assertTrue(state.enqueue(new HttpTransportProtocol.Delivery(deliveryId,
				JsonEnvelope.builder("proxy-reply").build())));
		HttpTransportProtocol.Delivery backendMessage = new HttpTransportProtocol.Delivery(deliveryId,
				JsonEnvelope.builder("backend-request").build());
		assertEquals(java.util.List.of(backendMessage), state.acceptIncoming(java.util.List.of(backendMessage)));
		state.beginIncoming(deliveryId);
		state.completeIncomingDurably(deliveryId);
		state.completeIncoming(deliveryId, true);

		byte[] request = HttpTransportProtocol.request("lobby-1", java.util.UUID.randomUUID().toString(), 0,
				java.util.List.of(), java.util.List.of(deliveryId), java.util.List.of());
		HttpTransportProtocol.Packet packet = HttpTransportProtocol.parsePacket(request);
		state.confirmIncoming(packet.ackConfirmations());
		state.acknowledge(packet.acks());
		assertEquals(deliveryId, state.await("lobby-1", java.util.UUID.randomUUID().toString(), 0)
				.messages().iterator().next().id(),
				"confirming the backend-origin acknowledgement must not acknowledge a same-ID proxy reply");
	}

	@Test
	void closeWaitsForTheCredentialOwningPollerToStop() throws Exception {
		HttpTlsIdentity identity = HttpTlsIdentity.loadOrCreate(directory.resolve("close-proxy"), "localhost");
		HttpEnrollmentAuthority authority = new HttpEnrollmentAuthority(identity, directory.resolve("close-authority"));
		try (HttpProxyTransportServer server = new HttpProxyTransportServer(new InetSocketAddress("localhost", 0),
				identity, authority, ignored -> { })) {
			server.start();
			Path clientDirectory = directory.resolve("close-client");
			HttpConnectionCode code = authority.createConnectionCode("lobby-1", server.endpoint("localhost"),
					Duration.ofMinutes(5));
			HttpBackendTransportConnector.enroll(code, "lobby-1", clientDirectory);
			try (HttpBackendTransportConnector connector = new HttpBackendTransportConnector(clientDirectory, ignored -> { })) {
				connector.start();
				assertTrue(connector.awaitFirstResponse(System.nanoTime() + TimeUnit.SECONDS.toNanos(8)));
				connector.close();
				assertFalse(connector.pollerAlive(),
						"credential-directory ownership must outlive every poller filesystem mutation");
			}
		}
	}

	@Test
	void closeDrainsRunningCallbacksBeforeSealingTheirJournal() throws Exception {
		HttpTlsIdentity identity = HttpTlsIdentity.loadOrCreate(directory.resolve("close-callback-proxy"), "localhost");
		HttpTlsIdentity.IssuedClientCertificate issued = identity.issueClientCertificate("lobby-1");
		HttpConnectionCode code = new HttpConnectionCode("lobby-1", URI.create("https://localhost:8443/"),
				identity.serverCertificatePin(), identity.caCertificatePin(), Instant.now().plusSeconds(300), "A".repeat(43));
		Path clientDirectory = directory.resolve("close-callback-client");
		HttpClientCredentialStore.saveEnrolled(clientDirectory, code, issued);
		CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
		HttpBackendTransportConnector connector = new HttpBackendTransportConnector(clientDirectory, ignored -> {
			started.countDown();
			try { release.await(); }
			catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
		});
		String id = java.util.UUID.randomUUID().toString();
		connector.dispatch(new HttpTransportProtocol.Delivery(id, JsonEnvelope.builder("close-callback").build()));
		assertTrue(started.await(2, TimeUnit.SECONDS));
		Thread closer = new Thread(connector::close, "close-callback-test");
		closer.start();
		try {
			Thread.sleep(100);
			assertTrue(closer.isAlive(), "close must wait for a running callback to finish its journal transition");
		} finally { release.countDown(); }
		closer.join(3000);
		assertFalse(closer.isAlive());
		assertEquals(HttpInboundDeliveryStore.State.COMPLETED,
				HttpInboundDeliveryStore.inspect(clientDirectory).state(id));
	}

	@Test
	void interruptedCloseRemainsBoundedWhenACallbackIgnoresInterruption() throws Exception {
		HttpTlsIdentity identity = HttpTlsIdentity.loadOrCreate(directory.resolve("bounded-close-proxy"), "localhost");
		HttpTlsIdentity.IssuedClientCertificate issued = identity.issueClientCertificate("lobby-1");
		HttpConnectionCode code = new HttpConnectionCode("lobby-1", URI.create("https://localhost:8443/"),
				identity.serverCertificatePin(), identity.caCertificatePin(), Instant.now().plusSeconds(300), "A".repeat(43));
		Path clientDirectory = directory.resolve("bounded-close-client");
		HttpClientCredentialStore.saveEnrolled(clientDirectory, code, issued);
		CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), stopped = new CountDownLatch(1);
		HttpBackendTransportConnector connector = new HttpBackendTransportConnector(clientDirectory, ignored -> {
			started.countDown();
			while (release.getCount() != 0L) try { release.await(); }
			catch (InterruptedException ignoredInterrupt) { }
			stopped.countDown();
		});
		String id = java.util.UUID.randomUUID().toString();
		connector.dispatch(new HttpTransportProtocol.Delivery(id, JsonEnvelope.builder("bounded-close").build()));
		assertTrue(started.await(2, TimeUnit.SECONDS));
		Thread closer = new Thread(connector::close, "bounded-close-test");
		closer.start();
		closer.interrupt();
		closer.join(2500);
		assertFalse(closer.isAlive(), "a non-cooperative application callback must not hang connector shutdown");
		try {
			release.countDown();
			assertTrue(stopped.await(2, TimeUnit.SECONDS));
		} finally { release.countDown(); }
		connector.close();
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
		HttpInboundDeliveryStore.State state;
		do {
			state = HttpInboundDeliveryStore.inspect(clientDirectory).state(id);
			if (state == HttpInboundDeliveryStore.State.COMPLETED) break;
			Thread.sleep(10);
		} while (System.nanoTime() < deadline);
		assertEquals(HttpInboundDeliveryStore.State.COMPLETED, state,
				"the deferred journal seal must allow the late callback completion to become durable");
	}

	@Test
	void proxyShutdownGivesInterruptedCallbacksTimeToFinish() throws Exception {
		java.util.concurrent.ThreadPoolExecutor executor = new java.util.concurrent.ThreadPoolExecutor(1, 1, 0L,
				TimeUnit.MILLISECONDS, new java.util.concurrent.ArrayBlockingQueue<>(1));
		CountDownLatch started = new CountDownLatch(1), finished = new CountDownLatch(1);
		executor.execute(() -> {
			started.countDown();
			try { new CountDownLatch(1).await(); }
			catch (InterruptedException stopRequested) {
				try { Thread.sleep(100); }
				catch (InterruptedException repeated) { Thread.currentThread().interrupt(); }
			} finally { finished.countDown(); }
		});
		assertTrue(started.await(2, TimeUnit.SECONDS));
		Thread closer = new Thread(() -> HttpProxyTransportServer.shutdown(executor), "proxy-shutdown-test");
		closer.start();
		closer.interrupt();
		closer.join(2500);
		assertFalse(closer.isAlive());
		assertEquals(0L, finished.getCount(),
				"forced shutdown must give a cooperative callback time to finish before journals are sealed");
	}

	@Test
	void finalFlushDeliversMessagesQueuedBehindAnActiveLongPoll() throws Exception {
		HttpTlsIdentity identity = HttpTlsIdentity.loadOrCreate(directory.resolve("flush-proxy"), "localhost");
		HttpEnrollmentAuthority authority = new HttpEnrollmentAuthority(identity, directory.resolve("flush-authority"));
		CountDownLatch received = new CountDownLatch(1);
		AtomicReference<JsonEnvelope> envelope = new AtomicReference<>();
		try (HttpProxyTransportServer server = new HttpProxyTransportServer(new InetSocketAddress("localhost", 0),
				identity, authority, message -> {
					envelope.set(message.envelope());
					received.countDown();
				})) {
			server.start();
			Path clientDirectory = directory.resolve("flush-client");
			HttpConnectionCode code = authority.createConnectionCode("lobby-1", server.endpoint("localhost"),
					Duration.ofMinutes(5));
			HttpBackendTransportConnector.enroll(code, "lobby-1", clientDirectory);
			HttpBackendTransportConnector connector = new HttpBackendTransportConnector(clientDirectory, ignored -> { });
			connector.start();
			assertTrue(connector.awaitFirstResponse(System.nanoTime() + TimeUnit.SECONDS.toNanos(8)));
			assertTrue(connector.send(JsonEnvelope.builder("backend-stopped").build()));
			try {
				assertTrue(connector.flushOutgoing(System.nanoTime() + TimeUnit.SECONDS.toNanos(5)));
				assertTrue(received.await(1, TimeUnit.SECONDS));
				assertEquals("backend-stopped", envelope.get().getSubChannel());
				assertEquals(0, connector.queuedOutgoing());
			} finally { connector.close(); }
		}
	}

	@Test
	void normalTransportRejectsAClientWithoutCertificate() throws Exception {
		HttpTlsIdentity identity = HttpTlsIdentity.loadOrCreate(directory.resolve("proxy"), "localhost");
		HttpEnrollmentAuthority authority = new HttpEnrollmentAuthority(identity, directory.resolve("authority"));
		try (HttpProxyTransportServer server = new HttpProxyTransportServer(new InetSocketAddress("localhost", 0), identity, authority, ignored -> { })) {
			server.start();
			HttpConnectionCode code = authority.createConnectionCode("lobby-1", server.endpoint("localhost"), Duration.ofMinutes(5));
			HttpClient client = HttpClient.newBuilder().sslContext(HttpPinnedTls.clientContext(code)).build();
			byte[] body = HttpTransportProtocol.request("lobby-1", java.util.UUID.randomUUID().toString(), 0,
					java.util.List.of(), java.util.List.of(), java.util.List.of());
			HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(code.endpoint().resolve("v1/transport"))
				.timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofByteArray());
			assertEquals(401, response.statusCode());
		}
	}

	@Test
	void fixedRequestBodiesReportProtocolErrorsBeforeAdmissionErrors() {
		Headers headers = new Headers();
		assertEquals(411, HttpProxyTransportServer.fixedBodyErrorStatus(headers, 128));
		headers.set("Content-Length", "invalid");
		assertEquals(400, HttpProxyTransportServer.fixedBodyErrorStatus(headers, 128));
		headers.set("Content-Length", "0");
		assertEquals(400, HttpProxyTransportServer.fixedBodyErrorStatus(headers, 128));
		headers.set("Content-Length", "129");
		assertEquals(413, HttpProxyTransportServer.fixedBodyErrorStatus(headers, 128));
		headers.set("Content-Length", "128");
		assertEquals(0, HttpProxyTransportServer.fixedBodyErrorStatus(headers, 128));
		headers.set("Transfer-Encoding", "chunked");
		assertEquals(400, HttpProxyTransportServer.fixedBodyErrorStatus(headers, 128));
	}

	@Test
	void enrollmentPersistenceFailuresReturnServiceUnavailable() throws Exception {
		HttpTlsIdentity identity = HttpTlsIdentity.loadOrCreate(directory.resolve("failed-enroll-proxy"), "localhost");
		Path authorityDirectory = directory.resolve("failed-enroll-authority");
		HttpEnrollmentAuthority authority = new HttpEnrollmentAuthority(identity, authorityDirectory);
		try (HttpProxyTransportServer server = new HttpProxyTransportServer(new InetSocketAddress("localhost", 0),
				identity, authority, ignored -> { })) {
			server.start();
			HttpConnectionCode code = authority.createConnectionCode("lobby-1", server.endpoint("localhost"),
					Duration.ofMinutes(5));
			Path state = authorityDirectory.resolve("http-transport-clients.properties");
			Files.delete(state);
			Files.createDirectory(state);
			byte[] body = ("{\"server\":\"lobby-1\",\"token\":\"" + code.enrollmentToken() + "\"}")
					.getBytes(java.nio.charset.StandardCharsets.UTF_8);
			HttpClient client = HttpClient.newBuilder().sslContext(HttpPinnedTls.clientContext(code)).build();
			HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(code.endpoint().resolve("v1/enroll"))
					.timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofByteArray());
			assertEquals(503, response.statusCode());
		}
	}

	@Test
	void boundedQueuesFailClosed() throws Exception {
		HttpTlsIdentity identity = HttpTlsIdentity.loadOrCreate(directory.resolve("proxy"), "localhost");
		HttpEnrollmentAuthority authority = new HttpEnrollmentAuthority(identity, directory.resolve("authority"));
		try (HttpProxyTransportServer server = new HttpProxyTransportServer(new InetSocketAddress("localhost", 0), identity, authority, ignored -> { })) {
			for (int i = 0; i < HttpTransportProtocol.MAX_QUEUE; i++) assertTrue(server.send("lobby-1", JsonEnvelope.builder("x").build()));
			assertFalse(server.send("lobby-1", JsonEnvelope.builder("x").build()));
			assertFalse(server.send("lobby-1", JsonEnvelope.builder("x").put("large", "x".repeat(HttpTransportProtocol.MAX_ENVELOPE_BYTES)).build()));
		}
	}

	@Test
	void proxyBackendStateIsGloballyBounded() throws Exception {
		HttpTlsIdentity identity = HttpTlsIdentity.loadOrCreate(directory.resolve("bounded-backend-proxy"), "localhost");
		HttpEnrollmentAuthority authority = new HttpEnrollmentAuthority(identity,
				directory.resolve("bounded-backend-authority"));
		try (HttpProxyTransportServer server = new HttpProxyTransportServer(new InetSocketAddress("localhost", 0),
				identity, authority, ignored -> { })) {
			for (int index = 0; index < 128; index++)
				assertTrue(server.send("server-" + index, JsonEnvelope.builder("x").build()));
			assertFalse(server.send("server-overflow", JsonEnvelope.builder("x").build()));
			assertTrue(server.send("server-0", JsonEnvelope.builder("existing").build()),
					"the global bound must not reject an existing backend state");
		}
	}

	@Test
	void proxyReclaimsOnlyQuiescentBackendStateAfterReplayWindow() throws Exception {
		HttpTlsIdentity identity = HttpTlsIdentity.loadOrCreate(directory.resolve("reclaim-proxy"), "localhost");
		HttpEnrollmentAuthority authority = new HttpEnrollmentAuthority(identity,
				directory.resolve("reclaim-authority"));
		AtomicLong nanoTime = new AtomicLong();
		try (HttpProxyTransportServer server = new HttpProxyTransportServer(new InetSocketAddress("localhost", 0),
				identity, authority, null, ignored -> { }, (serverId, deliveryId) -> { }, nanoTime::get)) {
			for (int index = 0; index < 128; index++) {
				HttpProxyTransportServer.BackendState state = server.backendStateForTest("server-" + index);
				assertTrue(state.beginPollForTest());
				state.endPollForTest();
			}
			assertFalse(server.send("replacement", JsonEnvelope.builder("x").build()),
					"fresh state must retain its replay fence");
			nanoTime.addAndGet(TimeUnit.MILLISECONDS.toNanos(HttpTransportProtocol.MAX_CLOCK_SKEW_MILLIS) + 1L);
			assertTrue(server.send("replacement", JsonEnvelope.builder("x").build()),
					"a quiescent state must be reclaimable after captured requests expire");
			assertEquals(128, server.backendCountForTest());
		}
	}

	@Test
	void proxyDoesNotReclaimAQuarantineOnlyBackendAfterReplayWindow() throws Exception {
		Path queueRoot = directory.resolve("quarantine-reclamation-outgoing");
		AtomicLong forceCalls = new AtomicLong();
		String serverId = "quarantined";
		HttpTransportProtocol.Delivery retry = new HttpTransportProtocol.Delivery(
				java.util.UUID.randomUUID().toString(), JsonEnvelope.builder("retry").build());
		try (HttpProxyTransportServer.DurableOutgoingQueue queue = new HttpProxyTransportServer.DurableOutgoingQueue(
				queueRoot, ignored -> {
					if (forceCalls.incrementAndGet() == 3L) throw new java.io.IOException("injected directory force failure");
				})) {
			HttpProxyTransportServer.BackendState state = new HttpProxyTransportServer.BackendState(
					serverId, queue, (server, id) -> { });
			assertFalse(state.enqueue(retry));
		}

		HttpTlsIdentity identity = HttpTlsIdentity.loadOrCreate(directory.resolve("quarantine-reclamation-proxy"), "localhost");
		HttpEnrollmentAuthority authority = new HttpEnrollmentAuthority(identity,
				directory.resolve("quarantine-reclamation-authority"));
		AtomicLong nanoTime = new AtomicLong();
		try (HttpProxyTransportServer server = new HttpProxyTransportServer(new InetSocketAddress("localhost", 0),
				identity, authority, queueRoot, ignored -> { }, (backend, delivery) -> { }, nanoTime::get)) {
			for (int index = 0; index < 127; index++) {
				HttpProxyTransportServer.BackendState state = server.backendStateForTest("idle-" + index);
				assertTrue(state.beginPollForTest());
				state.endPollForTest();
			}
			nanoTime.addAndGet(TimeUnit.MILLISECONDS.toNanos(HttpTransportProtocol.MAX_CLOCK_SKEW_MILLIS) + 1L);
			for (int index = 0; index < 127; index++)
				assertTrue(server.send("replacement-" + index, JsonEnvelope.builder("replacement").build()));
			assertFalse(server.send("overflow", JsonEnvelope.builder("overflow").build()));
			assertTrue(server.backendStateForTest(serverId).enqueue(retry),
					"the identical retry must still reach and promote its quarantined delivery at capacity");
		}
	}

	@Test
	void proxyOutgoingQueueSurvivesRestartUntilBackendAcknowledges() throws Exception {
		Path proxyDirectory = directory.resolve("proxy");
		Path authorityDirectory = directory.resolve("authority");
		Path queueDirectory = directory.resolve("outgoing");
		HttpTlsIdentity identity = HttpTlsIdentity.loadOrCreate(proxyDirectory, "localhost");
		HttpEnrollmentAuthority authority = new HttpEnrollmentAuthority(identity, authorityDirectory);
		HttpProxyTransportServer first = new HttpProxyTransportServer(new InetSocketAddress("localhost", 0),
				identity, authority, queueDirectory, ignored -> { });
		assertTrue(first.send("lobby-1", JsonEnvelope.builder("durable").build()));
		first.close();

		CountDownLatch received = new CountDownLatch(1);
		try (HttpProxyTransportServer restarted = new HttpProxyTransportServer(
				new InetSocketAddress("localhost", 0), identity, authority, queueDirectory, ignored -> { })) {
			restarted.start();
			HttpConnectionCode code = authority.createConnectionCode("lobby-1", restarted.endpoint("localhost"),
					Duration.ofMinutes(5));
			HttpClientCredentialStore.ClientCredential credential = HttpBackendTransportConnector.enroll(code,
					"lobby-1", directory.resolve("durable-client"));
			try (HttpBackendTransportConnector connector = new HttpBackendTransportConnector(code, "lobby-1",
					credential, envelope -> received.countDown())) {
				connector.start();
				assertTrue(received.await(8, TimeUnit.SECONDS));
				long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
				while (countRegularFiles(queueDirectory) != 0L && System.nanoTime() < deadline) Thread.sleep(20);
				assertEquals(0L, countRegularFiles(queueDirectory), "backend ACK must durably remove the delivery");
			}
		}
	}

	@Test
	void pollCreatedBackendStateUsesDurableOutgoingQueue() throws Exception {
		HttpTlsIdentity identity = HttpTlsIdentity.loadOrCreate(directory.resolve("poll-proxy"), "localhost");
		HttpEnrollmentAuthority authority = new HttpEnrollmentAuthority(identity, directory.resolve("poll-authority"));
		Path queueDirectory = directory.resolve("poll-outgoing");
		CountDownLatch proxyReceived = new CountDownLatch(1), backendReceived = new CountDownLatch(1);
		CountDownLatch releaseBackendCallback = new CountDownLatch(1);
		try (HttpProxyTransportServer server = new HttpProxyTransportServer(new InetSocketAddress("localhost", 0),
				identity, authority, queueDirectory, ignored -> proxyReceived.countDown())) {
			server.start();
			HttpConnectionCode code = authority.createConnectionCode("lobby-1", server.endpoint("localhost"),
					Duration.ofMinutes(5));
			HttpClientCredentialStore.ClientCredential credential = HttpBackendTransportConnector.enroll(code,
					"lobby-1", directory.resolve("poll-client"));
			try (HttpBackendTransportConnector connector = new HttpBackendTransportConnector(code, "lobby-1",
					credential, envelope -> {
						backendReceived.countDown();
						try { releaseBackendCallback.await(5, TimeUnit.SECONDS); }
						catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
					})) {
				connector.start();
				assertTrue(connector.send(JsonEnvelope.builder("establish-poll").build()));
				assertTrue(proxyReceived.await(8, TimeUnit.SECONDS));
				assertTrue(server.send("lobby-1", JsonEnvelope.builder("durable-after-poll").build()));
				assertTrue(backendReceived.await(8, TimeUnit.SECONDS));
				assertEquals(1L, countRegularFiles(queueDirectory),
						"a poll-created backend state must persist before reporting acceptance");
				releaseBackendCallback.countDown();
			}
		} finally {
			releaseBackendCallback.countDown();
		}
	}

	private static long countRegularFiles(Path root) throws Exception {
		try (java.util.stream.Stream<Path> paths = java.nio.file.Files.walk(root)) {
			return paths.filter(path -> java.nio.file.Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)).count();
		}
	}

	@Test
	void aggregatePacketBudgetSplitsLargeValidEnvelopes() {
		java.util.List<HttpTransportProtocol.Delivery> candidates = new java.util.ArrayList<>();
		for (int index = 0; index < 12; index++) candidates.add(new HttpTransportProtocol.Delivery(
				java.util.UUID.randomUUID().toString(), JsonEnvelope.builder("large").put("value", "x".repeat(40_000)).build()));
		String session = java.util.UUID.randomUUID().toString();
		java.util.List<HttpTransportProtocol.Delivery> fitted = HttpTransportProtocol.fittingMessages(
				"lobby-1", session, 0, java.util.List.of(), java.util.List.of(), candidates);
		assertTrue(fitted.size() > 0 && fitted.size() < candidates.size());
		assertTrue(HttpTransportProtocol.request("lobby-1", session, 0, java.util.List.of(), java.util.List.of(), fitted).length
				<= HttpTransportProtocol.MAX_BODY_BYTES);
	}

	@Test
	void packetNumbersMustUseCanonicalJsonIntegerTokens() {
		com.google.gson.JsonObject packet = com.google.gson.JsonParser.parseString(new String(HttpTransportProtocol.request(
				"lobby-1", java.util.UUID.randomUUID().toString(), 0, java.util.List.of(), java.util.List.of(), java.util.List.of()),
				java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
		assertDoesNotThrow(() -> HttpTransportProtocol.parsePacket(packet.toString()
				.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		String timestamp = packet.get("timestamp").getAsString();
		java.util.Map<String, java.util.List<String>> invalid = java.util.Map.of(
				"v", java.util.List.of("\"1\"", "1.0", "1e0"),
				"sequence", java.util.List.of("\"0\"", "0.0", "0e0"),
				"timestamp", java.util.List.of("\"" + timestamp + "\"", timestamp + ".0", timestamp + "e0"));
		for (var field : invalid.entrySet()) for (String token : field.getValue()) {
			com.google.gson.JsonObject rejected = packet.deepCopy();
			rejected.add(field.getKey(), com.google.gson.JsonParser.parseString(token));
			assertThrows(IllegalArgumentException.class, () -> HttpTransportProtocol.parsePacket(
					rejected.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)), field.getKey() + "=" + token);
		}
	}

	@Test
	void packetParsingRejectsNoncanonicalUuidForms() {
		String deliveryId = java.util.UUID.randomUUID().toString();
		com.google.gson.JsonObject packet = com.google.gson.JsonParser.parseString(new String(HttpTransportProtocol.request(
				"lobby-1", java.util.UUID.randomUUID().toString(), 0, java.util.List.of(deliveryId),
				java.util.List.of(deliveryId),
				java.util.List.of(new HttpTransportProtocol.Delivery(deliveryId, JsonEnvelope.builder("payload").build()))),
				java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
		String abbreviated = "1-1-1-1-1";

		com.google.gson.JsonObject invalidSession = packet.deepCopy();
		invalidSession.addProperty("session", abbreviated);
		assertThrows(IllegalArgumentException.class, () -> HttpTransportProtocol.parsePacket(
				invalidSession.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));

		com.google.gson.JsonObject invalidAck = packet.deepCopy();
		invalidAck.getAsJsonArray("acks").set(0, new com.google.gson.JsonPrimitive(abbreviated));
		assertThrows(IllegalArgumentException.class, () -> HttpTransportProtocol.parsePacket(
				invalidAck.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		com.google.gson.JsonObject invalidConfirmation = packet.deepCopy();
		invalidConfirmation.getAsJsonArray("ackConfirmations").set(0, new com.google.gson.JsonPrimitive(abbreviated));
		assertThrows(IllegalArgumentException.class, () -> HttpTransportProtocol.parsePacket(
				invalidConfirmation.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));

		com.google.gson.JsonObject invalidMessage = packet.deepCopy();
		invalidMessage.getAsJsonArray("messages").get(0).getAsJsonObject().addProperty("id", abbreviated);
		assertThrows(IllegalArgumentException.class, () -> HttpTransportProtocol.parsePacket(
				invalidMessage.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
	}

	@Test
	void persistedProfileStartsAfterTheEnrollmentCodeExpires() throws Exception {
		HttpTlsIdentity identity = HttpTlsIdentity.loadOrCreate(directory.resolve("proxy"), "localhost");
		HttpEnrollmentAuthority authority = new HttpEnrollmentAuthority(identity, directory.resolve("authority"));
		try (HttpProxyTransportServer server = new HttpProxyTransportServer(new InetSocketAddress("localhost", 0), identity, authority, ignored -> { })) {
			server.start();
			HttpConnectionCode active = authority.createConnectionCode("lobby-1", server.endpoint("localhost"), Duration.ofMinutes(5));
			HttpBackendTransportConnector.enroll(active, "lobby-1", directory.resolve("client"));
			HttpConnectionCode expired = new HttpConnectionCode(active.serverId(), active.endpoint(), active.serverCertificatePin(), active.caCertificatePin(),
					java.time.Instant.now().minusSeconds(1), active.enrollmentToken());
			try (HttpBackendTransportConnector ignored = new HttpBackendTransportConnector(expired, "lobby-1", directory.resolve("client"), message -> { })) {
				assertTrue(true);
			}
			try (HttpBackendTransportConnector ignored = new HttpBackendTransportConnector(directory.resolve("client"), message -> { })) {
				assertTrue(true);
			}
		}
	}

	@Test
	void persistedBackendConnectsAfterAutomaticServerLeafRotation() throws Exception {
		Instant now = Instant.now();
		Path proxyDirectory = directory.resolve("proxy");
		HttpTlsIdentity original = HttpTlsIdentity.loadOrCreate(proxyDirectory, "localhost",
				java.time.Clock.fixed(now.minus(Duration.ofDays(340)), java.time.ZoneOffset.UTC));
		String originalServerPin = HttpTransportSecrets.certificatePin(original.serverCertificate());
		HttpEnrollmentAuthority authority = new HttpEnrollmentAuthority(original, directory.resolve("authority"));
		HttpTlsIdentity rotated = HttpTlsIdentity.loadOrCreate(proxyDirectory, "localhost");
		CountDownLatch received = new CountDownLatch(1);
		try (HttpProxyTransportServer server = new HttpProxyTransportServer(new InetSocketAddress("localhost", 0), rotated,
				authority, ignored -> received.countDown())) {
			HttpConnectionCode activeCode = authority.createConnectionCode("lobby-1", server.endpoint("localhost"), Duration.ofMinutes(5));
			HttpTlsIdentity.IssuedClientCertificate issued = authority.enroll("lobby-1", activeCode.enrollmentToken());
			HttpConnectionCode oldProfileCode = new HttpConnectionCode(activeCode.serverId(), activeCode.endpoint(), originalServerPin,
					activeCode.caCertificatePin(), activeCode.expiresAt(), activeCode.enrollmentToken());
			HttpClientCredentialStore.saveEnrolled(directory.resolve("client"), oldProfileCode, issued);
			assertFalse(oldProfileCode.serverCertificatePin().equals(rotated.serverCertificatePin()));
			server.start();
			try (HttpBackendTransportConnector connector = new HttpBackendTransportConnector(directory.resolve("client"), ignored -> { })) {
				connector.start();
				assertTrue(connector.send(JsonEnvelope.builder("after-rotation").build()));
				assertTrue(received.await(8, TimeUnit.SECONDS));
			}
		}
	}

	@Test
	void renewalRetainsOldCredentialUntilPublishedPointerIsConfirmed() throws Exception {
		HttpTlsIdentity identity = HttpTlsIdentity.loadOrCreate(directory.resolve("pointer-proxy"), "localhost");
		HttpTlsIdentity.IssuedClientCertificate expiring = identity.issueClientCertificate("lobby-1",
				Instant.now().minus(Duration.ofDays(340)));
		String originalPin = HttpTransportSecrets.certificatePin(expiring.certificate());
		Path authorityDirectory = Files.createDirectory(directory.resolve("pointer-authority"));
		String key = java.util.Base64.getUrlEncoder().withoutPadding()
				.encodeToString("lobby-1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
		Files.writeString(authorityDirectory.resolve("http-transport-clients.properties"),
				"version=2\nbinding." + key + "=" + originalPin + ":-:0\n");
		HttpEnrollmentAuthority authority = new HttpEnrollmentAuthority(identity, authorityDirectory);
		CountDownLatch received = new CountDownLatch(1);
		try (HttpProxyTransportServer server = new HttpProxyTransportServer(new InetSocketAddress("localhost", 0),
				identity, authority, ignored -> received.countDown())) {
			Path clientDirectory = directory.resolve("pointer-client");
			HttpConnectionCode code = new HttpConnectionCode("lobby-1", server.endpoint("localhost"),
					identity.serverCertificatePin(), identity.caCertificatePin(), Instant.now().plusSeconds(60), "A".repeat(43));
			HttpClientCredentialStore.saveEnrolled(clientDirectory, code, expiring);
			server.start();
			try (HttpBackendTransportConnector connector = new HttpBackendTransportConnector(clientDirectory, ignored -> { })) {
				var renew = HttpBackendTransportConnector.class.getDeclaredMethod("maybeRenewCredential");
				renew.setAccessible(true);
				var pending = HttpBackendTransportConnector.class.getDeclaredField("pendingActivation");
				pending.setAccessible(true);
				var credential = HttpBackendTransportConnector.class.getDeclaredField("credential");
				credential.setAccessible(true);
				Path pointer = clientDirectory.resolve("http-transport-client-current");
				String generation;
				try (var forces = org.mockito.Mockito.mockStatic(com.bencodez.simpleapi.file.DurableFiles.class,
						org.mockito.Mockito.CALLS_REAL_METHODS)) {
					AtomicLong clientDirectoryForces = new AtomicLong();
					forces.when(() -> com.bencodez.simpleapi.file.DurableFiles.forceDirectory(clientDirectory))
							.thenAnswer(call -> {
								// Staging first confirms the existing directory. Subsequent forces
								// are the CURRENT pointer publication and its same-generation retry.
								if (clientDirectoryForces.incrementAndGet() > 1L)
									throw new java.io.IOException("injected pointer force failure");
								return call.callRealMethod();
							});
					renew.invoke(connector);
					generation = Files.readString(pointer);
					var selected = HttpClientCredentialStore.load(clientDirectory);
					String selectedPin = HttpTransportSecrets.certificatePin(selected.certificate());
					assertFalse(originalPin.equals(selectedPin));
					assertEquals(originalPin, HttpTransportSecrets.certificatePin(
							((HttpClientCredentialStore.ClientCredential) credential.get(connector)).certificate()));
					assertTrue(pending.get(connector) != null);
					assertTrue(authority.authenticate("lobby-1", expiring.certificate()),
							"uncertain pointer publication must not invalidate the restart-safe old credential");
					renew.invoke(connector);
					assertEquals(generation, Files.readString(pointer));
					assertTrue(pending.get(connector) != null, "failed retry must retain the same pending activation");
				}
				renew.invoke(connector);
				assertTrue(pending.get(connector) == null);
				assertEquals(generation, Files.readString(pointer));
				var confirmed = HttpClientCredentialStore.load(clientDirectory);
				assertEquals(HttpTransportSecrets.certificatePin(confirmed.certificate()),
						HttpTransportSecrets.certificatePin(((HttpClientCredentialStore.ClientCredential)
								credential.get(connector)).certificate()));
				assertTrue(authority.authenticate("lobby-1", confirmed.certificate()));
				assertFalse(authority.authenticate("lobby-1", expiring.certificate()));
				connector.start();
				assertTrue(connector.send(JsonEnvelope.builder("after-pointer-recovery").build()));
				assertTrue(received.await(8, TimeUnit.SECONDS), "transport must use the adopted TLS client");
			}
		}
	}

	@Test
	void backendRenewsClientCertificateBeforeExpiryWithoutNewConnectionCode() throws Exception {
		HttpTlsIdentity identity = HttpTlsIdentity.loadOrCreate(directory.resolve("proxy"), "localhost");
		HttpTlsIdentity.IssuedClientCertificate expiring = identity.issueClientCertificate("lobby-1",
				Instant.now().minus(Duration.ofDays(340)));
		String originalPin = HttpTransportSecrets.certificatePin(expiring.certificate());
		Path authorityDirectory = directory.resolve("authority");
		java.nio.file.Files.createDirectories(authorityDirectory);
		String key = java.util.Base64.getUrlEncoder().withoutPadding()
				.encodeToString("lobby-1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
		java.nio.file.Files.writeString(authorityDirectory.resolve("http-transport-clients.properties"),
				"version=2\nbinding." + key + "=" + originalPin + ":-:0\n");
		HttpEnrollmentAuthority authority = new HttpEnrollmentAuthority(identity, authorityDirectory);
		try (HttpProxyTransportServer server = new HttpProxyTransportServer(new InetSocketAddress("localhost", 0), identity,
				authority, ignored -> { })) {
			HttpConnectionCode profileCode = new HttpConnectionCode("lobby-1", server.endpoint("localhost"),
					identity.serverCertificatePin(), identity.caCertificatePin(), Instant.now().plusSeconds(60), "A".repeat(43));
			Path clientDirectory = directory.resolve("client");
			HttpClientCredentialStore.saveEnrolled(clientDirectory, profileCode, expiring);
			server.start();
			try (HttpBackendTransportConnector connector = new HttpBackendTransportConnector(clientDirectory, ignored -> { })) {
				connector.start();
				long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
				String renewedPin = originalPin;
				while (renewedPin.equals(originalPin) && System.nanoTime() < deadline) {
					Thread.sleep(25);
					renewedPin = HttpTransportSecrets.certificatePin(HttpClientCredentialStore.load(clientDirectory).certificate());
				}
				assertFalse(renewedPin.equals(originalPin));
				HttpClientCredentialStore.ClientCredential renewed = HttpClientCredentialStore.load(clientDirectory);
				long promotionDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
				while (authority.authenticate("lobby-1", expiring.certificate()) && System.nanoTime() < promotionDeadline) Thread.sleep(25);
				assertTrue(authority.authenticate("lobby-1", renewed.certificate()));
				assertFalse(authority.authenticate("lobby-1", expiring.certificate()));
			}
		}
	}

	@Test
	void duplicateInboundDeliveryIsReAcknowledgedWithoutSecondDispatch() {
		HttpProxyTransportServer.BackendState state = new HttpProxyTransportServer.BackendState();
		String session = java.util.UUID.randomUUID().toString();
		String id = java.util.UUID.randomUUID().toString();
		HttpTransportProtocol.Delivery delivery = new HttpTransportProtocol.Delivery(id, JsonEnvelope.builder("x").build());
		assertTrue(state.acceptSession(session, 0));
		assertEquals(1, state.acceptIncoming(java.util.List.of(delivery)).size());
		state.completeIncoming(id, true);
		assertEquals(java.util.List.of(id), state.await("lobby-1", session, 0).acks());
		assertTrue(state.acceptSession(session, 1));
		assertTrue(state.acceptIncoming(java.util.List.of(delivery)).isEmpty());
		assertEquals(java.util.List.of(id), state.await("lobby-1", session, 1).acks());
		String replacementSession = java.util.UUID.randomUUID().toString();
		assertTrue(state.acceptSession(replacementSession, 0));
		assertTrue(state.acceptIncoming(java.util.List.of(delivery)).isEmpty());
		assertEquals(java.util.List.of(id), state.await("lobby-1", replacementSession, 0).acks());
	}

	@Test
	void newerDeliveriesDoNotPostponeRetryOfOlderUnacknowledgedDelivery() {
		AtomicLong nanoTime = new AtomicLong(1L);
		HttpProxyTransportServer.BackendState state = new HttpProxyTransportServer.BackendState(nanoTime::get);
		String session = java.util.UUID.randomUUID().toString();
		HttpTransportProtocol.Delivery first = new HttpTransportProtocol.Delivery(
				java.util.UUID.randomUUID().toString(), JsonEnvelope.builder("first").build());
		HttpTransportProtocol.Delivery second = new HttpTransportProtocol.Delivery(
				java.util.UUID.randomUUID().toString(), JsonEnvelope.builder("second").build());

		assertTrue(state.acceptSession(session, 0));
		assertTrue(state.enqueue(first));
		assertEquals(java.util.List.of(first), state.await("lobby-1", session, 0).messages());

		nanoTime.addAndGet(TimeUnit.SECONDS.toNanos(1));
		assertTrue(state.acceptSession(session, 1));
		assertTrue(state.enqueue(second));
		assertEquals(java.util.List.of(second), state.await("lobby-1", session, 1).messages());

		nanoTime.addAndGet(TimeUnit.MILLISECONDS.toNanos(1100));
		assertTrue(state.acceptSession(session, 2));
		assertEquals(java.util.List.of(first), state.await("lobby-1", session, 2).messages(),
				"sending newer traffic must not reset an older delivery's retry age");
	}

	@Test
	void longPollWakesAtTheOldestDeliveryRetryDeadline() throws Exception {
		AtomicLong nanoTime = new AtomicLong(1L);
		HttpProxyTransportServer.BackendState state = new HttpProxyTransportServer.BackendState(nanoTime::get);
		String session = java.util.UUID.randomUUID().toString();
		HttpTransportProtocol.Delivery first = new HttpTransportProtocol.Delivery(
				java.util.UUID.randomUUID().toString(), JsonEnvelope.builder("first").build());
		HttpTransportProtocol.Delivery second = new HttpTransportProtocol.Delivery(
				java.util.UUID.randomUUID().toString(), JsonEnvelope.builder("second").build());
		assertTrue(state.acceptSession(session, 0));
		assertTrue(state.enqueue(first));
		assertEquals(java.util.List.of(first), state.await("lobby-1", session, 0).messages());

		nanoTime.addAndGet(HttpProxyTransportServer.LONG_POLL.minusMillis(100).toNanos());
		assertTrue(state.acceptSession(session, 1));
		assertTrue(state.enqueue(second));
		assertEquals(java.util.List.of(second), state.await("lobby-1", session, 1).messages());
		assertTrue(state.acceptSession(session, 2));
		Thread clock = new Thread(() -> {
			try { Thread.sleep(50L); }
			catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
			nanoTime.addAndGet(TimeUnit.MILLISECONDS.toNanos(100));
		}, "HTTP-retry-test-clock");
		clock.setDaemon(true);
		long started = System.nanoTime();
		clock.start();
		assertEquals(java.util.List.of(first), state.await("lobby-1", session, 2).messages());
		clock.join();
		assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(1),
				"the poll must wake at the oldest delivery deadline, not a fresh long-poll deadline");
	}

	@Test
	void proxyDedupWindowEvictsOldestCompletedDeliveryAtCapacity() {
		HttpProxyTransportServer.BackendState state = new HttpProxyTransportServer.BackendState();
		String oldest = null, newest = null;
		for (int index = 0; index < HttpTransportProtocol.MAX_QUEUE + 1; index++) {
			String id = java.util.UUID.randomUUID().toString();
			if (index == 0) oldest = id;
			newest = id;
			HttpTransportProtocol.Delivery delivery = new HttpTransportProtocol.Delivery(id, JsonEnvelope.builder("x").build());
			assertEquals(1, state.acceptIncoming(java.util.List.of(delivery)).size());
			state.completeIncoming(id, true);
		}
		HttpTransportProtocol.Delivery evicted = new HttpTransportProtocol.Delivery(oldest, JsonEnvelope.builder("x").build());
		HttpTransportProtocol.Delivery retained = new HttpTransportProtocol.Delivery(newest, JsonEnvelope.builder("x").build());
		assertEquals(1, state.acceptIncoming(java.util.List.of(evicted)).size());
		assertTrue(state.acceptIncoming(java.util.List.of(retained)).isEmpty());
	}

	@Test
	void backendReAcknowledgesLostAckDuplicateWithoutSecondCallback() throws Exception {
		HttpTlsIdentity identity = HttpTlsIdentity.loadOrCreate(directory.resolve("proxy"), "localhost");
		HttpTlsIdentity.IssuedClientCertificate issued = identity.issueClientCertificate("lobby-1");
		HttpClientCredentialStore.save(directory.resolve("client"), issued);
		HttpClientCredentialStore.HttpClientProfile profile = new HttpClientCredentialStore.HttpClientProfile("lobby-1",
				java.net.URI.create("https://localhost:8443/"), identity.serverCertificatePin(), identity.caCertificatePin());
		CountDownLatch callback = new CountDownLatch(1);
		try (HttpBackendTransportConnector connector = new HttpBackendTransportConnector(profile,
				HttpClientCredentialStore.load(directory.resolve("client")), envelope -> callback.countDown())) {
			String id = java.util.UUID.randomUUID().toString();
			HttpTransportProtocol.Delivery delivery = new HttpTransportProtocol.Delivery(id, JsonEnvelope.builder("x").build());
			connector.dispatch(delivery);
			assertTrue(callback.await(2, TimeUnit.SECONDS));
			java.util.List<String> acknowledgements = java.util.List.of();
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
			while (acknowledgements.isEmpty() && System.nanoTime() < deadline) {
				acknowledgements = connector.drainAcknowledgements();
				if (acknowledgements.isEmpty()) Thread.sleep(5);
			}
			assertEquals(java.util.List.of(id), acknowledgements);
			assertTrue(connector.accept(java.util.List.of(delivery)).isEmpty());
			assertEquals(java.util.List.of(id), connector.drainAcknowledgements());
		}
	}

	@Test
	void backendCallbacksAreSerializedInDeliveryOrder() throws Exception {
		HttpTlsIdentity identity = HttpTlsIdentity.loadOrCreate(directory.resolve("ordered-proxy"), "localhost");
		HttpTlsIdentity.IssuedClientCertificate issued = identity.issueClientCertificate("lobby-1");
		HttpClientCredentialStore.save(directory.resolve("ordered-client"), issued);
		HttpClientCredentialStore.HttpClientProfile profile = new HttpClientCredentialStore.HttpClientProfile("lobby-1",
				java.net.URI.create("https://localhost:8443/"), identity.serverCertificatePin(), identity.caCertificatePin());
		CountDownLatch firstStarted = new CountDownLatch(1), releaseFirst = new CountDownLatch(1), secondStarted = new CountDownLatch(1);
		java.util.List<String> order = new java.util.concurrent.CopyOnWriteArrayList<>();
		try (HttpBackendTransportConnector connector = new HttpBackendTransportConnector(profile,
				HttpClientCredentialStore.load(directory.resolve("ordered-client")), envelope -> {
					String marker = String.valueOf(envelope.getFields().get("marker"));
					order.add(marker);
					if ("first".equals(marker)) {
						firstStarted.countDown();
						try { releaseFirst.await(5, TimeUnit.SECONDS); }
						catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
					} else secondStarted.countDown();
				})) {
			connector.dispatch(new HttpTransportProtocol.Delivery(java.util.UUID.randomUUID().toString(),
					JsonEnvelope.builder("x").put("marker", "first").build()));
			connector.dispatch(new HttpTransportProtocol.Delivery(java.util.UUID.randomUUID().toString(),
					JsonEnvelope.builder("x").put("marker", "second").build()));
			assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
			assertFalse(secondStarted.await(150, TimeUnit.MILLISECONDS));
			releaseFirst.countDown();
			assertTrue(secondStarted.await(2, TimeUnit.SECONDS));
			assertEquals(java.util.List.of("first", "second"), order);
		} finally { releaseFirst.countDown(); }
	}

	@Test
	void backendCallbackQueueBackpressuresWithoutBreakingFifo() throws Exception {
		HttpTlsIdentity identity = HttpTlsIdentity.loadOrCreate(directory.resolve("backpressure-proxy"), "localhost");
		HttpTlsIdentity.IssuedClientCertificate issued = identity.issueClientCertificate("lobby-1");
		HttpClientCredentialStore.save(directory.resolve("backpressure-client"), issued);
		HttpClientCredentialStore.HttpClientProfile profile = new HttpClientCredentialStore.HttpClientProfile("lobby-1",
				java.net.URI.create("https://localhost:8443/"), identity.serverCertificatePin(), identity.caCertificatePin());
		CountDownLatch firstStarted = new CountDownLatch(1), releaseFirst = new CountDownLatch(1);
		int deliveries = HttpBackendTransportConnector.CALLBACK_QUEUE_CAPACITY + 2;
		CountDownLatch completed = new CountDownLatch(deliveries), overflowSubmitted = new CountDownLatch(1);
		java.util.List<Integer> order = new java.util.concurrent.CopyOnWriteArrayList<>();
		try (HttpBackendTransportConnector connector = new HttpBackendTransportConnector(profile,
				HttpClientCredentialStore.load(directory.resolve("backpressure-client")), envelope -> {
			int marker = Integer.parseInt(envelope.getFields().get("marker"));
			order.add(marker);
			if (marker == 0) {
				firstStarted.countDown();
				try { releaseFirst.await(5, TimeUnit.SECONDS); }
				catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
			}
			completed.countDown();
		})) {
			connector.dispatch(delivery(0));
			assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
			for (int marker = 1; marker <= HttpBackendTransportConnector.CALLBACK_QUEUE_CAPACITY; marker++)
				connector.dispatch(delivery(marker));
			Thread overflow = new Thread(() -> {
				connector.dispatch(delivery(deliveries - 1));
				overflowSubmitted.countDown();
			}, "HTTP-overflow-submitter");
			overflow.start();
			assertFalse(overflowSubmitted.await(150, TimeUnit.MILLISECONDS), "a full ordered lane must backpressure its producer");
			releaseFirst.countDown();
			assertTrue(overflowSubmitted.await(2, TimeUnit.SECONDS));
			assertTrue(completed.await(5, TimeUnit.SECONDS));
			assertEquals(java.util.stream.IntStream.range(0, deliveries).boxed().toList(), order);
		} finally { releaseFirst.countDown(); }
	}

	@Test
	void durableBackendFencePreventsCallbackReplayAfterRestartBeforeAck() throws Exception {
		HttpTlsIdentity identity = HttpTlsIdentity.loadOrCreate(directory.resolve("fence-proxy"), "localhost");
		HttpTlsIdentity.IssuedClientCertificate issued = identity.issueClientCertificate("lobby-1");
		HttpConnectionCode code = new HttpConnectionCode("lobby-1", java.net.URI.create("https://localhost:8443/"),
				identity.serverCertificatePin(), identity.caCertificatePin(), Instant.now().plusSeconds(300), "A".repeat(43));
		Path clientDirectory = directory.resolve("fence-client");
		HttpClientCredentialStore.saveEnrolled(clientDirectory, code, issued);
		java.util.concurrent.atomic.AtomicInteger callbacks = new java.util.concurrent.atomic.AtomicInteger();
		String id = java.util.UUID.randomUUID().toString();
		HttpTransportProtocol.Delivery delivery = new HttpTransportProtocol.Delivery(id, JsonEnvelope.builder("vote").build());
		try (HttpBackendTransportConnector first = new HttpBackendTransportConnector(clientDirectory,
				ignored -> callbacks.incrementAndGet())) {
			first.dispatch(delivery);
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
			while (callbacks.get() != 1 && System.nanoTime() < deadline) Thread.sleep(5);
			assertEquals(1, callbacks.get());
		}
		try (HttpBackendTransportConnector restarted = new HttpBackendTransportConnector(clientDirectory,
				ignored -> callbacks.incrementAndGet())) {
			assertEquals(java.util.List.of(id), restarted.drainAcknowledgements(),
					"restart must retain and acknowledge the pre-callback delivery fence");
			assertTrue(restarted.accept(java.util.List.of(delivery)).isEmpty());
			assertEquals(1, callbacks.get(), "a durable proxy replay must not award twice");
		}
	}

	@Test
	void failedBackendCallbackRemainsUnacknowledgedAndIsNotReplayed() throws Exception {
		HttpTlsIdentity identity = HttpTlsIdentity.loadOrCreate(directory.resolve("retry-fence-proxy"), "localhost");
		HttpTlsIdentity.IssuedClientCertificate issued = identity.issueClientCertificate("lobby-1");
		HttpConnectionCode code = new HttpConnectionCode("lobby-1", java.net.URI.create("https://localhost:8443/"),
				identity.serverCertificatePin(), identity.caCertificatePin(), Instant.now().plusSeconds(300), "B".repeat(43));
		Path clientDirectory = directory.resolve("retry-fence-client");
		HttpClientCredentialStore.saveEnrolled(clientDirectory, code, issued);
		java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
		CountDownLatch failed = new CountDownLatch(1);
		HttpTransportProtocol.Delivery delivery = new HttpTransportProtocol.Delivery(java.util.UUID.randomUUID().toString(),
				JsonEnvelope.builder("vote").build());
		try (HttpBackendTransportConnector first = new HttpBackendTransportConnector(clientDirectory, ignored -> {
			attempts.incrementAndGet(); failed.countDown(); throw new IllegalStateException("retry");
		})) {
			first.dispatch(delivery);
			assertTrue(failed.await(2, TimeUnit.SECONDS));
			assertTrue(first.drainAcknowledgements().isEmpty());
		}
		try (HttpBackendTransportConnector restarted = new HttpBackendTransportConnector(clientDirectory,
				ignored -> attempts.incrementAndGet())) {
			assertTrue(restarted.drainAcknowledgements().isEmpty());
			java.util.List<HttpTransportProtocol.Delivery> accepted = restarted.accept(java.util.List.of(delivery));
			assertTrue(accepted.isEmpty(), "an ambiguous callback must not be awarded twice");
			assertEquals(1, attempts.get());
		}
	}

	@Test
	void reservedButNotStartedDeliveryResumesAfterRestart() throws Exception {
		HttpTlsIdentity identity = HttpTlsIdentity.loadOrCreate(directory.resolve("reserved-proxy"), "localhost");
		HttpTlsIdentity.IssuedClientCertificate issued = identity.issueClientCertificate("lobby-1");
		HttpConnectionCode code = new HttpConnectionCode("lobby-1", java.net.URI.create("https://localhost:8443/"),
				identity.serverCertificatePin(), identity.caCertificatePin(), Instant.now().plusSeconds(300), "C".repeat(43));
		Path clientDirectory = directory.resolve("reserved-client");
		HttpClientCredentialStore.saveEnrolled(clientDirectory, code, issued);
		String id = java.util.UUID.randomUUID().toString();
		HttpInboundDeliveryStore reservedStore = new HttpInboundDeliveryStore(clientDirectory);
		reservedStore.reserve(id);
		reservedStore.seal();
		CountDownLatch completed = new CountDownLatch(1);
		HttpTransportProtocol.Delivery delivery = new HttpTransportProtocol.Delivery(id, JsonEnvelope.builder("vote").build());
		try (HttpBackendTransportConnector restarted = new HttpBackendTransportConnector(clientDirectory, ignored -> completed.countDown())) {
			assertTrue(restarted.drainAcknowledgements().isEmpty(), "a reservation alone must never be acknowledged");
			java.util.List<HttpTransportProtocol.Delivery> accepted = restarted.accept(java.util.List.of(delivery));
			assertEquals(1, accepted.size());
			restarted.dispatch(accepted.get(0));
			assertTrue(completed.await(2, TimeUnit.SECONDS));
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
			java.util.List<String> acknowledgements = java.util.List.of();
			while (acknowledgements.isEmpty() && System.nanoTime() < deadline) {
				acknowledgements = restarted.drainAcknowledgements();
				if (acknowledgements.isEmpty()) Thread.sleep(5);
			}
			assertEquals(java.util.List.of(id), acknowledgements);
		}
	}

	@Test
	void interruptedStateRenameRetainsTheFurthestSafeState() throws Exception {
		Path clientDirectory = directory.resolve("interrupted-state-client");
		String id = java.util.UUID.randomUUID().toString();
		String completedId = java.util.UUID.randomUUID().toString();
		Path states = clientDirectory.resolve("http-transport-inbound-deliveries");
		Files.createDirectories(states);
		Files.writeString(states.resolve(id + ".reserved"), id);
		Files.writeString(states.resolve(id + ".running"), id);
		Files.writeString(states.resolve(completedId + ".running"), completedId);
		Files.writeString(states.resolve(completedId + ".completed"), completedId);
		HttpInboundDeliveryStore store = new HttpInboundDeliveryStore(clientDirectory);
		assertEquals(HttpInboundDeliveryStore.State.RUNNING, store.state(id));
		assertEquals(HttpInboundDeliveryStore.State.COMPLETED, store.state(completedId));
		assertFalse(Files.exists(states.resolve(id + ".reserved")));
		assertTrue(Files.exists(states.resolve(id + ".running")));
		assertFalse(Files.exists(states.resolve(completedId + ".running")));
		assertTrue(Files.exists(states.resolve(completedId + ".completed")));
	}

	private static HttpTransportProtocol.Delivery delivery(int marker) {
		return new HttpTransportProtocol.Delivery(java.util.UUID.randomUUID().toString(),
				JsonEnvelope.builder("x").put("marker", marker).build());
	}

	@Test
	void proxyCallbacksAreSerializedInDeliveryOrder() throws Exception {
		HttpTlsIdentity identity = HttpTlsIdentity.loadOrCreate(directory.resolve("ordered-proxy-server"), "localhost");
		HttpEnrollmentAuthority authority = new HttpEnrollmentAuthority(identity, directory.resolve("ordered-authority"));
		CountDownLatch firstStarted = new CountDownLatch(1), releaseFirst = new CountDownLatch(1), secondStarted = new CountDownLatch(1);
		java.util.List<String> order = new java.util.concurrent.CopyOnWriteArrayList<>();
		try (HttpProxyTransportServer server = new HttpProxyTransportServer(new InetSocketAddress("localhost", 0), identity,
				authority, received -> {
					String marker = String.valueOf(received.envelope().getFields().get("marker"));
					order.add(marker);
					if ("first".equals(marker)) {
						firstStarted.countDown();
						try { releaseFirst.await(5, TimeUnit.SECONDS); }
						catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
					} else secondStarted.countDown();
				})) {
			server.start();
			HttpConnectionCode code = authority.createConnectionCode("lobby-1", server.endpoint("localhost"), Duration.ofMinutes(5));
			HttpClientCredentialStore.ClientCredential credential = HttpBackendTransportConnector.enroll(code, "lobby-1",
					directory.resolve("ordered-proxy-client"));
			try (HttpBackendTransportConnector connector = new HttpBackendTransportConnector(code, "lobby-1", credential,
					ignored -> { })) {
				connector.start();
				assertTrue(connector.send(JsonEnvelope.builder("x").put("marker", "first").build()));
				assertTrue(connector.send(JsonEnvelope.builder("x").put("marker", "second").build()));
				assertTrue(firstStarted.await(3, TimeUnit.SECONDS));
				assertFalse(secondStarted.await(150, TimeUnit.MILLISECONDS));
				releaseFirst.countDown();
				assertTrue(secondStarted.await(3, TimeUnit.SECONDS));
				assertEquals(java.util.List.of("first", "second"), order);
			}
		} finally { releaseFirst.countDown(); }
	}

	@Test
	void backendDedupWindowContinuesAfterCapacity() throws Exception {
		HttpTlsIdentity identity = HttpTlsIdentity.loadOrCreate(directory.resolve("proxy"), "localhost");
		HttpTlsIdentity.IssuedClientCertificate issued = identity.issueClientCertificate("lobby-1");
		HttpClientCredentialStore.save(directory.resolve("client"), issued);
		HttpClientCredentialStore.HttpClientProfile profile = new HttpClientCredentialStore.HttpClientProfile("lobby-1",
				java.net.URI.create("https://localhost:8443/"), identity.serverCertificatePin(), identity.caCertificatePin());
		try (HttpBackendTransportConnector connector = new HttpBackendTransportConnector(profile,
				HttpClientCredentialStore.load(directory.resolve("client")), envelope -> { })) {
			for (int index = 0; index < HttpTransportProtocol.MAX_QUEUE; index++) {
				String id = java.util.UUID.randomUUID().toString();
				HttpTransportProtocol.Delivery delivery = new HttpTransportProtocol.Delivery(id, JsonEnvelope.builder("x").build());
				assertEquals(1, connector.accept(java.util.List.of(delivery)).size());
				connector.completeIncoming(id, true);
			}
			HttpTransportProtocol.Delivery next = new HttpTransportProtocol.Delivery(java.util.UUID.randomUUID().toString(),
					JsonEnvelope.builder("x").build());
			assertEquals(1, connector.accept(java.util.List.of(next)).size());
		}
	}
}
