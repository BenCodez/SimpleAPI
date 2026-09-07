package com.bencodez.simpleapi.servercomm.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.bencodez.simpleapi.file.DurableFiles;

class HttpEnrollmentPublicationRecoveryTest {
	@TempDir Path directory;

	@Test
	void publishedInitialCredentialIsConfirmedWithoutReusingTheEnrollmentToken() throws Exception {
		HttpTlsIdentity identity = HttpTlsIdentity.loadOrCreate(directory.resolve("proxy"), "localhost");
		HttpEnrollmentAuthority authority = new HttpEnrollmentAuthority(identity, directory.resolve("authority"));
		Path credentials = directory.resolve("client").toAbsolutePath().normalize();
		HttpConnectionCode code;
		HttpClientCredentialStore.ClientCredential published;
		try (HttpProxyTransportServer server = new HttpProxyTransportServer(new InetSocketAddress("localhost", 0),
				identity, authority, ignored -> { })) {
			server.start();
			code = authority.createConnectionCode("lobby-1", server.endpoint("localhost"), Duration.ofMinutes(5));
			Path current = credentials.resolve("http-transport-client-current");
			try (var forces = org.mockito.Mockito.mockStatic(DurableFiles.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
				forces.when(() -> DurableFiles.forceDirectory(credentials)).thenAnswer(call -> {
					if (Files.isRegularFile(current, LinkOption.NOFOLLOW_LINKS))
						throw new java.io.IOException("injected CURRENT publication failure");
					return call.callRealMethod();
				});
				assertThrows(DurableFiles.PublishedException.class,
						() -> HttpBackendTransportConnector.enroll(code, "lobby-1", credentials));
			}
			published = HttpClientCredentialStore.load(credentials);
		}

		// The endpoint is now closed. Recovery can succeed only by confirming the
		// already-published CURRENT pointer rather than sending the token again.
		HttpClientCredentialStore.ClientCredential recovered = HttpBackendTransportConnector.enroll(
				code, "lobby-1", credentials);
		assertArrayEquals(published.certificate().getEncoded(), recovered.certificate().getEncoded());
		assertTrue(authority.authenticate("lobby-1", recovered.certificate()));
	}

	@Test
	void concurrentEnrollmentForOneCredentialDirectoryPublishesOneCertificate() throws Exception {
		HttpTlsIdentity identity = HttpTlsIdentity.loadOrCreate(directory.resolve("concurrent-proxy"), "localhost");
		HttpEnrollmentAuthority authority = new HttpEnrollmentAuthority(identity, directory.resolve("concurrent-authority"));
		Path credentials = directory.resolve("concurrent-client");
		var workers = Executors.newFixedThreadPool(2);
		try (HttpProxyTransportServer server = new HttpProxyTransportServer(new InetSocketAddress("localhost", 0),
				identity, authority, ignored -> { })) {
			server.start();
			HttpConnectionCode code = authority.createConnectionCode("lobby-1", server.endpoint("localhost"), Duration.ofMinutes(5));
			CountDownLatch start = new CountDownLatch(1);
			var first = workers.submit(() -> { start.await(); return HttpBackendTransportConnector.enroll(code, "lobby-1", credentials); });
			var second = workers.submit(() -> { start.await(); return HttpBackendTransportConnector.enroll(code, "lobby-1", credentials); });
			start.countDown();
			var firstCredential = first.get();
			var secondCredential = second.get();
			assertArrayEquals(firstCredential.certificate().getEncoded(), secondCredential.certificate().getEncoded(),
					"the second caller must recover the generation published under the directory lock");
			assertTrue(authority.authenticate("lobby-1", firstCredential.certificate()));
		} finally { workers.shutdownNow(); }
	}

	@Test
	void enrollmentMonitorSerializesOnlyTheSameCredentialDirectory() throws Exception {
		Path firstDirectory = directory.resolve("monitor-first");
		Path secondDirectory = directory.resolve("monitor-second");
		CountDownLatch firstEntered = new CountDownLatch(1);
		CountDownLatch releaseFirst = new CountDownLatch(1);
		CountDownLatch sameDirectoryEntered = new CountDownLatch(1);
		var workers = Executors.newFixedThreadPool(3);
		try {
			var first = workers.submit(() -> HttpClientCredentialStore.withEnrollmentLock(firstDirectory, () -> {
				firstEntered.countDown();
				releaseFirst.await();
				return true;
			}));
			assertTrue(firstEntered.await(2, TimeUnit.SECONDS));
			var unrelated = workers.submit(() -> HttpClientCredentialStore.withEnrollmentLock(secondDirectory, () -> true));
			assertTrue(unrelated.get(2, TimeUnit.SECONDS),
					"an unrelated credential root must not wait for another root's network operation");
			var sameDirectory = workers.submit(() -> HttpClientCredentialStore.withEnrollmentLock(firstDirectory, () -> {
				sameDirectoryEntered.countDown();
				return true;
			}));
			assertFalse(sameDirectoryEntered.await(100, TimeUnit.MILLISECONDS));
			releaseFirst.countDown();
			assertTrue(first.get(2, TimeUnit.SECONDS));
			assertTrue(sameDirectory.get(2, TimeUnit.SECONDS));
		} finally {
			releaseFirst.countDown();
			workers.shutdownNow();
		}
	}
}
