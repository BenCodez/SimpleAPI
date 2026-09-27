package com.bencodez.simpleapi.servercomm.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bencodez.simpleapi.servercomm.codec.JsonEnvelope;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HttpEnvelopeWireCodecTest {
	@TempDir Path directory;

	@Test
	void serializedWrappersCoordinateConcurrentAccessToOneCodec() throws Exception {
		CountDownLatch firstEntered = new CountDownLatch(1);
		CountDownLatch releaseFirst = new CountDownLatch(1);
		AtomicInteger active = new AtomicInteger();
		AtomicInteger maximumActive = new AtomicInteger();
		HttpEnvelopeWireCodec delegate = new HttpEnvelopeWireCodec() {
			private JsonEnvelope apply(JsonEnvelope envelope) {
				int current = active.incrementAndGet();
				maximumActive.accumulateAndGet(current, Math::max);
				try {
					if (current == 1) {
						firstEntered.countDown();
						assertTrue(releaseFirst.await(5, TimeUnit.SECONDS));
					}
					return envelope;
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException(interrupted);
				} finally {
					active.decrementAndGet();
				}
			}

			@Override public JsonEnvelope encode(JsonEnvelope envelope) { return apply(envelope); }
			@Override public JsonEnvelope decode(JsonEnvelope envelope) { return apply(envelope); }
		};
		HttpEnvelopeWireCodec first = HttpEnvelopeWireCodec.serialized(delegate);
		HttpEnvelopeWireCodec second = HttpEnvelopeWireCodec.serialized(delegate);
		JsonEnvelope envelope = JsonEnvelope.builder("test").build();
		var executor = Executors.newFixedThreadPool(2);
		try {
			var firstCall = executor.submit(() -> first.encode(envelope));
			assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
			CountDownLatch secondAttempted = new CountDownLatch(1);
			var secondCall = executor.submit(() -> {
				secondAttempted.countDown();
				return second.decode(envelope);
			});
			assertTrue(secondAttempted.await(5, TimeUnit.SECONDS));
			assertThrows(java.util.concurrent.TimeoutException.class,
					() -> secondCall.get(100, TimeUnit.MILLISECONDS));
			assertEquals(1, maximumActive.get());
			releaseFirst.countDown();
			assertEquals(envelope, firstCall.get(5, TimeUnit.SECONDS));
			assertEquals(envelope, secondCall.get(5, TimeUnit.SECONDS));
			assertEquals(1, maximumActive.get());
		} finally {
			releaseFirst.countDown();
			executor.shutdownNow();
		}
	}

	@Test
	void durableProxyQueueKeepsSemanticEnvelopeAcrossCodecChange() throws Exception {
		Path outgoing = directory.resolve("outgoing");
		HttpTlsIdentity identity = HttpTlsIdentity.loadOrCreate(directory.resolve("proxy"), "localhost");
		HttpEnrollmentAuthority authority = new HttpEnrollmentAuthority(identity, directory.resolve("authority"));
		String deliveryId = UUID.randomUUID().toString();
		JsonEnvelope semantic = JsonEnvelope.builder("vote").put("player", "Example").build();

		try (HttpProxyTransportServer first = server(identity, authority, outgoing, codec("old"), ignored -> { })) {
			assertTrue(first.send("backend-a", deliveryId, semantic));
		}

		Path persisted;
		try (var files = Files.walk(outgoing)) {
			persisted = files.filter(Files::isRegularFile).findFirst().orElseThrow();
		}
		HttpTransportProtocol.Delivery stored = HttpTransportProtocol.parseStoredDelivery(Files.readAllBytes(persisted));
		assertEquals("vote", stored.envelope().getSubChannel());
		assertEquals("Example", stored.envelope().getFields().get("player"));
		assertFalse(stored.envelope().getFields().containsKey("wire-key"));

		HttpEnvelopeWireCodec replacement = codec("new");
		try (HttpProxyTransportServer restarted = server(identity, authority, outgoing, replacement, ignored -> { })) {
			HttpProxyTransportServer.Response response = restarted.backendStateForTest("backend-a")
					.await("backend-a", UUID.randomUUID().toString(), 1L, java.util.List.of(), replacement);
			assertEquals(1, response.messages().size());
			JsonEnvelope wire = response.messages().iterator().next().envelope();
			assertEquals("new", wire.getFields().get("wire-key"));
			assertEquals("Example", replacement.decode(wire).getFields().get("player"));
		}
	}

	@Test
	void codecAppliesAtBothAuthenticatedHttpWireBoundaries() throws Exception {
		HttpTlsIdentity identity = HttpTlsIdentity.loadOrCreate(directory.resolve("integration-proxy"), "localhost");
		HttpEnrollmentAuthority authority = new HttpEnrollmentAuthority(identity, directory.resolve("integration-authority"));
		HttpEnvelopeWireCodec codec = codec("shared");
		CountDownLatch proxyReceived = new CountDownLatch(1), backendReceived = new CountDownLatch(1);
		AtomicReference<JsonEnvelope> proxyEnvelope = new AtomicReference<>(), backendEnvelope = new AtomicReference<>();
		try (HttpProxyTransportServer server = server(identity, authority, directory.resolve("integration-outgoing"), codec,
				received -> { proxyEnvelope.set(received.envelope()); proxyReceived.countDown(); })) {
			server.start();
			HttpConnectionCode code = authority.createConnectionCode("backend-a", server.endpoint("localhost"),
					Duration.ofMinutes(5));
			Path credentials = directory.resolve("integration-client");
			HttpBackendTransportConnector.enroll(code, "backend-a", credentials);
			try (HttpBackendTransportConnector connector = new HttpBackendTransportConnector(credentials,
					envelope -> { backendEnvelope.set(envelope); backendReceived.countDown(); }, codec)) {
				connector.start();
				assertTrue(connector.awaitFirstResponse(System.nanoTime() + TimeUnit.SECONDS.toNanos(8)));
				assertTrue(connector.send(JsonEnvelope.builder("backend-to-proxy").put("value", "one").build()));
				assertTrue(proxyReceived.await(8, TimeUnit.SECONDS));
				assertEquals("one", proxyEnvelope.get().getFields().get("value"));
				assertFalse(proxyEnvelope.get().getFields().containsKey("wire-key"));

				assertTrue(server.send("backend-a", JsonEnvelope.builder("proxy-to-backend").put("value", "two").build()));
				assertTrue(backendReceived.await(8, TimeUnit.SECONDS));
				assertEquals("two", backendEnvelope.get().getFields().get("value"));
				assertFalse(backendEnvelope.get().getFields().containsKey("wire-key"));
			}
		}
	}

	@Test
	void wireExpansionIsRejectedBeforeItCanBlockAQueue() throws Exception {
		Path outgoing = directory.resolve("bounded-outgoing");
		HttpTlsIdentity identity = HttpTlsIdentity.loadOrCreate(directory.resolve("bounded-proxy"), "localhost");
		HttpEnrollmentAuthority authority = new HttpEnrollmentAuthority(identity, directory.resolve("bounded-authority"));
		JsonEnvelope large = JsonEnvelope.builder("large").put("value", "x".repeat(46_000)).build();
		HttpEnvelopeWireCodec expanding = expandingCodec();

		try (HttpProxyTransportServer server = server(identity, authority, outgoing, expanding, ignored -> { })) {
			assertFalse(server.send("backend-a", UUID.randomUUID().toString(), large));
			assertTrue(server.send("backend-a", UUID.randomUUID().toString(),
					JsonEnvelope.builder("small").build()));
		}

		Path restartOutgoing = directory.resolve("restart-bounded-outgoing");
		try (HttpProxyTransportServer first = server(identity, authority, restartOutgoing,
				HttpEnvelopeWireCodec.identity(), ignored -> { })) {
			assertTrue(first.send("backend-a", UUID.randomUUID().toString(), large));
		}
		assertThrows(IllegalArgumentException.class,
				() -> server(identity, authority, restartOutgoing, expanding, ignored -> { }));
		try (HttpProxyTransportServer recovered = server(identity, authority, restartOutgoing,
				HttpEnvelopeWireCodec.identity(), ignored -> { })) {
			assertTrue(recovered.hasPendingDeliveries());
		}

		try (HttpProxyTransportServer server = server(identity, authority, directory.resolve("backend-bounded-outgoing"),
				HttpEnvelopeWireCodec.identity(), ignored -> { })) {
			server.start();
			HttpConnectionCode code = authority.createConnectionCode("backend-b", server.endpoint("localhost"),
					Duration.ofMinutes(5));
			Path credentials = directory.resolve("backend-bounded-client");
			HttpBackendTransportConnector.enroll(code, "backend-b", credentials);
			try (HttpBackendTransportConnector connector = new HttpBackendTransportConnector(credentials,
					ignored -> { }, expanding)) {
				connector.start();
				assertTrue(connector.awaitFirstResponse(System.nanoTime() + TimeUnit.SECONDS.toNanos(8)));
				assertFalse(connector.send(large));
				assertTrue(connector.send(JsonEnvelope.builder("small").build()));
			}
		}
	}

	private static HttpProxyTransportServer server(HttpTlsIdentity identity, HttpEnrollmentAuthority authority,
			Path outgoing, HttpEnvelopeWireCodec codec,
			java.util.function.Consumer<HttpProxyTransportServer.ReceivedEnvelope> consumer) throws Exception {
		return new HttpProxyTransportServer(new InetSocketAddress("localhost", 0), identity, authority, outgoing,
				consumer, (serverId, deliveryId) -> { }, codec);
	}

	private static HttpEnvelopeWireCodec codec(String key) {
		return new HttpEnvelopeWireCodec() {
			@Override public JsonEnvelope encode(JsonEnvelope envelope) {
				return envelope.toBuilder().put("wire-key", key).build();
			}

			@Override public JsonEnvelope decode(JsonEnvelope envelope) {
				if (!key.equals(envelope.getFields().get("wire-key")))
					throw new IllegalArgumentException("wire key mismatch");
				LinkedHashMap<String, String> fields = new LinkedHashMap<>(envelope.getFields());
				fields.remove("wire-key");
				return new JsonEnvelope(envelope.getSubChannel(), envelope.getSchema(), fields);
			}
		};
	}

	private static HttpEnvelopeWireCodec expandingCodec() {
		return new HttpEnvelopeWireCodec() {
			@Override public JsonEnvelope encode(JsonEnvelope envelope) {
				return envelope.toBuilder().put("overhead", "y".repeat(4_000)).build();
			}

			@Override public JsonEnvelope decode(JsonEnvelope envelope) { return envelope; }
		};
	}
}
