package com.bencodez.simpleapi.servercomm.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HttpTlsIdentityOwnershipTest {
	@TempDir Path directory;

	@Test
	void concurrentFirstOpenPublishesOneReloadableIdentity() throws Exception {
		Path identityDirectory = directory.resolve("identity");
		CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
		ExecutorService workers = Executors.newFixedThreadPool(2);
		try {
			Future<HttpTlsIdentity> first = workers.submit(() -> openTogether(identityDirectory, ready, start));
			Future<HttpTlsIdentity> second = workers.submit(() -> openTogether(identityDirectory, ready, start));
			assertTrue(ready.await(2, TimeUnit.SECONDS));
			start.countDown();
			HttpTlsIdentity firstIdentity = first.get(10, TimeUnit.SECONDS);
			HttpTlsIdentity secondIdentity = second.get(10, TimeUnit.SECONDS);
			assertEquals(firstIdentity.caCertificatePin(), secondIdentity.caCertificatePin());
			assertEquals(firstIdentity.serverCertificatePin(), secondIdentity.serverCertificatePin());
			HttpTlsIdentity reloaded = HttpTlsIdentity.loadOrCreate(identityDirectory, "localhost");
			assertEquals(firstIdentity.caCertificatePin(), reloaded.caCertificatePin());
			assertEquals(firstIdentity.serverCertificatePin(), reloaded.serverCertificatePin());
			assertTrue(Files.isRegularFile(identityDirectory.resolve(".http-transport-identity.lock")));
		} finally {
			workers.shutdownNow();
			workers.awaitTermination(2, TimeUnit.SECONDS);
		}
	}

	@Test
	void compressedIpv6ServerNameSurvivesReload() throws Exception {
		Path identityDirectory = directory.resolve("ipv6-identity");
		HttpTlsIdentity created = HttpTlsIdentity.loadOrCreate(identityDirectory, "::1");
		String originalPin = created.serverCertificatePin();

		HttpTlsIdentity reloaded = HttpTlsIdentity.loadOrCreate(identityDirectory, "::1");
		assertEquals(originalPin, reloaded.serverCertificatePin());
	}

	@Test
	void staleInstanceAdoptsAnotherInstancesRenewedServerCertificate() throws Exception {
		Instant now = Instant.now();
		Path identityDirectory = directory.resolve("stale-renewal");
		Clock creationClock = Clock.fixed(now.minus(Duration.ofDays(340)), ZoneOffset.UTC);
		HttpTlsIdentity first = HttpTlsIdentity.loadOrCreate(identityDirectory, "localhost", creationClock);
		HttpTlsIdentity second = HttpTlsIdentity.loadOrCreate(identityDirectory, "localhost", creationClock);
		String originalPin = HttpTransportSecrets.certificatePin(first.serverCertificate());

		String renewedByFirst = first.serverCertificatePin();
		assertNotEquals(originalPin, renewedByFirst);
		String adoptedBySecond = second.serverCertificatePin();
		assertEquals(renewedByFirst, adoptedBySecond,
				"a stale instance must reload the persisted renewal instead of generating another server key");
		HttpTlsIdentity reloaded = HttpTlsIdentity.loadOrCreate(identityDirectory, "localhost");
		assertEquals(renewedByFirst, reloaded.serverCertificatePin());
	}

	private static HttpTlsIdentity openTogether(Path directory, CountDownLatch ready, CountDownLatch start) throws Exception {
		ready.countDown();
		if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("concurrent identity start timed out");
		return HttpTlsIdentity.loadOrCreate(directory, "localhost");
	}
}
