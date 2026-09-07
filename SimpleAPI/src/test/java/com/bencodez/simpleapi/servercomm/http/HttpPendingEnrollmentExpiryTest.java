package com.bencodez.simpleapi.servercomm.http;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HttpPendingEnrollmentExpiryTest {
	@TempDir Path directory;

	@Test
	void expiredPendingCertificateCannotActivateButActiveBindingRemainsValid() throws Exception {
		AtomicReference<Instant> now = new AtomicReference<>(Instant.now());
		Clock clock = new Clock() {
			@Override public ZoneId getZone() { return ZoneOffset.UTC; }
			@Override public Clock withZone(ZoneId zone) { return this; }
			@Override public Instant instant() { return now.get(); }
		};
		HttpTlsIdentity identity = HttpTlsIdentity.loadOrCreate(directory.resolve("identity"), "localhost");
		HttpEnrollmentAuthority authority = new HttpEnrollmentAuthority(identity, clock);
		URI endpoint = URI.create("https://localhost:8443/");
		HttpConnectionCode pendingCode = authority.createConnectionCode("pending", endpoint, Duration.ofSeconds(1));
		var pending = authority.enroll("pending", pendingCode.enrollmentToken());
		HttpConnectionCode activeCode = authority.createConnectionCode("active", endpoint, Duration.ofSeconds(1));
		var active = authority.enroll("active", activeCode.enrollmentToken());
		assertTrue(authority.authenticate("active", active.certificate()));
		now.set(pendingCode.expiresAt());
		assertFalse(authority.authenticate("pending", pending.certificate()));
		assertTrue(authority.authenticate("active", active.certificate()));
		now.set(now.get().plusSeconds(1));
		assertFalse(authority.authenticate("pending", pending.certificate()));
	}
}
