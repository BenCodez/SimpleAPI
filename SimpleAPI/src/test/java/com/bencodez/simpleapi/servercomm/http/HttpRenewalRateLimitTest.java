package com.bencodez.simpleapi.servercomm.http;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

class HttpRenewalRateLimitTest {
	@TempDir Path directory;

	@Test
	void repeatedRenewalIsLimitedPerBackendBeforeCertificateIssuance() throws Exception {
		AtomicReference<Instant> now = new AtomicReference<>(Instant.now());
		Clock clock = new Clock() {
			@Override public ZoneId getZone() { return ZoneOffset.UTC; }
			@Override public Clock withZone(ZoneId zone) { return this; }
			@Override public Instant instant() { return now.get(); }
		};
		HttpTlsIdentity identity = HttpTlsIdentity.loadOrCreate(directory.resolve("identity"), "localhost");
		HttpEnrollmentAuthority authority = new HttpEnrollmentAuthority(identity, clock);
		URI endpoint = URI.create("https://localhost:8443/");
		var code = authority.createConnectionCode("lobby-1", endpoint, Duration.ofMinutes(5));
		var original = authority.enroll("lobby-1", code.enrollmentToken());
		assertTrue(authority.authenticate("lobby-1", original.certificate()));
		var replacement = authority.renew("lobby-1", original.certificate());
		for (int index = 0; index < 10; index++) assertThrows(HttpEnrollmentAuthority.RenewalRateLimitException.class,
				() -> authority.renew("lobby-1", original.certificate()));
		assertTrue(authority.authenticate("lobby-1", replacement.certificate()));
		assertThrows(HttpEnrollmentAuthority.RenewalRateLimitException.class,
				() -> authority.renew("lobby-1", replacement.certificate()));
		var otherCode = authority.createConnectionCode("lobby-2", endpoint, Duration.ofMinutes(5));
		var other = authority.enroll("lobby-2", otherCode.enrollmentToken());
		assertNotNull(authority.renew("lobby-2", other.certificate()));
		now.set(now.get().plusSeconds(60));
		assertNotNull(authority.renew("lobby-1", replacement.certificate()));
	}

	@Test
	void renewalWindowIsSharedAcrossAuthorities() throws Exception {
		AtomicReference<Instant> now = new AtomicReference<>(Instant.now());
		Clock clock = new Clock() {
			@Override public ZoneId getZone() { return ZoneOffset.UTC; }
			@Override public Clock withZone(ZoneId zone) { return this; }
			@Override public Instant instant() { return now.get(); }
		};
		HttpTlsIdentity identity = HttpTlsIdentity.loadOrCreate(directory.resolve("shared-identity"), "localhost");
		Path stateDirectory = directory.resolve("shared-authority");
		java.nio.file.Files.createDirectory(stateDirectory);
		Path stateFile = stateDirectory.resolve("http-transport-clients.properties");
		HttpEnrollmentAuthority first = new HttpEnrollmentAuthority(identity, clock, stateFile);
		HttpEnrollmentAuthority second = new HttpEnrollmentAuthority(identity, clock, stateFile);
		URI endpoint = URI.create("https://localhost:8443/");
		var code = first.createConnectionCode("lobby-1", endpoint, Duration.ofMinutes(5));
		var original = first.enroll("lobby-1", code.enrollmentToken());
		assertTrue(first.authenticate("lobby-1", original.certificate()));

		first.renew("lobby-1", original.certificate());
		assertThrows(HttpEnrollmentAuthority.RenewalRateLimitException.class,
				() -> second.renew("lobby-1", original.certificate()));
		now.set(now.get().plusSeconds(60));
		assertNotNull(second.renew("lobby-1", original.certificate()));
	}

	@Test
	void revocationRemovesTheDurableRenewalWindow() throws Exception {
		Instant now = Instant.now();
		Clock clock = Clock.fixed(now, ZoneOffset.UTC);
		HttpTlsIdentity identity = HttpTlsIdentity.loadOrCreate(directory.resolve("revoked-identity"), "localhost");
		Path stateDirectory = directory.resolve("revoked-authority");
		java.nio.file.Files.createDirectory(stateDirectory);
		Path stateFile = stateDirectory.resolve("http-transport-clients.properties");
		HttpEnrollmentAuthority authority = new HttpEnrollmentAuthority(identity, clock, stateFile);
		URI endpoint = URI.create("https://localhost:8443/");
		var firstCode = authority.createConnectionCode("lobby-1", endpoint, Duration.ofMinutes(5));
		var firstCertificate = authority.enroll("lobby-1", firstCode.enrollmentToken());
		assertTrue(authority.authenticate("lobby-1", firstCertificate.certificate()));
		authority.renew("lobby-1", firstCertificate.certificate());
		authority.revoke("lobby-1");

		var secondCode = authority.createConnectionCode("lobby-1", endpoint, Duration.ofMinutes(5));
		var secondCertificate = authority.enroll("lobby-1", secondCode.enrollmentToken());
		assertTrue(authority.authenticate("lobby-1", secondCertificate.certificate()));
		assertNotNull(new HttpEnrollmentAuthority(identity, clock, stateFile)
				.renew("lobby-1", secondCertificate.certificate()));
	}
}
