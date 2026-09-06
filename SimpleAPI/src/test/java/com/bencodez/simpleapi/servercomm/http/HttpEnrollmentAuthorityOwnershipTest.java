package com.bencodez.simpleapi.servercomm.http;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HttpEnrollmentAuthorityOwnershipTest {
	@TempDir Path directory;

	@Test
	void staleAuthorityCannotEraseAnotherEnrollmentOrRestoreARevokedBinding() throws Exception {
		HttpTlsIdentity identity = HttpTlsIdentity.loadOrCreate(directory.resolve("identity"), "localhost");
		Path state = directory.resolve("authority");
		HttpEnrollmentAuthority first = new HttpEnrollmentAuthority(identity, state);
		HttpEnrollmentAuthority stale = new HttpEnrollmentAuthority(identity, state);
		URI endpoint = URI.create("https://localhost:8443/");

		HttpConnectionCode firstCode = first.createConnectionCode("lobby-1", endpoint, Duration.ofMinutes(5));
		HttpConnectionCode secondCode = stale.createConnectionCode("lobby-2", endpoint, Duration.ofMinutes(5));
		assertNotNull(first.enroll("lobby-1", firstCode.enrollmentToken()),
				"a stale authority rewrite must retain another instance's pending enrollment");
		var secondCertificate = stale.enroll("lobby-2", secondCode.enrollmentToken());
		assertNotNull(secondCertificate);

		first.revoke("lobby-2");
		stale.createConnectionCode("lobby-3", endpoint, Duration.ofMinutes(5));
		HttpEnrollmentAuthority reloaded = new HttpEnrollmentAuthority(identity, state);
		assertFalse(reloaded.authenticate("lobby-2", secondCertificate.certificate()),
				"a stale authority rewrite must not restore a revoked certificate");
	}
}
