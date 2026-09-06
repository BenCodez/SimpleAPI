package com.bencodez.simpleapi.servercomm.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HttpEnrollmentPinTest {
	@TempDir Path directory;

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
	void stageReplacementCanRotateCaAndActivatesValidatedCredential() throws Exception {
		HttpTlsIdentity proxy = HttpTlsIdentity.loadOrCreate(directory.resolve("proxy"), "localhost");
		HttpTlsIdentity foreign = HttpTlsIdentity.loadOrCreate(directory.resolve("foreign"), "localhost");
		Path client = directory.resolve("client");
		HttpConnectionCode code = code(proxy, "lobby-1");
		HttpClientCredentialStore.saveEnrolled(client, code, proxy.issueClientCertificate("lobby-1"));
		String originalCaPin = HttpClientCredentialStore.loadProfile(client).caCertificatePin();

		HttpClientCredentialStore.StagedCredential staged = HttpClientCredentialStore.stageReplacement(
				client, foreign.issueClientCertificate("lobby-1"));
		String replacementCaPin = foreign.caCertificatePin();
		assertNotEquals(originalCaPin, replacementCaPin);
		assertEquals(replacementCaPin, staged.profile().caCertificatePin());
		assertEquals(replacementCaPin, HttpTransportSecrets.certificatePin(staged.credential().caCertificate()));

		HttpClientCredentialStore.activateReplacement(client, staged);
		HttpClientCredentialStore.EnrolledClient active = HttpClientCredentialStore.loadEnrolled(client);
		assertEquals(replacementCaPin, active.profile().caCertificatePin());
		assertEquals(replacementCaPin, HttpTransportSecrets.certificatePin(active.credential().caCertificate()));
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
}
