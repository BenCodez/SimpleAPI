package com.bencodez.simpleapi.servercomm.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bencodez.simpleapi.servercomm.codec.JsonEnvelope;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HttpBackendLifecycleRecoveryTest {
	@TempDir Path directory;

	@Test
	void callbackOwnedCloseCompletesItsJournalBeforeAsynchronousSealing() throws Exception {
		HttpTlsIdentity identity = HttpTlsIdentity.loadOrCreate(directory.resolve("proxy"), "localhost");
		HttpTlsIdentity.IssuedClientCertificate issued = identity.issueClientCertificate("lobby-1");
		HttpConnectionCode code = new HttpConnectionCode("lobby-1", URI.create("https://localhost:8443/"),
				identity.serverCertificatePin(), identity.caCertificatePin(), Instant.now().plusSeconds(300), "A".repeat(43));
		Path clientDirectory = directory.resolve("client");
		HttpClientCredentialStore.saveEnrolled(clientDirectory, code, issued);
		AtomicReference<HttpBackendTransportConnector> connector = new AtomicReference<>();
		CountDownLatch callbackReturned = new CountDownLatch(1);
		connector.set(new HttpBackendTransportConnector(clientDirectory, ignored -> {
			connector.get().close();
			callbackReturned.countDown();
		}));
		String id = UUID.randomUUID().toString();
		connector.get().dispatch(new HttpTransportProtocol.Delivery(id, JsonEnvelope.builder("self-close").build()));
		assertTrue(callbackReturned.await(2, TimeUnit.SECONDS), "callback-owned close must not drain its own worker");
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
		HttpInboundDeliveryStore.State state = null;
		while (System.nanoTime() < deadline) {
			state = HttpInboundDeliveryStore.inspect(clientDirectory).state(id);
			if (state == HttpInboundDeliveryStore.State.COMPLETED) break;
			Thread.sleep(5);
		}
		assertEquals(HttpInboundDeliveryStore.State.COMPLETED, state,
				"the callback wrapper must mark completed before close seals the journal");
		connector.get().start();
		assertFalse(connector.get().pollerAlive(), "a closed connector must not restart its poller");
		connector.get().close();
	}
}
