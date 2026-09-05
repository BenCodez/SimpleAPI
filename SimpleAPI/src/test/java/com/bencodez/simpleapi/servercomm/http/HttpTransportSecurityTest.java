package com.bencodez.simpleapi.servercomm.http;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HttpTransportSecurityTest {
	@Test
	void failedRenewalRetriesBeforeActiveCertificateExpires() {
		assertEquals(Duration.ofMinutes(5),
				HttpBackendTransportConnector.renewalRetryDelay(Duration.ofHours(6)));
		assertEquals(Duration.ofMinutes(1),
				HttpBackendTransportConnector.renewalRetryDelay(Duration.ofMinutes(4)));
		assertTrue(HttpBackendTransportConnector.renewalRetryDelay(Duration.ofSeconds(3))
				.compareTo(Duration.ofSeconds(3)) < 0);
	}

	@Test
	void connectionCodeRejectsExplicitZeroPort() {
		assertThrows(IllegalArgumentException.class,
				() -> new HttpConnectionCode("lobby", URI.create("https://proxy.example.test:0/"), pin('a'),
						pin('b'), Instant.now().plusSeconds(60), "token"));
	}

	@TempDir Path directory;

	@Test
	void backendResponseReaderRejectsBodiesBeyondTheWireLimit() throws Exception {
		byte[] maximum = new byte[HttpTransportProtocol.MAX_BODY_BYTES];
		assertEquals(maximum.length, HttpBackendTransportConnector.readLimited(
				new java.io.ByteArrayInputStream(maximum)).length);
		assertThrows(java.io.IOException.class, () -> HttpBackendTransportConnector.readLimited(
				new java.io.ByteArrayInputStream(new byte[HttpTransportProtocol.MAX_BODY_BYTES + 1])));
	}

	@Test
	void connectionCodeRoundTripsAndRejectsAccidentalCorruption() {
		HttpConnectionCode original = new HttpConnectionCode("lobby.eu", URI.create("https://Proxy.Example.test:8443/http"), pin('a'), pin('b'),
				Instant.parse("2030-01-01T00:00:00Z"), HttpTransportSecrets.randomToken());
		String encoded = original.encode();
		HttpConnectionCode parsed = HttpConnectionCode.parse(encoded);
		assertEquals("lobby.eu", parsed.serverId());
		assertEquals(URI.create("https://proxy.example.test:8443/http/"), parsed.endpoint());
		assertEquals(original.serverCertificatePin(), parsed.serverCertificatePin());
		char last = encoded.charAt(encoded.length() - 1);
		assertThrows(IllegalArgumentException.class, () -> HttpConnectionCode.parse(encoded.substring(0, encoded.length() - 1)
				+ (last == 'A' ? 'B' : 'A')));
		assertThrows(IllegalArgumentException.class, () -> HttpConnectionCode.parse("http://not-a-code"));
	}

	@Test
	void connectionCodePreservesEscapedEndpointPaths() {
		HttpConnectionCode original = new HttpConnectionCode("lobby.eu",
				URI.create("https://Proxy.Example.test:8443/api%20root/%2F"), pin('a'), pin('b'),
				Instant.parse("2030-01-01T00:00:00Z"), HttpTransportSecrets.randomToken());
		URI expected = URI.create("https://proxy.example.test:8443/api%20root/%2F/");
		assertEquals(expected, original.endpoint());
		assertEquals(expected, HttpConnectionCode.parse(original.encode()).endpoint());
	}

	@Test
	void legacyConnectionCodesAndConsumedMarkersRemainCompatible() throws Exception {
		HttpTlsIdentity identity = HttpTlsIdentity.loadOrCreate(directory.resolve("legacy-code-proxy"), "proxy.example.test");
		HttpConnectionCode legacy = new HttpConnectionCode("lobby", URI.create("https://proxy.example.test:8443/"),
				identity.serverCertificatePin(), identity.caCertificatePin(),
				Instant.now().plusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.SECONDS),
				HttpTransportSecrets.randomToken());
		assertEquals(legacy, HttpConnectionCode.parse(legacy.encodeLegacy()));

		Path client = directory.resolve("legacy-code-client");
		HttpClientCredentialStore.saveEnrolled(client, legacy, identity.issueClientCertificate("lobby"));
		Path active = client.resolve("http-transport-client-generations")
				.resolve(Files.readString(client.resolve("http-transport-client-current")));
		Files.writeString(active.resolve("http-transport-connection-code.sha256"),
				HttpTransportSecrets.sha256Hex(legacy.encodeLegacy().getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
		assertTrue(HttpClientCredentialStore.matchesEnrollmentCode(client,
				HttpConnectionCode.parse(legacy.encodeLegacy())));
	}

	@Test
	void expiredCodesAreNotActive() {
		HttpConnectionCode code = new HttpConnectionCode("lobby", URI.create("https://proxy.example.test/"), pin('a'), pin('b'),
				Instant.parse("2029-12-31T23:59:59Z"), HttpTransportSecrets.randomToken());
		assertFalse(code.isActive(Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC)));
		assertThrows(IllegalArgumentException.class, () -> code.requireActive(Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC)));
	}

	@Test
	void inboundDeliveryFenceRejectsCorruptionAndPathReplacement() throws Exception {
		Path corruptCredentials = directory.resolve("corrupt-client");
		Path corruptFence = corruptCredentials.resolve("http-transport-inbound-deliveries");
		Files.createDirectories(corruptFence);
		Files.writeString(corruptFence.resolve("not-a-delivery.seen"), "not-a-delivery");
		assertThrows(java.io.IOException.class, () -> new HttpInboundDeliveryStore(corruptCredentials));

		Path replacedCredentials = directory.resolve("replaced-client");
		Files.createDirectories(replacedCredentials);
		HttpInboundDeliveryStore store = new HttpInboundDeliveryStore(replacedCredentials);
		Path fence = replacedCredentials.resolve("http-transport-inbound-deliveries");
		Path outside = directory.resolve("outside-fence");
		Files.createDirectory(outside);
		Files.delete(fence);
		Files.createSymbolicLink(fence, outside);
		assertThrows(java.io.IOException.class, () -> store.reserve(java.util.UUID.randomUUID().toString()));
	}

	@Test
	void sealedInboundStoreCannotChangeAfterOwnershipHandoff() throws Exception {
		Path credentials = directory.resolve("sealed-client");
		Files.createDirectories(credentials);
		String id = java.util.UUID.randomUUID().toString();
		HttpInboundDeliveryStore store = new HttpInboundDeliveryStore(credentials);
		store.reserve(id);
		store.markRunning(id);
		store.seal();
		assertThrows(java.io.IOException.class, () -> store.markCompleted(id));
		assertEquals(HttpInboundDeliveryStore.State.RUNNING, new HttpInboundDeliveryStore(credentials).state(id));
	}

	@Test
	void identityIsDurableAndPinsRejectTheWrongServer() throws Exception {
		HttpTlsIdentity created = HttpTlsIdentity.loadOrCreate(directory, "localhost");
		HttpTlsIdentity loaded = HttpTlsIdentity.loadOrCreate(directory, "localhost");
		assertEquals(created.serverCertificatePin(), loaded.serverCertificatePin());
		assertEquals(created.caCertificatePin(), loaded.caCertificatePin());
		HttpConnectionCode correct = new HttpConnectionCode("lobby", URI.create("https://localhost:8443/"), created.serverCertificatePin(),
				created.caCertificatePin(), Instant.now().plusSeconds(60), HttpTransportSecrets.randomToken());
		HttpConnectionCode incorrect = new HttpConnectionCode("lobby", URI.create("https://localhost:8443/"), pin('0'), created.caCertificatePin(),
				Instant.now().plusSeconds(60), HttpTransportSecrets.randomToken());
		assertTrue(HttpPinnedTls.matchesServerPin(correct, created.serverCertificate()));
		assertFalse(HttpPinnedTls.matchesServerPin(incorrect, created.serverCertificate()));
		assertTrue(Files.exists(directory.resolve("http-transport-ca.p12")));
		HttpTlsIdentity rotated = HttpTlsIdentity.loadOrCreate(directory, "127.0.0.1");
		assertEquals(created.caCertificatePin(), rotated.caCertificatePin());
		assertNotEquals(created.serverCertificatePin(), rotated.serverCertificatePin());
	}

	@Test
	void oversizedTlsAndClientPasswordFilesFailClosed() throws Exception {
		Path identityDirectory = directory.resolve("oversized-password-proxy");
		HttpTlsIdentity identity = HttpTlsIdentity.loadOrCreate(identityDirectory, "localhost");
		Files.writeString(identityDirectory.resolve("http-transport-password"), "x".repeat(129));
		assertThrows(java.io.IOException.class, () -> HttpTlsIdentity.loadOrCreate(identityDirectory, "localhost"));

		Path clientDirectory = directory.resolve("oversized-password-client");
		HttpClientCredentialStore.save(clientDirectory, identity.issueClientCertificate("lobby-1"));
		Files.writeString(clientDirectory.resolve("http-transport-client-password"), "x".repeat(129));
		assertThrows(java.io.IOException.class, () -> HttpClientCredentialStore.load(clientDirectory));
	}

	@Test
	void privateCredentialRootsRejectSymbolicLinks() throws Exception {
		Path identityTarget = directory.resolve("identity-target");
		Path identityLink = directory.resolve("identity-link");
		Files.createDirectory(identityTarget);
		Files.createSymbolicLink(identityLink, identityTarget);
		assertThrows(java.io.IOException.class, () -> HttpTlsIdentity.loadOrCreate(identityLink, "localhost"));
		assertFalse(Files.exists(identityTarget.resolve("http-transport-ca.p12")));

		HttpTlsIdentity authority = HttpTlsIdentity.loadOrCreate(directory.resolve("safe-identity"), "localhost");
		HttpTlsIdentity.IssuedClientCertificate issued = authority.issueClientCertificate("lobby-1");
		Path credentialTarget = directory.resolve("credential-target");
		Path credentialLink = directory.resolve("credential-link");
		Files.createDirectory(credentialTarget);
		Files.createSymbolicLink(credentialLink, credentialTarget);
		assertThrows(java.io.IOException.class, () -> HttpClientCredentialStore.save(credentialLink, issued));
		HttpConnectionCode code = new HttpConnectionCode("lobby-1", URI.create("https://localhost:8443/"),
				authority.serverCertificatePin(), authority.caCertificatePin(), Instant.now().plusSeconds(60), "A".repeat(43));
		assertThrows(java.io.IOException.class, () -> HttpClientCredentialStore.saveEnrolled(credentialLink, code, issued));
		try (var files = Files.list(credentialTarget)) {
			assertTrue(files.findAny().isEmpty(), "a symlinked credential root must receive no private files");
		}
	}

	@Test
	void markedIncompleteFirstRunTlsProvisioningRecoversWithoutManualCleanup() throws Exception {
		Path source = directory.resolve("complete-identity");
		HttpTlsIdentity original = HttpTlsIdentity.loadOrCreate(source, "localhost");
		Path interrupted = directory.resolve("interrupted-identity");
		Files.createDirectories(interrupted);
		Files.writeString(interrupted.resolve("http-transport-initializing"), "initializing\n");
		Files.copy(source.resolve("http-transport-ca.p12"), interrupted.resolve("http-transport-ca.p12"));
		Files.copy(source.resolve("http-transport-server.p12"), interrupted.resolve("http-transport-server.p12"));

		HttpTlsIdentity recovered = HttpTlsIdentity.loadOrCreate(interrupted, "localhost");

		assertNotEquals(original.caCertificatePin(), recovered.caCertificatePin());
		assertTrue(Files.exists(interrupted.resolve("http-transport-ca.p12")));
		assertTrue(Files.exists(interrupted.resolve("http-transport-server.p12")));
		assertTrue(Files.exists(interrupted.resolve("http-transport-password")));
		assertFalse(Files.exists(interrupted.resolve("http-transport-initializing")));
	}

	@Test
	void unmarkedPartialIdentityFailsClosedWithExternalTransportState() throws Exception {
		Path source = directory.resolve("external-state-source");
		HttpTlsIdentity.loadOrCreate(source, "localhost");
		Path partial = directory.resolve("external-state-partial");
		Files.createDirectories(partial);
		Files.copy(source.resolve("http-transport-ca.p12"), partial.resolve("http-transport-ca.p12"));
		byte[] retainedCa = Files.readAllBytes(partial.resolve("http-transport-ca.p12"));
		Path externalState = directory.resolve("external-authority");
		Files.createDirectories(externalState);
		Files.writeString(externalState.resolve("http-transport-clients.properties"), "version=3\n");

		assertThrows(java.io.IOException.class, () -> HttpTlsIdentity.loadOrCreate(partial, "localhost"));
		assertTrue(Arrays.equals(retainedCa, Files.readAllBytes(partial.resolve("http-transport-ca.p12"))),
				"fail-closed recovery must preserve the surviving CA bytes");
	}

	@Test
	void completedFirstRunFilesRecoverWhenInitializationMarkerSurvives() throws Exception {
		Path interrupted = directory.resolve("marked-identity");
		HttpTlsIdentity original = HttpTlsIdentity.loadOrCreate(interrupted, "localhost");
		String originalCaPin = original.caCertificatePin();
		Files.writeString(interrupted.resolve("http-transport-initializing"), "initializing\n");

		HttpTlsIdentity recovered = HttpTlsIdentity.loadOrCreate(interrupted, "localhost");

		assertNotEquals(originalCaPin, recovered.caCertificatePin());
		assertFalse(Files.exists(interrupted.resolve("http-transport-initializing")));
	}

	@Test
	void incompleteEstablishedTlsIdentityFailsClosed() throws Exception {
		Path established = directory.resolve("established-identity");
		HttpTlsIdentity.loadOrCreate(established, "localhost");
		Files.writeString(established.resolve("http-transport-clients.properties"), "version=2\n");
		Files.delete(established.resolve("http-transport-server.p12"));

		assertThrows(java.io.IOException.class, () -> HttpTlsIdentity.loadOrCreate(established, "localhost"));
		assertTrue(Files.exists(established.resolve("http-transport-ca.p12")));
		assertTrue(Files.exists(established.resolve("http-transport-password")));
	}

	@Test
	void enrollmentIsSingleUseBoundToServerAndRevocable() throws Exception {
		HttpTlsIdentity identity = HttpTlsIdentity.loadOrCreate(directory, "localhost");
		Clock clock = Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC);
		HttpEnrollmentAuthority authority = new HttpEnrollmentAuthority(identity, clock);
		HttpConnectionCode wrongTargetCode = authority.createConnectionCode("lobby-1", URI.create("https://localhost:8443/"), Duration.ofMinutes(5));
		assertThrows(IllegalArgumentException.class, () -> authority.enroll("attacker", wrongTargetCode.enrollmentToken()));
		assertTrue(authority.authenticate("lobby-1", authority.enroll("lobby-1", wrongTargetCode.enrollmentToken()).certificate()),
				"a wrong backend must not consume another backend's connection code");
		authority.revoke("lobby-1");
		HttpConnectionCode code = authority.createConnectionCode("lobby-1", URI.create("https://localhost:8443/"), Duration.ofMinutes(5));
		HttpTlsIdentity.IssuedClientCertificate issued = authority.enroll("lobby-1", code.enrollmentToken());
		assertTrue(authority.authenticate("lobby-1", issued.certificate()));
		assertTrue(identity.validClientCertificate("LOBBY-1", issued.certificate()));
		assertFalse(authority.authenticate("lobby-2", issued.certificate()));
		assertThrows(IllegalArgumentException.class, () -> authority.enroll("lobby-2", code.enrollmentToken()));
		authority.revoke("lobby-1");
		assertFalse(authority.authenticate("lobby-1", issued.certificate()));
		HttpConnectionCode replacementCode = authority.createConnectionCode("lobby-1", URI.create("https://localhost:8443/"), Duration.ofMinutes(5));
		HttpTlsIdentity.IssuedClientCertificate replacement = authority.enroll("lobby-1", replacementCode.enrollmentToken());
		assertTrue(authority.authenticate("lobby-1", replacement.certificate()));
		assertFalse(authority.authenticate("lobby-1", issued.certificate()));
		HttpClientCredentialStore.saveEnrolled(directory.resolve("client"), code, issued);
		HttpClientCredentialStore.ClientCredential restored = HttpClientCredentialStore.load(directory.resolve("client"));
		assertEquals(HttpTransportSecrets.certificatePin(issued.certificate()), HttpTransportSecrets.certificatePin(restored.certificate()));
		HttpClientCredentialStore.HttpClientProfile profile = HttpClientCredentialStore.loadProfile(directory.resolve("client"));
		assertEquals("lobby-1", profile.serverId());
		assertEquals(code.endpoint(), profile.endpoint());
		assertEquals("lobby-1", HttpClientCredentialStore.loadEnrolled(directory.resolve("client")).profile().serverId());
		assertNotEquals(null, HttpPinnedTls.mutualTlsContext(code, restored));
		Path clientDirectory = directory.resolve("client");
		String generation = Files.readString(clientDirectory.resolve("http-transport-client-current"));
		Files.writeString(clientDirectory.resolve("http-transport-client-generations").resolve(generation)
				.resolve("http-transport-profile.properties"), "version=1\nserverId=lobby-1\n");
		assertThrows(java.io.IOException.class, () -> HttpClientCredentialStore.loadProfile(directory.resolve("client")));
		HttpEnrollmentAuthority durable = new HttpEnrollmentAuthority(identity, directory.resolve("state"));
		HttpConnectionCode durableCode = durable.createConnectionCode("survival", URI.create("https://localhost:8443/"), Duration.ofMinutes(5));
		HttpTlsIdentity.IssuedClientCertificate durableIssued = durable.enroll("survival", durableCode.enrollmentToken());
		assertTrue(new HttpEnrollmentAuthority(identity, directory.resolve("state")).authenticate("survival", durableIssued.certificate()));
		durable.revoke("survival");
		assertFalse(new HttpEnrollmentAuthority(identity, directory.resolve("state")).authenticate("survival", durableIssued.certificate()));
	}

	@Test
	void revocationInvalidatesEveryPendingCodeForTheBackend() throws Exception {
		HttpTlsIdentity identity = HttpTlsIdentity.loadOrCreate(directory.resolve("revoke-pending"), "localhost");
		HttpEnrollmentAuthority authority = new HttpEnrollmentAuthority(identity,
				Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC));
		URI endpoint = URI.create("https://localhost:8443/");
		HttpConnectionCode beforeEnrollment = authority.createConnectionCode("lobby-1", endpoint, Duration.ofMinutes(5));
		authority.revoke("LOBBY-1");
		assertThrows(IllegalArgumentException.class,
				() -> authority.enroll("lobby-1", beforeEnrollment.enrollmentToken()));

		HttpConnectionCode active = authority.createConnectionCode("lobby-1", endpoint, Duration.ofMinutes(5));
		authority.enroll("lobby-1", active.enrollmentToken());
		HttpConnectionCode firstPending = authority.createConnectionCode("lobby-1", endpoint, Duration.ofMinutes(5));
		HttpConnectionCode secondPending = authority.createConnectionCode("lobby-1", endpoint, Duration.ofMinutes(5));
		authority.revoke("lobby-1");
		assertThrows(IllegalArgumentException.class,
				() -> authority.enroll("lobby-1", firstPending.enrollmentToken()));
		assertThrows(IllegalArgumentException.class,
				() -> authority.enroll("lobby-1", secondPending.enrollmentToken()));
	}

	@Test
	void failedRevocationPersistenceCanBeRetriedWithoutLosingState() throws Exception {
		HttpTlsIdentity identity = HttpTlsIdentity.loadOrCreate(directory.resolve("retry-revoke-proxy"), "localhost");
		Path stateDirectory = directory.resolve("retry-revoke-state");
		HttpEnrollmentAuthority authority = new HttpEnrollmentAuthority(identity, stateDirectory);
		URI endpoint = URI.create("https://localhost:8443/");
		HttpConnectionCode active = authority.createConnectionCode("lobby-1", endpoint, Duration.ofMinutes(5));
		HttpTlsIdentity.IssuedClientCertificate issued = authority.enroll("lobby-1", active.enrollmentToken());
		HttpConnectionCode pending = authority.createConnectionCode("lobby-1", endpoint, Duration.ofMinutes(5));
		HttpConnectionCode unaffected = authority.createConnectionCode("lobby-2", endpoint, Duration.ofMinutes(5));
		HttpTlsIdentity.IssuedClientCertificate unaffectedIssued = authority.enroll("lobby-2", unaffected.enrollmentToken());
		Path stateFile = stateDirectory.resolve("http-transport-clients.properties");
		Files.delete(stateFile);
		Files.createDirectory(stateFile);

		assertThrows(IllegalStateException.class, () -> authority.revoke("lobby-1"));
		assertFalse(authority.authenticate("lobby-1", issued.certificate()),
				"an unpersisted revocation must fail authentication closed");
		assertFalse(authority.authenticate("lobby-2", unaffectedIssued.certificate()),
				"all authentication must fail closed while persistence is unresolved");
		Files.delete(stateFile);
		authority.revoke("lobby-1");
		assertTrue(authority.authenticate("lobby-2", unaffectedIssued.certificate()),
				"a successful full-state retry must restore authentication availability");

		HttpEnrollmentAuthority restarted = new HttpEnrollmentAuthority(identity, stateDirectory);
		assertFalse(restarted.authenticate("lobby-1", issued.certificate()));
		assertThrows(IllegalArgumentException.class,
				() -> restarted.enroll("lobby-1", pending.enrollmentToken()));
	}

	@Test
	void pendingEnrollmentSurvivesRestartAndRevocationRemainsDurable() throws Exception {
		HttpTlsIdentity identity = HttpTlsIdentity.loadOrCreate(directory.resolve("pending-restart-proxy"), "localhost");
		Path state = directory.resolve("pending-restart-state");
		HttpEnrollmentAuthority authority = new HttpEnrollmentAuthority(identity, state);
		HttpConnectionCode code = authority.createConnectionCode("lobby-1",
				URI.create("https://localhost:8443/"), Duration.ofMinutes(5));
		assertFalse(Files.readString(state.resolve("http-transport-clients.properties"))
				.contains(code.enrollmentToken()), "raw enrollment tokens must never be persisted");

		HttpEnrollmentAuthority restarted = new HttpEnrollmentAuthority(identity, state);
		HttpTlsIdentity.IssuedClientCertificate issued = restarted.enroll("lobby-1", code.enrollmentToken());
		assertTrue(restarted.authenticate("lobby-1", issued.certificate()));

		restarted.revoke("lobby-1");
		HttpConnectionCode revokedPending = restarted.createConnectionCode("lobby-1",
				URI.create("https://localhost:8443/"), Duration.ofMinutes(5));
		restarted.revoke("lobby-1");
		HttpEnrollmentAuthority afterRevocation = new HttpEnrollmentAuthority(identity, state);
		assertThrows(IllegalArgumentException.class,
				() -> afterRevocation.enroll("lobby-1", revokedPending.enrollmentToken()));
	}

	@Test
	void lostEnrollmentResponseCanRetryUntilCertificatePossessionIsProved() throws Exception {
		HttpTlsIdentity identity = HttpTlsIdentity.loadOrCreate(directory.resolve("retry-enrollment-proxy"), "localhost");
		Path state = directory.resolve("retry-enrollment-state");
		HttpEnrollmentAuthority authority = new HttpEnrollmentAuthority(identity, state);
		HttpConnectionCode code = authority.createConnectionCode("lobby-1",
				URI.create("https://localhost:8443/"), Duration.ofMinutes(5));
		HttpTlsIdentity.IssuedClientCertificate lost = authority.enroll("lobby-1", code.enrollmentToken());

		HttpEnrollmentAuthority restarted = new HttpEnrollmentAuthority(identity, state);
		HttpTlsIdentity.IssuedClientCertificate retried = restarted.enroll("lobby-1", code.enrollmentToken());
		HttpEnrollmentAuthority beforeProof = new HttpEnrollmentAuthority(identity, state);
		assertFalse(beforeProof.authenticate("lobby-1", lost.certificate()),
				"retrying enrollment must supersede the certificate from the lost response");
		assertTrue(beforeProof.authenticate("lobby-1", retried.certificate()),
				"the first authenticated request must promote the certificate durably");

		HttpEnrollmentAuthority afterProof = new HttpEnrollmentAuthority(identity, state);
		assertTrue(afterProof.authenticate("lobby-1", retried.certificate()));
		assertThrows(IllegalArgumentException.class,
				() -> afterProof.enroll("lobby-1", code.enrollmentToken()),
				"proof of possession must consume the one-time enrollment token");
	}

	@Test
	void failedPendingCertificateWriteLeavesEnrollmentTokenRetryable() throws Exception {
		HttpTlsIdentity identity = HttpTlsIdentity.loadOrCreate(directory.resolve("failed-enrollment-proxy"), "localhost");
		Path state = directory.resolve("failed-enrollment-state");
		HttpEnrollmentAuthority authority = new HttpEnrollmentAuthority(identity, state);
		HttpConnectionCode code = authority.createConnectionCode("lobby-1",
				URI.create("https://localhost:8443/"), Duration.ofMinutes(5));
		Path stateFile = state.resolve("http-transport-clients.properties");
		Files.delete(stateFile);
		Files.createDirectory(stateFile);

		assertThrows(java.io.IOException.class, () -> authority.enroll("lobby-1", code.enrollmentToken()));
		Files.delete(stateFile);
		HttpTlsIdentity.IssuedClientCertificate retried = authority.enroll("lobby-1", code.enrollmentToken());
		assertTrue(authority.authenticate("lobby-1", retried.certificate()));
	}

	@Test
	void authorityStatePrunesRevocationsAndBoundsActiveBindings() throws Exception {
		Path proxy = directory.resolve("bounded-proxy");
		Path state = directory.resolve("bounded-state");
		HttpTlsIdentity identity = HttpTlsIdentity.loadOrCreate(proxy, "localhost");
		Files.createDirectories(state);
		java.util.Properties properties = new java.util.Properties();
		properties.setProperty("version", "3");
		String firstServer = boundedServerId(0);
		for (int index = 0; index < 128; index++) {
			String serverId = boundedServerId(index);
			String encodedServer = java.util.Base64.getUrlEncoder().withoutPadding()
					.encodeToString(serverId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			properties.setProperty("binding." + encodedServer, pin('a') + ":-:0");
			byte[] hash = new byte[32];
			java.nio.ByteBuffer.wrap(hash).putInt(index);
			properties.setProperty("enrollment." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(hash),
					Instant.parse("2099-01-01T00:00:00Z").toEpochMilli() + ":" + java.util.Base64.getUrlEncoder()
							.withoutPadding().encodeToString(firstServer.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		}
		Path stateFile = state.resolve("http-transport-clients.properties");
		try (var output = Files.newOutputStream(stateFile)) { properties.store(output, "bounded authority state"); }
		assertTrue(Files.size(stateFile) < 65536, "the maximum supported state must fit the read bound");

		HttpEnrollmentAuthority authority = new HttpEnrollmentAuthority(identity, state);
		authority.revoke(firstServer);
		assertFalse(Files.readString(stateFile).contains("binding." + java.util.Base64.getUrlEncoder().withoutPadding()
				.encodeToString(firstServer.getBytes(java.nio.charset.StandardCharsets.UTF_8))),
				"revoked bindings must not accumulate in durable state");
		HttpEnrollmentAuthority restarted = new HttpEnrollmentAuthority(identity, state);
		HttpConnectionCode replacement = restarted.createConnectionCode("replacement", URI.create("https://localhost:8443/"),
				Duration.ofMinutes(5));
		restarted.enroll("replacement", replacement.enrollmentToken());
		HttpConnectionCode overflow = restarted.createConnectionCode("overflow", URI.create("https://localhost:8443/"),
				Duration.ofMinutes(5));
		assertThrows(IllegalStateException.class, () -> restarted.enroll("overflow", overflow.enrollmentToken()));
		restarted.revoke(boundedServerId(1));
		assertDoesNotThrow(() -> restarted.enroll("overflow", overflow.enrollmentToken()),
				"a capacity rejection must not consume the enrollment token");
		assertTrue(Files.size(stateFile) <= 65536);
		assertDoesNotThrow(() -> new HttpEnrollmentAuthority(identity, state));
	}

	@Test
	void renewalKeepsOldCredentialUntilReplacementAuthenticates() throws Exception {
		HttpTlsIdentity identity = HttpTlsIdentity.loadOrCreate(directory.resolve("proxy"), "localhost");
		HttpEnrollmentAuthority authority = new HttpEnrollmentAuthority(identity, directory.resolve("state"));
		HttpConnectionCode code = authority.createConnectionCode("lobby-1", URI.create("https://localhost:8443/"), Duration.ofMinutes(5));
		HttpTlsIdentity.IssuedClientCertificate original = authority.enroll("lobby-1", code.enrollmentToken());
		HttpTlsIdentity.IssuedClientCertificate replacement = authority.renew("lobby-1", original.certificate());

		assertTrue(authority.authenticate("lobby-1", original.certificate()), "lost renewal responses must leave the old credential usable");
		assertTrue(authority.authenticate("lobby-1", replacement.certificate()), "first replacement request promotes the pending binding");
		assertFalse(authority.authenticate("lobby-1", original.certificate()), "promotion revokes the superseded credential");
		assertTrue(new HttpEnrollmentAuthority(identity, directory.resolve("state"))
				.authenticate("lobby-1", replacement.certificate()), "promoted renewal must survive restart");
	}

	@Test
	void failedRenewalPersistenceRestoresTheActiveBindingAndCanRetry() throws Exception {
		HttpTlsIdentity identity = HttpTlsIdentity.loadOrCreate(directory.resolve("retry-renewal-proxy"), "localhost");
		Path stateDirectory = directory.resolve("retry-renewal-state");
		HttpEnrollmentAuthority authority = new HttpEnrollmentAuthority(identity, stateDirectory);
		HttpConnectionCode code = authority.createConnectionCode("lobby-1", URI.create("https://localhost:8443/"),
				Duration.ofMinutes(5));
		HttpTlsIdentity.IssuedClientCertificate original = authority.enroll("lobby-1", code.enrollmentToken());
		assertTrue(authority.authenticate("lobby-1", original.certificate()));
		Path stateFile = stateDirectory.resolve("http-transport-clients.properties");
		Files.delete(stateFile);
		Files.createDirectory(stateFile);

		assertThrows(java.io.IOException.class, () -> authority.renew("lobby-1", original.certificate()));
		assertTrue(authority.authenticate("lobby-1", original.certificate()),
				"a pre-publication renewal failure must leave the active credential usable");
		Files.delete(stateFile);
		HttpTlsIdentity.IssuedClientCertificate retried = authority.renew("lobby-1", original.certificate());
		assertTrue(authority.authenticate("lobby-1", retried.certificate()),
				"renewal must remain retryable after persistence recovers");
	}

	@Test
	void serverLeafRotatesInsideRenewalWindowAndPreservesAuthority() throws Exception {
		Instant now = Instant.now();
		HttpTlsIdentity original = HttpTlsIdentity.loadOrCreate(directory, "localhost",
				Clock.fixed(now.minus(Duration.ofDays(340)), ZoneOffset.UTC));
		String originalPin = HttpTransportSecrets.certificatePin(original.serverCertificate());
		HttpTlsIdentity renewed = HttpTlsIdentity.loadOrCreate(directory, "localhost", Clock.fixed(now, ZoneOffset.UTC));
		assertNotEquals(originalPin, renewed.serverCertificatePin());
		assertEquals(original.caCertificatePin(), renewed.caCertificatePin());
		assertFalse(HttpTlsIdentity.needsRenewal(renewed.serverCertificate(), Clock.fixed(now, ZoneOffset.UTC)));
	}

	@Test
	void runningPrivateCaRollsOverBeforeExpiryWithoutStrandingExistingClients() throws Exception {
		Instant now = Instant.now();
		Clock originalClock = Clock.fixed(now.minus(Duration.ofDays(9 * 365L + 30L)), ZoneOffset.UTC);
		HttpTlsIdentity original = HttpTlsIdentity.loadOrCreate(directory, "localhost", originalClock);
		X509Certificate originalCa = original.caCertificate();
		HttpTlsIdentity.IssuedClientCertificate existingClient = original.issueClientCertificate("lobby-1", now);
		Path client = directory.resolve("client");
		HttpConnectionCode oldCode = new HttpConnectionCode("lobby-1", URI.create("https://localhost:8443/"),
				HttpTransportSecrets.certificatePin(original.serverCertificate()), HttpTransportSecrets.certificatePin(originalCa),
				now.plusSeconds(60), "A".repeat(43));
		HttpClientCredentialStore.saveEnrolled(client, oldCode, existingClient);

		String renewedPin = original.caCertificatePin();
		assertNotEquals(HttpTransportSecrets.certificatePin(originalCa), renewedPin);
		assertEquals(originalCa.getPublicKey(), original.caCertificate().getPublicKey(),
				"certificate rollover keeps the private authority key so old and new trust anchors overlap");
		assertFalse(HttpTlsIdentity.needsCaRenewal(original.caCertificate(), Clock.fixed(now, ZoneOffset.UTC)));
		assertTrue(original.validClientCertificate("lobby-1", existingClient.certificate()));

		X509TrustManager oldClientTrust = Arrays.stream(HttpTlsIdentity.trustManagers(originalCa))
				.filter(X509TrustManager.class::isInstance).map(X509TrustManager.class::cast).findFirst().orElseThrow();
		assertDoesNotThrow(() -> oldClientTrust.checkServerTrusted(
				new X509Certificate[] { original.serverCertificate(), original.caCertificate() }, "ECDHE_ECDSA"));
		HttpClientCredentialStore.StagedCredential staged = HttpClientCredentialStore.stageReplacement(client,
				original.issueClientCertificate("lobby-1", now));
		assertEquals(HttpTransportSecrets.certificatePin(originalCa), HttpClientCredentialStore.loadProfile(client).caCertificatePin());
		assertEquals(renewedPin, staged.profile().caCertificatePin());
		HttpClientCredentialStore.activateReplacement(client, staged);
		assertEquals(renewedPin, HttpClientCredentialStore.loadProfile(client).caCertificatePin());
		assertEquals(renewedPin, HttpTlsIdentity.loadOrCreate(directory, "localhost").caCertificatePin(),
				"live CA rollover must survive restart");
	}

	@Test
	void activeTlsContextRotatesServerLeafInsideRenewalWindow() throws Exception {
		Instant now = Instant.now();
		HttpTlsIdentity identity = HttpTlsIdentity.loadOrCreate(directory, "localhost",
				Clock.fixed(now.minus(Duration.ofDays(340)), ZoneOffset.UTC));
		String expiringPin = HttpTransportSecrets.certificatePin(identity.serverCertificate());
		identity.serverContext();
		assertNotEquals(expiringPin, identity.serverCertificatePin());
		assertFalse(HttpTlsIdentity.needsRenewal(identity.serverCertificate(), Clock.systemUTC()));
	}

	@Test
	void serverTlsUsesPrivateCaTrustAndRejectsForeignClients() throws Exception {
		HttpTlsIdentity identity = HttpTlsIdentity.loadOrCreate(directory.resolve("proxy"), "localhost");
		HttpTlsIdentity foreign = HttpTlsIdentity.loadOrCreate(directory.resolve("foreign"), "localhost");
		X509TrustManager trust = Arrays.stream(HttpTlsIdentity.trustManagers(identity.caCertificate()))
				.filter(X509TrustManager.class::isInstance).map(X509TrustManager.class::cast).findFirst().orElseThrow();
		HttpTlsIdentity.IssuedClientCertificate accepted = identity.issueClientCertificate("lobby-1");
		HttpTlsIdentity.IssuedClientCertificate rejected = foreign.issueClientCertificate("lobby-1");
		assertDoesNotThrow(() -> trust.checkClientTrusted(
				new java.security.cert.X509Certificate[] { accepted.certificate(), identity.caCertificate() }, "EC"));
		assertThrows(java.security.cert.CertificateException.class, () -> trust.checkClientTrusted(
				new java.security.cert.X509Certificate[] { rejected.certificate(), foreign.caCertificate() }, "EC"));
	}

	@Test
	void stagedCredentialDoesNotReplaceActiveGenerationUntilAtomicActivation() throws Exception {
		HttpTlsIdentity identity = HttpTlsIdentity.loadOrCreate(directory.resolve("proxy"), "localhost");
		Path client = directory.resolve("client");
		HttpTlsIdentity.IssuedClientCertificate original = identity.issueClientCertificate("lobby-1");
		HttpConnectionCode code = new HttpConnectionCode("lobby-1", URI.create("https://localhost:8443/"),
				identity.serverCertificatePin(), identity.caCertificatePin(), Instant.now().plusSeconds(60), "A".repeat(43));
		HttpClientCredentialStore.saveEnrolled(client, code, original);
		String originalPin = HttpTransportSecrets.certificatePin(HttpClientCredentialStore.load(client).certificate());
		HttpTlsIdentity.IssuedClientCertificate replacement = identity.issueClientCertificate("lobby-1");
		HttpClientCredentialStore.StagedCredential staged = HttpClientCredentialStore.stageReplacement(client, replacement);
		assertEquals(originalPin, HttpTransportSecrets.certificatePin(HttpClientCredentialStore.load(client).certificate()));
		HttpClientCredentialStore.activateReplacement(client, staged);
		assertNotEquals(originalPin, HttpTransportSecrets.certificatePin(HttpClientCredentialStore.load(client).certificate()));
		assertTrue(HttpClientCredentialStore.matchesEnrollmentCode(client, code),
				"automatic certificate renewal must retain the consumed-code marker");
		HttpTlsIdentity.IssuedClientCertificate manuallyReenrolled = identity.issueClientCertificate("lobby-1");
		HttpClientCredentialStore.saveEnrolled(client, code, manuallyReenrolled);
		assertEquals(HttpTransportSecrets.certificatePin(manuallyReenrolled.certificate()),
				HttpTransportSecrets.certificatePin(HttpClientCredentialStore.loadEnrolled(client).credential().certificate()));
	}

	@Test
	void restoresCredentialGenerationAfterFailedReenrollment() throws Exception {
		Path client = directory.resolve("client-rollback");
		HttpTlsIdentity oldIdentity = HttpTlsIdentity.loadOrCreate(directory.resolve("old-proxy"), "old.example.test");
		HttpConnectionCode oldCode = new HttpConnectionCode("lobby-1", URI.create("https://old.example.test:1297/"),
				oldIdentity.serverCertificatePin(), oldIdentity.caCertificatePin(), Instant.now().plusSeconds(60),
				"R".repeat(43));
		HttpClientCredentialStore.saveEnrolled(client, oldCode, oldIdentity.issueClientCertificate("lobby-1"));
		HttpClientCredentialStore.ActiveCredentialGeneration previous =
				HttpClientCredentialStore.snapshotActiveGeneration(client);

		HttpTlsIdentity replacementIdentity = HttpTlsIdentity.loadOrCreate(directory.resolve("new-proxy"), "new.example.test");
		HttpConnectionCode replacementCode = new HttpConnectionCode("lobby-1", URI.create("https://new.example.test:1297/"),
				replacementIdentity.serverCertificatePin(), replacementIdentity.caCertificatePin(),
				Instant.now().plusSeconds(60), "S".repeat(43));
		HttpClientCredentialStore.saveEnrolled(client, replacementCode,
				replacementIdentity.issueClientCertificate("lobby-1"));
		assertEquals(replacementCode.endpoint(), HttpClientCredentialStore.loadProfile(client).endpoint());

		HttpClientCredentialStore.restoreActiveGeneration(client, previous);
		assertEquals(oldCode.endpoint(), HttpClientCredentialStore.loadProfile(client).endpoint());
		assertTrue(HttpClientCredentialStore.matchesEnrollmentCode(client, oldCode));
	}

	@Test
	void rollbackRetainsNewerCredentialForTheSameEndpoint() throws Exception {
		Path client = directory.resolve("client-renewal-rollback");
		HttpTlsIdentity identity = HttpTlsIdentity.loadOrCreate(directory.resolve("renewal-proxy"), "renew.example.test");
		HttpConnectionCode code = new HttpConnectionCode("lobby-1", URI.create("https://renew.example.test:1297/"),
				identity.serverCertificatePin(), identity.caCertificatePin(), Instant.now().plusSeconds(60),
				"T".repeat(43));
		HttpTlsIdentity.IssuedClientCertificate original = identity.issueClientCertificate("lobby-1");
		HttpClientCredentialStore.saveEnrolled(client, code, original);
		HttpClientCredentialStore.ActiveCredentialGeneration previous =
				HttpClientCredentialStore.snapshotActiveGeneration(client);

		HttpConnectionCode replacementCode = new HttpConnectionCode("lobby-1", code.endpoint(),
				identity.serverCertificatePin(), identity.caCertificatePin(), Instant.now().plusSeconds(60),
				"U".repeat(43));
		HttpTlsIdentity.IssuedClientCertificate renewed = identity.issueClientCertificate("lobby-1");
		HttpClientCredentialStore.saveEnrolled(client, replacementCode, renewed);
		HttpClientCredentialStore.restoreActiveGenerationAfterReplacement(client, previous);

		assertEquals(HttpTransportSecrets.certificatePin(renewed.certificate()),
				HttpTransportSecrets.certificatePin(HttpClientCredentialStore.load(client).certificate()),
				"rollback must not reactivate a same-endpoint certificate that renewal may have revoked");
		assertTrue(HttpClientCredentialStore.matchesEnrollmentCode(client, code),
				"the retained credential must recognize the connection code restored in YAML");
	}

	private static String pin(char character) { return String.valueOf(character).repeat(64); }
	private static String boundedServerId(int index) { return String.format("s%03d", index) + "x".repeat(60); }
}
