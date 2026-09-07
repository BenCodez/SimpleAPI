package com.bencodez.simpleapi.servercomm.http;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

	@Test
	void authorityClearsPublishedFailureAfterSharedStateIsReconciled() throws Exception {
		HttpTlsIdentity identity = HttpTlsIdentity.loadOrCreate(directory.resolve("reconciled-identity"), "localhost");
		Path state = directory.resolve("reconciled-authority");
		HttpEnrollmentAuthority failed = new HttpEnrollmentAuthority(identity, state);
		HttpEnrollmentAuthority peer = new HttpEnrollmentAuthority(identity, state);
		URI endpoint = URI.create("https://localhost:8443/");
		HttpConnectionCode activeCode = failed.createConnectionCode("active", endpoint, Duration.ofMinutes(5));
		var active = failed.enroll("active", activeCode.enrollmentToken());
		HttpConnectionCode pendingCode = failed.createConnectionCode("pending", endpoint, Duration.ofMinutes(5));
		try (var forces = org.mockito.Mockito.mockStatic(com.bencodez.simpleapi.file.DurableFiles.class,
				org.mockito.Mockito.CALLS_REAL_METHODS)) {
			forces.when(() -> com.bencodez.simpleapi.file.DurableFiles.forceDirectory(state))
					.thenThrow(new java.io.IOException("injected publication failure"));
			assertThrows(com.bencodez.simpleapi.file.DurableFiles.PublishedException.class,
					() -> failed.enroll("pending", pendingCode.enrollmentToken()));
		}

		peer.createConnectionCode("peer", endpoint, Duration.ofMinutes(5));
		assertTrue(failed.authenticate("active", active.certificate()),
				"adopting and forcing a peer's complete state must clear the stale fail-closed marker");
	}
}
