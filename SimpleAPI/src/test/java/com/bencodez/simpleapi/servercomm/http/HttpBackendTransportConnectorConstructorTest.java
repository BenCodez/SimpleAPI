package com.bencodez.simpleapi.servercomm.http;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HttpBackendTransportConnectorConstructorTest {
	@TempDir Path directory;

	@Test
	void invalidConnectionCodeDoesNotPreventImmediateValidRetry() throws Exception {
		HttpTlsIdentity identity = HttpTlsIdentity.loadOrCreate(directory.resolve("proxy"), "localhost");
		HttpEnrollmentAuthority authority = new HttpEnrollmentAuthority(identity, directory.resolve("authority"));
		try (HttpProxyTransportServer server = new HttpProxyTransportServer(new InetSocketAddress("localhost", 0),
				identity, authority, ignored -> { })) {
			server.start();
			HttpConnectionCode code = authority.createConnectionCode("lobby-1", server.endpoint("localhost"),
					Duration.ofMinutes(5));
			Path credentials = directory.resolve("client");
			HttpBackendTransportConnector.enroll(code, "lobby-1", credentials);

			assertThrows(IllegalArgumentException.class,
					() -> new HttpBackendTransportConnector(null, "lobby-1", credentials, ignored -> { }));
			try (HttpBackendTransportConnector ignored = new HttpBackendTransportConnector(code, "lobby-1",
					credentials, ignoredMessage -> { })) {
				// The valid retry must be able to claim both durable journal locks.
			}
		}
	}
}
