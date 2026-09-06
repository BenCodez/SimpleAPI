package com.bencodez.simpleapi.servercomm.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
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
}
