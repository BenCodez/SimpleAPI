package com.bencodez.simpleapi.servercomm.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HttpEnrollmentPinTest {
	@TempDir Path directory;

	@Test
	void enrollmentLifetimeRequiresAtLeastOneSecond() throws Exception {
		HttpTlsIdentity identity = HttpTlsIdentity.loadOrCreate(directory.resolve("proxy"), "localhost");
		java.time.Clock clock = java.time.Clock.fixed(Instant.parse("2026-09-05T12:00:00.999Z"),
				java.time.ZoneOffset.UTC);
		HttpEnrollmentAuthority authority = new HttpEnrollmentAuthority(identity, clock);
		URI endpoint = URI.create("https://localhost:8443/");
		for (Duration lifetime : new Duration[] { Duration.ofNanos(1), Duration.ofMillis(999),
				Duration.ofSeconds(1).minusNanos(1), Duration.ZERO, Duration.ofSeconds(-1) }) {
			assertThrows(IllegalArgumentException.class,
					() -> authority.createConnectionCode("lobby-1", endpoint, lifetime));
		}
		HttpConnectionCode minimum = authority.createConnectionCode("lobby-1", endpoint, Duration.ofSeconds(1));
		org.junit.jupiter.api.Assertions.assertTrue(HttpConnectionCode.parse(minimum.encode()).expiresAt().isAfter(clock.instant()));
	}

	@Test
	void rejectsDifferentCaBundleDuringInitialEnrollment() throws Exception {
		HttpTlsIdentity proxy = HttpTlsIdentity.loadOrCreate(directory.resolve("proxy"), "localhost");
		HttpTlsIdentity foreign = HttpTlsIdentity.loadOrCreate(directory.resolve("foreign"), "localhost");
		HttpConnectionCode code = code(proxy, "lobby-1");

		assertThrows(java.io.IOException.class, () -> HttpClientCredentialStore.saveEnrolled(
				directory.resolve("client"), code, foreign.issueClientCertificate("lobby-1")));
		assertFalse(HttpClientCredentialStore.hasEnrolledProfile(directory.resolve("client")));
	}

	@Test
	void rejectsDifferentCaBundleWithoutReplacingExistingEnrollment() throws Exception {
		HttpTlsIdentity proxy = HttpTlsIdentity.loadOrCreate(directory.resolve("proxy"), "localhost");
		HttpTlsIdentity foreign = HttpTlsIdentity.loadOrCreate(directory.resolve("foreign"), "localhost");
		Path client = directory.resolve("client");
		HttpConnectionCode code = code(proxy, "lobby-1");
		HttpTlsIdentity.IssuedClientCertificate original = proxy.issueClientCertificate("lobby-1");
		HttpClientCredentialStore.saveEnrolled(client, code, original);
		HttpClientCredentialStore.EnrolledClient before = HttpClientCredentialStore.loadEnrolled(client);

		assertThrows(java.io.IOException.class, () -> HttpClientCredentialStore.saveEnrolled(
				client, code, foreign.issueClientCertificate("lobby-1")));
		HttpClientCredentialStore.EnrolledClient after = HttpClientCredentialStore.loadEnrolled(client);
		assertEquals(before.profile(), after.profile());
		assertArrayEquals(before.credential().certificate().getEncoded(), after.credential().certificate().getEncoded());
	}

	@Test
	void acceptsBundleSignedByConnectionCodeCa() throws Exception {
		HttpTlsIdentity proxy = HttpTlsIdentity.loadOrCreate(directory.resolve("proxy"), "localhost");
		Path client = directory.resolve("client");
		HttpConnectionCode code = code(proxy, "lobby-1");

		HttpClientCredentialStore.saveEnrolled(client, code, proxy.issueClientCertificate("lobby-1"));
		HttpClientCredentialStore.EnrolledClient enrolled = HttpClientCredentialStore.loadEnrolled(client);
		assertEquals(code.caCertificatePin(), enrolled.profile().caCertificatePin());
		assertEquals(code.caCertificatePin(), HttpTransportSecrets.certificatePin(enrolled.credential().caCertificate()));
	}

	@Test
	void rejectsPkcs12WithUnrelatedPrivateKeyDuringEnrollmentAndReplacement() throws Exception {
		HttpTlsIdentity proxy = HttpTlsIdentity.loadOrCreate(directory.resolve("proxy"), "localhost");
		HttpConnectionCode code = code(proxy, "lobby-1");
		HttpTlsIdentity.IssuedClientCertificate original = proxy.issueClientCertificate("lobby-1");
		HttpTlsIdentity.IssuedClientCertificate mismatched = withUnrelatedPrivateKey(original);
		Path client = directory.resolve("client");

		assertThrows(java.io.IOException.class, () -> HttpClientCredentialStore.saveEnrolled(client, code, mismatched));
		assertFalse(HttpClientCredentialStore.hasEnrolledProfile(client));

		HttpClientCredentialStore.saveEnrolled(client, code, original);
		HttpClientCredentialStore.EnrolledClient before = HttpClientCredentialStore.loadEnrolled(client);
		assertThrows(java.io.IOException.class, () -> HttpClientCredentialStore.stageReplacement(client, mismatched));
		HttpClientCredentialStore.EnrolledClient after = HttpClientCredentialStore.loadEnrolled(client);
		assertEquals(before.profile(), after.profile());
		assertArrayEquals(before.credential().certificate().getEncoded(), after.credential().certificate().getEncoded());
	}

	@Test
	void stageReplacementRejectsForeignCaAndPreservesActiveEnrollment() throws Exception {
		HttpTlsIdentity proxy = HttpTlsIdentity.loadOrCreate(directory.resolve("proxy"), "localhost");
		HttpTlsIdentity foreign = HttpTlsIdentity.loadOrCreate(directory.resolve("foreign"), "localhost");
		Path client = directory.resolve("client");
		HttpConnectionCode code = code(proxy, "lobby-1");
		HttpTlsIdentity.IssuedClientCertificate originalCredential = proxy.issueClientCertificate("lobby-1");
		HttpClientCredentialStore.saveEnrolled(client, code, originalCredential);
		HttpClientCredentialStore.EnrolledClient before = HttpClientCredentialStore.loadEnrolled(client);

		assertThrows(java.io.IOException.class, () -> HttpClientCredentialStore.stageReplacement(
				client, foreign.issueClientCertificate("lobby-1")));
		HttpClientCredentialStore.EnrolledClient after = HttpClientCredentialStore.loadEnrolled(client);
		assertEquals(before.profile(), after.profile());
		assertArrayEquals(before.credential().certificate().getEncoded(), after.credential().certificate().getEncoded());
	}

	@Test
	void stageReplacementAcceptsCaRenewalWithSameAuthorityKey() throws Exception {
		Instant now = Instant.now();
		java.time.Clock originalClock = java.time.Clock.fixed(
				now.minus(Duration.ofDays(9 * 365L + 30L)), java.time.ZoneOffset.UTC);
		Path proxyDirectory = directory.resolve("proxy");
		HttpTlsIdentity original = HttpTlsIdentity.loadOrCreate(proxyDirectory, "localhost", originalClock);
		HttpTlsIdentity.IssuedClientCertificate originalCredential = original.issueClientCertificate("lobby-1", now);
		String originalCaPin = HttpTransportSecrets.certificatePin(original.caCertificate());
		java.security.PublicKey originalCaKey = original.caCertificate().getPublicKey();
		Path client = directory.resolve("client");
		HttpConnectionCode code = new HttpConnectionCode("lobby-1", URI.create("https://localhost:8443/"),
				HttpTransportSecrets.certificatePin(original.serverCertificate()),
				HttpTransportSecrets.certificatePin(original.caCertificate()), now.plusSeconds(60), "A".repeat(43));
		HttpClientCredentialStore.saveEnrolled(client, code, originalCredential);

		HttpTlsIdentity renewed = HttpTlsIdentity.loadOrCreate(proxyDirectory, "localhost",
				java.time.Clock.fixed(now, java.time.ZoneOffset.UTC));
		assertNotEquals(originalCaPin, renewed.caCertificatePin());
		assertEquals(originalCaKey, renewed.caCertificate().getPublicKey());

		HttpClientCredentialStore.StagedCredential staged = HttpClientCredentialStore.stageReplacement(
				client, renewed.issueClientCertificate("lobby-1", now));
		assertEquals(renewed.caCertificatePin(), staged.profile().caCertificatePin());
		HttpClientCredentialStore.activateReplacement(client, staged);
		assertEquals(renewed.caCertificatePin(), HttpClientCredentialStore.loadProfile(client).caCertificatePin());
	}

	@Test
	void invalidConnectionCodeEndpointsDoNotConsumePendingCapacityOrBreakReload() throws Exception {
		HttpTlsIdentity identity = HttpTlsIdentity.loadOrCreate(directory.resolve("proxy"), "localhost");
		Path state = directory.resolve("state");
		HttpEnrollmentAuthority authority = new HttpEnrollmentAuthority(identity, state);
		for (int i = 0; i < 128; i++) {
			final int index = i;
			URI endpoint = (i & 1) == 0 ? null : URI.create("http://localhost:8443/");
			assertThrows(IllegalArgumentException.class, () -> authority.createConnectionCode(
					"lobby-" + index, endpoint, Duration.ofMinutes(5)));
		}

		HttpConnectionCode first = authority.createConnectionCode("lobby-1", URI.create("https://localhost:8443/"),
				Duration.ofMinutes(5));
		assertEquals("lobby-1", first.serverId());
		HttpEnrollmentAuthority reloaded = new HttpEnrollmentAuthority(identity, state);
		HttpConnectionCode second = reloaded.createConnectionCode("lobby-2", URI.create("https://localhost:8443/"),
				Duration.ofMinutes(5));
		assertEquals("lobby-2", second.serverId());
	}

	private HttpConnectionCode code(HttpTlsIdentity identity, String serverId) {
		return new HttpConnectionCode(serverId, URI.create("https://localhost:8443/"),
				identity.serverCertificatePin(), identity.caCertificatePin(), Instant.now().plusSeconds(300),
				HttpTransportSecrets.randomToken());
	}

	private HttpTlsIdentity.IssuedClientCertificate withUnrelatedPrivateKey(
			HttpTlsIdentity.IssuedClientCertificate issued) throws Exception {
		char[] password = issued.password();
		KeyStore source = KeyStore.getInstance("PKCS12");
		source.load(new ByteArrayInputStream(issued.pkcs12()), password);
		KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
		generator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
		KeyPair unrelated = generator.generateKeyPair();
		KeyStore replacement = KeyStore.getInstance("PKCS12");
		replacement.load(null, password);
		replacement.setKeyEntry("client", unrelated.getPrivate(), password, source.getCertificateChain("client"));
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		replacement.store(bytes, password);
		return new HttpTlsIdentity.IssuedClientCertificate(issued.serverId(), issued.certificate(), bytes.toByteArray(), password);
	}
}
