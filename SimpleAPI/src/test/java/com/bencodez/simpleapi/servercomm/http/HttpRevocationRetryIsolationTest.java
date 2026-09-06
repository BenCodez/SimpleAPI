package com.bencodez.simpleapi.servercomm.http;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HttpRevocationRetryIsolationTest {
	@TempDir Path directory;

	@Test
	void failedRevocationRetryCannotClearOrReplaceAnotherServerBinding() throws Exception {
		HttpTlsIdentity identity = HttpTlsIdentity.loadOrCreate(directory.resolve("proxy"), "localhost");
		Path state = directory.resolve("authority");
		HttpEnrollmentAuthority authority = new HttpEnrollmentAuthority(identity, state);
		URI endpoint = URI.create("https://localhost:8443/");

		HttpConnectionCode codeA = authority.createConnectionCode("backend-a", endpoint, Duration.ofMinutes(5));
		HttpTlsIdentity.IssuedClientCertificate certificateA = authority.enroll("backend-a", codeA.enrollmentToken());
		HttpConnectionCode codeB = authority.createConnectionCode("backend-b", endpoint, Duration.ofMinutes(5));
		HttpTlsIdentity.IssuedClientCertificate certificateB = authority.enroll("backend-b", codeB.enrollmentToken());
		assertTrue(authority.authenticate("backend-a", certificateA.certificate()));
		assertTrue(authority.authenticate("backend-b", certificateB.certificate()));

		Path stateFile = state.resolve("http-transport-clients.properties");
		Files.delete(stateFile);
		Files.createDirectory(stateFile);

		assertThrows(IllegalStateException.class, () -> authority.revoke("backend-a"));
		assertFalse(authority.authenticate("backend-a", certificateA.certificate()),
				"authentication must fail closed while the revocation state is unresolved");
		assertFalse(authority.authenticate("backend-b", certificateB.certificate()),
				"the global retry guard must fail closed for every backend");

		assertThrows(IllegalStateException.class, () -> authority.revoke("nonexistent"));
		assertThrows(IllegalStateException.class, () -> authority.revoke("backend-b"));
		assertFalse(authority.authenticate("backend-a", certificateA.certificate()));
		assertFalse(authority.authenticate("backend-b", certificateB.certificate()),
				"unrelated revocation attempts must not clear the retry guard or restore state");

		Files.delete(stateFile);
		authority.revoke("backend-a");
		assertFalse(authority.authenticate("backend-a", certificateA.certificate()));
		assertTrue(authority.authenticate("backend-b", certificateB.certificate()),
				"retrying the original target must revoke only that target");

		HttpEnrollmentAuthority restarted = new HttpEnrollmentAuthority(identity, state);
		assertFalse(restarted.authenticate("backend-a", certificateA.certificate()));
		assertTrue(restarted.authenticate("backend-b", certificateB.certificate()),
				"the isolated revocation result must survive restart");
	}
}
