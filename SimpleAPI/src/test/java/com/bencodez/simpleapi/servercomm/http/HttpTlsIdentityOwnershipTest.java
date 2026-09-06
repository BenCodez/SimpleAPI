package com.bencodez.simpleapi.servercomm.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
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

	private static HttpTlsIdentity openTogether(Path directory, CountDownLatch ready, CountDownLatch start) throws Exception {
		ready.countDown();
		if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("concurrent identity start timed out");
		return HttpTlsIdentity.loadOrCreate(directory, "localhost");
	}
}
