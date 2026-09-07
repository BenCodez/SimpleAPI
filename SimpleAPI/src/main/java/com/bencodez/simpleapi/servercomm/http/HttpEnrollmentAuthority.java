package com.bencodez.simpleapi.servercomm.http;

import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.Base64;
import com.bencodez.simpleapi.file.DurableFiles;
import com.bencodez.simpleapi.file.PrivateFilePermissions;

/**
 * Single-activation enrollment tokens and client-certificate binding. A token may retry certificate
 * issuance until possession is proved, but can activate only one binding. Token material is never
 * retained; only SHA-256 hashes are kept until expiry. This type is thread-safe.
 */
public final class HttpEnrollmentAuthority {
	private static final Object PROCESS_STATE_LOCK = new Object();
	private static final String STATE_LOCK_FILE = ".http-transport-authority.lock";
	private static final Duration MAX_ENROLLMENT_LIFETIME = Duration.ofMinutes(15);
	private static final Duration MIN_RENEWAL_INTERVAL = Duration.ofMinutes(1);
	private static final int MAX_PENDING_ENROLLMENTS = 128;
	private static final int MAX_BINDINGS = 128;
	private static final int MAX_REVOCATION_MARKERS = MAX_BINDINGS + MAX_PENDING_ENROLLMENTS;
	private static final int LEGACY_MAX_REVOCATION_FINGERPRINTS_PER_SERVER = 4;
	private static final long MAX_STATE_BYTES = 65536;
	private final HttpTlsIdentity identity;
	private final Clock clock;
	private final Path stateFile;
	private final Map<String, Enrollment> enrollments = new HashMap<>();
	private final Map<String, ClientBinding> bindings = new HashMap<>();
	private final Map<String, Instant> renewalNotBefore = new HashMap<>();
	private final Map<String, Set<String>> revocationMarkers = new HashMap<>();
	private final Map<String, Long> revocationGenerations = new HashMap<>();
	private boolean persistenceFailure;
	private boolean rollbackStateAvailable;
	private boolean revocationRetryRequired;
	private String revocationRetryServerId;
	private String revocationRetryFingerprint;
	private long revocationRetryGeneration;

	/** Creates a restart-safe authority. State contains public certificate pins plus bounded hashes of pending tokens. */
	public HttpEnrollmentAuthority(HttpTlsIdentity identity, Path stateDirectory) throws java.io.IOException {
		this(identity, Clock.systemUTC(), stateFile(stateDirectory));
		withStateLock(() -> null);
	}

	HttpEnrollmentAuthority(HttpTlsIdentity identity, Clock clock) {
		this(identity, clock, null);
	}

	HttpEnrollmentAuthority(HttpTlsIdentity identity, Clock clock, Path stateFile) {
		if (identity == null || clock == null) throw new IllegalArgumentException("Identity and clock are required");
		this.identity = identity;
		this.clock = clock;
		this.stateFile = stateFile;
	}

	public synchronized HttpConnectionCode createConnectionCode(String serverId, URI endpoint, Duration lifetime) {
		try { return withMutationLock(() -> createConnectionCodeLocked(serverId, endpoint, lifetime)); }
		catch (java.io.IOException failure) { throw new IllegalStateException("Could not read HTTP enrollment state", failure); }
	}

	private HttpConnectionCode createConnectionCodeLocked(String serverId, URI endpoint, Duration lifetime) {
		if (revocationRetryRequired)
			throw new IllegalStateException("HTTP certificate revocation durability must be retried");
		serverId = HttpTlsIdentity.canonicalServerId(serverId);
		if (lifetime == null || lifetime.compareTo(Duration.ofSeconds(1)) < 0 || lifetime.compareTo(MAX_ENROLLMENT_LIFETIME) > 0)
			throw new IllegalArgumentException("Enrollment lifetime must be between one second and fifteen minutes");
		Instant now = clock.instant();
		Instant expiresAt = now.plus(lifetime);
		if (expiresAt.getNano() != 0) {
			expiresAt = expiresAt.plusSeconds(1).truncatedTo(ChronoUnit.SECONDS);
			Instant maximumExpiry = now.plus(MAX_ENROLLMENT_LIFETIME).truncatedTo(ChronoUnit.SECONDS);
			if (expiresAt.isAfter(maximumExpiry)) expiresAt = maximumExpiry;
		}
		String token = HttpTransportSecrets.randomToken();
		// Validate the complete code before reserving or persisting a pending slot.
		HttpConnectionCode code = new HttpConnectionCode(serverId, endpoint, identity.serverCertificatePin(),
				identity.caCertificatePin(), expiresAt, token);
		expireEnrollments();
		if (enrollments.size() >= MAX_PENDING_ENROLLMENTS)
			throw new IllegalStateException("Too many pending HTTP enrollments");
		byte[] tokenHash = HttpTransportSecrets.sha256(token.getBytes(StandardCharsets.US_ASCII));
		String lookup = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(tokenHash);
		enrollments.put(lookup, new Enrollment(tokenHash, expiresAt, serverId, null));
		try { persistState(); persistenceFailure = false; rollbackStateAvailable = false; }
		catch (java.io.IOException failure) {
			enrollments.remove(lookup);
			rollbackStateAvailable = !(failure instanceof DurableFiles.PublishedException);
			throw new IllegalStateException("Could not persist HTTP enrollment", failure);
		}
		return code;
	}

	public synchronized HttpTlsIdentity.IssuedClientCertificate enroll(String serverId, String enrollmentToken) throws Exception {
		return withMutationLock(() -> enrollLocked(serverId, enrollmentToken));
	}

	private HttpTlsIdentity.IssuedClientCertificate enrollLocked(String serverId, String enrollmentToken) throws Exception {
		// A failed revoke may have restored a still-valid pending token in memory.
		// Do not let enrollment persist that stale state before the revoke is retried.
		if (revocationRetryRequired)
			throw new java.io.IOException("HTTP certificate revocation durability must be retried");
		serverId = HttpTlsIdentity.canonicalServerId(serverId);
		if (enrollmentToken == null || enrollmentToken.length() > 128) throw new IllegalArgumentException("Enrollment was rejected");
		expireEnrollments();
		byte[] suppliedHash = HttpTransportSecrets.sha256(enrollmentToken.getBytes(StandardCharsets.US_ASCII));
		String lookup = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(suppliedHash);
		Enrollment enrollment = enrollments.get(lookup);
		if (enrollment == null || !HttpTransportSecrets.constantTimeEquals(enrollment.tokenHash(), suppliedHash))
			throw new IllegalArgumentException("Enrollment was rejected");
		if (!serverId.equals(enrollment.serverId())) throw new IllegalArgumentException("Enrollment was rejected");
		ClientBinding existing = bindings.get(serverId);
		if (existing != null && !existing.revoked()) throw new IllegalStateException("Server id is already enrolled");
		if (existing == null && !hasPendingCertificate(serverId) && reservedBindingCount() >= MAX_BINDINGS)
			throw new IllegalStateException("Too many enrolled HTTP backends");
		HttpTlsIdentity.IssuedClientCertificate issued = identity.issueClientCertificate(serverId);
		enrollments.put(lookup, new Enrollment(enrollment.tokenHash(), enrollment.expiresAt(), serverId,
				HttpTransportSecrets.certificatePin(issued.certificate())));
		try { persistState(); persistenceFailure = false; rollbackStateAvailable = false; }
		catch (java.io.IOException failure) {
			if (!(failure instanceof DurableFiles.PublishedException)) {
				enrollments.put(lookup, enrollment);
				rollbackStateAvailable = true;
			}
			else { persistenceFailure = true; rollbackStateAvailable = false; }
			throw failure;
		}
		return issued;
	}

	public synchronized boolean authenticate(String serverId, java.security.cert.X509Certificate certificate) {
		try { return withStateLock(() -> authenticateLocked(serverId, certificate), rollbackStateAvailable); }
		catch (java.io.IOException failure) { return false; }
	}

	private boolean authenticateLocked(String serverId, java.security.cert.X509Certificate certificate) {
		if (persistenceFailure || serverId == null || certificate == null) return false;
		try { serverId = HttpTlsIdentity.canonicalServerId(serverId); }
		catch (IllegalArgumentException invalid) { return false; }
		if (!identity.validClientCertificate(serverId, certificate)) return false;
		ClientBinding binding = bindings.get(serverId);
		String pin = HttpTransportSecrets.certificatePin(certificate);
		if (binding != null && !binding.revoked()) {
			if (samePin(binding.certificatePin(), pin)) return true;
			if (!samePin(binding.pendingCertificatePin(), pin)) return false;
			bindings.put(serverId, new ClientBinding(pin, null, false));
			try { persistState(); rollbackStateAvailable = false; return true; }
			catch (DurableFiles.PublishedException published) {
				rollbackStateAvailable = false;
				// The replacement is visible. If publication is lost on a crash, the
				// previous durable pending binding can promote this certificate again.
				return true;
			}
			catch (java.io.IOException failure) { bindings.put(serverId, binding); rollbackStateAvailable = true; return false; }
		}
		Map.Entry<String, Enrollment> pending = pendingCertificate(serverId, pin);
		if (pending == null || !pending.getValue().expiresAt().isAfter(clock.instant())
				|| bindings.size() >= MAX_BINDINGS) return false;
		enrollments.remove(pending.getKey());
		bindings.put(serverId, new ClientBinding(pin, null, false));
		try { persistState(); rollbackStateAvailable = false; return true; }
		catch (DurableFiles.PublishedException published) {
			rollbackStateAvailable = false;
			// The visible state is active. If its directory entry is lost on a crash, the
			// prior pending state can promote this same certificate again after restart.
			return true;
		}
		catch (java.io.IOException failure) {
			bindings.remove(serverId);
			enrollments.put(pending.getKey(), pending.getValue());
			rollbackStateAvailable = true;
			return false;
		}
	}

	/** Issues a replacement while the currently bound certificate is still valid. The old binding remains active
	 * until the replacement successfully authenticates, making a lost renewal response safe to retry. */
	public synchronized HttpTlsIdentity.IssuedClientCertificate renew(String serverId,
			java.security.cert.X509Certificate currentCertificate) throws Exception {
		return withMutationLock(() -> renewLocked(serverId, currentCertificate));
	}

	private HttpTlsIdentity.IssuedClientCertificate renewLocked(String serverId,
			java.security.cert.X509Certificate currentCertificate) throws Exception {
		serverId = HttpTlsIdentity.canonicalServerId(serverId);
		Instant now = clock.instant();
		Instant nextAllowed = renewalNotBefore.get(serverId);
		if (nextAllowed != null && now.isBefore(nextAllowed)) throw new RenewalRateLimitException();
		if (!authenticateLocked(serverId, currentCertificate)) throw new IllegalArgumentException("Certificate renewal was rejected");
		// Persist the limiter before certificate generation so failed issuance still consumes
		// the same window across every authority sharing this state.
		renewalNotBefore.keySet().retainAll(bindings.keySet());
		Instant previousRenewalNotBefore = renewalNotBefore.put(serverId, now.plus(MIN_RENEWAL_INTERVAL));
		try { persistState(); rollbackStateAvailable = false; }
		catch (DurableFiles.PublishedException published) { rollbackStateAvailable = false; throw published; }
		catch (java.io.IOException failure) {
			if (previousRenewalNotBefore == null) renewalNotBefore.remove(serverId);
			else renewalNotBefore.put(serverId, previousRenewalNotBefore);
			rollbackStateAvailable = true;
			throw failure;
		}
		ClientBinding binding = bindings.get(serverId);
		HttpTlsIdentity.IssuedClientCertificate issued = identity.issueClientCertificate(serverId);
		bindings.put(serverId, new ClientBinding(binding.certificatePin(),
				HttpTransportSecrets.certificatePin(issued.certificate()), false));
		try { persistState(); rollbackStateAvailable = false; }
		catch (DurableFiles.PublishedException published) {
			rollbackStateAvailable = false;
			// The old certificate remains active in both the old and newly visible state,
			// so a lost response can safely retry and republish the complete state.
			throw published;
		}
		catch (java.io.IOException failure) { bindings.put(serverId, binding); rollbackStateAvailable = true; throw failure; }
		return issued;
	}

	static final class RenewalRateLimitException extends IllegalStateException {
		private static final long serialVersionUID = 1L;
		RenewalRateLimitException() { super("HTTP certificate renewal is rate limited"); }
	}

	public synchronized void revoke(String serverId) {
		try { withMutationLock(() -> { revokeLocked(serverId); return null; }); }
		catch (java.io.IOException failure) { throw new IllegalStateException("Could not read HTTP enrollment state", failure); }
	}

	private void revokeLocked(String serverId) {
		try { serverId = HttpTlsIdentity.canonicalServerId(serverId); }
		catch (IllegalArgumentException invalid) { return; }
		if (revocationRetryRequired && !serverId.equals(revocationRetryServerId))
			throw new IllegalStateException("A different HTTP certificate revocation must be retried first");
		final String revokedServer = serverId;
		Map<String, Enrollment> removedEnrollments = new HashMap<>();
		enrollments.entrySet().removeIf(entry -> {
			if (!revokedServer.equals(entry.getValue().serverId())) return false;
			removedEnrollments.put(entry.getKey(), entry.getValue());
			return true;
		});
		// Absence is the durable revocation fence: authentication always requires an exact active binding.
		ClientBinding removedBinding = bindings.remove(serverId);
		Instant removedRenewalNotBefore = renewalNotBefore.remove(serverId);
		String revocationFingerprint = revocationFingerprint(serverId, removedBinding,
				removedEnrollments, removedRenewalNotBefore);
		long previousGeneration = revocationGenerations.getOrDefault(serverId, 0L);
		if (previousGeneration == Long.MAX_VALUE)
			throw new IllegalStateException("HTTP certificate revocation generation exhausted");
		long revocationGeneration = previousGeneration + 1L;
		Map<String, Set<String>> previousRevocationMarkers = copyRevocationMarkers();
		Map<String, Long> previousRevocationGenerations = new HashMap<>(revocationGenerations);
		if (removedBinding != null || !removedEnrollments.isEmpty() || removedRenewalNotBefore != null
				|| revocationRetryRequired) try {
			Set<String> fingerprints = revocationMarkers.computeIfAbsent(serverId, ignored -> new LinkedHashSet<>());
			fingerprints.clear();
			fingerprints.add(revocationFingerprint);
			revocationGenerations.put(serverId, revocationGeneration);
			trimRevocationMarkers(serverId);
			persistState();
			persistenceFailure = false;
			rollbackStateAvailable = false;
			revocationRetryRequired = false;
			revocationRetryServerId = null;
			revocationRetryFingerprint = null;
		}
		catch (java.io.IOException failure) {
			// Before publication, restore the exact disk-backed state so a retry still has work to persist.
			// After publication, retain the fail-closed new state and let a retry force it durably again.
			if (!(failure instanceof DurableFiles.PublishedException)) {
				if (removedBinding != null) bindings.put(serverId, removedBinding);
				enrollments.putAll(removedEnrollments);
				if (removedRenewalNotBefore != null) renewalNotBefore.put(serverId, removedRenewalNotBefore);
				revocationMarkers.clear();
				revocationMarkers.putAll(previousRevocationMarkers);
				revocationGenerations.clear();
				revocationGenerations.putAll(previousRevocationGenerations);
				rollbackStateAvailable = true;
			}
			persistenceFailure = true;
			if (failure instanceof DurableFiles.PublishedException) rollbackStateAvailable = false;
			revocationRetryRequired = true;
			revocationRetryServerId = serverId;
			revocationRetryFingerprint = revocationFingerprint;
			revocationRetryGeneration = revocationGeneration;
			throw new IllegalStateException("Could not persist HTTP certificate revocation", failure);
		}
	}

	@FunctionalInterface
	private interface StateOperation<T, E extends Exception> { T run() throws E; }

	private <T, E extends Exception> T withMutationLock(StateOperation<T, E> operation) throws java.io.IOException, E {
		return withStateLock(operation, true);
	}

	/** Serializes every durable-state operation across both authority instances and processes, then
	 * adopts the latest complete file before making a decision or rewriting it. */
	private <T, E extends Exception> T withStateLock(StateOperation<T, E> operation) throws java.io.IOException, E {
		return withStateLock(operation, false);
	}

	private <T, E extends Exception> T withStateLock(StateOperation<T, E> operation,
			boolean allowDirectoryFailure) throws java.io.IOException, E {
		if (stateFile == null) return operation.run();
		synchronized (PROCESS_STATE_LOCK) {
			Path sidecar = stateFile.resolveSibling(STATE_LOCK_FILE);
			if (Files.isSymbolicLink(sidecar) || Files.exists(sidecar, LinkOption.NOFOLLOW_LINKS)
					&& !Files.isRegularFile(sidecar, LinkOption.NOFOLLOW_LINKS))
				throw new java.io.IOException("HTTP enrollment state lock is unsafe");
			try (FileChannel channel = FileChannel.open(sidecar, StandardOpenOption.CREATE,
					StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
				PrivateFilePermissions.ownerOnlyFile(sidecar);
				try (FileLock ignored = lock(channel)) {
					refreshState(allowDirectoryFailure);
					return operation.run();
				}
			}
		}
	}

	private static FileLock lock(FileChannel channel) throws java.io.IOException {
		try { return channel.lock(); }
		catch (OverlappingFileLockException alreadyOwned) {
			throw new java.io.IOException("HTTP enrollment state is already being updated", alreadyOwned);
		}
	}

	private void refreshState(boolean allowDirectoryFailure) throws java.io.IOException {
		// A pre-publication failure may temporarily leave no state file while this instance
		// retains the exact rollback state needed for a retry. Any successful peer mutation
		// recreates the file under this same lock and will therefore be adopted here.
		if (!Files.exists(stateFile, LinkOption.NOFOLLOW_LINKS)) return;
		// Tests and recovery callers may expose a failed replacement as a directory at
		// the target path. Mutations must reach persistState() so its existing rollback
		// and fail-closed retry semantics run; reads still reject the invalid state.
		if (allowDirectoryFailure && Files.isDirectory(stateFile, LinkOption.NOFOLLOW_LINKS)) return;
		Map<String, Enrollment> previousEnrollments = new HashMap<>(enrollments);
		Map<String, ClientBinding> previousBindings = new HashMap<>(bindings);
		Map<String, Instant> previousRenewalNotBefore = new HashMap<>(renewalNotBefore);
		Map<String, Set<String>> previousRevocationMarkers = copyRevocationMarkers();
		Map<String, Long> previousRevocationGenerations = new HashMap<>(revocationGenerations);
		enrollments.clear();
		bindings.clear();
		renewalNotBefore.clear();
		revocationMarkers.clear();
		revocationGenerations.clear();
		try { loadState(); }
		catch (java.io.IOException failure) {
			enrollments.putAll(previousEnrollments);
			bindings.putAll(previousBindings);
			renewalNotBefore.putAll(previousRenewalNotBefore);
			revocationMarkers.putAll(previousRevocationMarkers);
			revocationGenerations.putAll(previousRevocationGenerations);
			throw failure;
		}
		boolean peerCompletedRevocation = persistenceFailure && rollbackStateAvailable
				&& revocationRetryRequired && revocationReflectedInState();
		if (persistenceFailure && (!rollbackStateAvailable || peerCompletedRevocation)) {
			// A published replacement is visible but remains fail-closed until its
			// directory entry is forced successfully. Reconciliation under the state
			// lock makes that publication durable even when no mutation is required.
			// A pre-publication revocation failure can also be cleared when the loaded
			// durable state proves that a peer completed that exact revocation.
			DurableFiles.forceDirectory(stateFile.getParent());
			persistenceFailure = false;
			rollbackStateAvailable = false;
			revocationRetryRequired = false;
			revocationRetryServerId = null;
			revocationRetryFingerprint = null;
			revocationRetryGeneration = 0L;
		}
	}

	private boolean revocationReflectedInState() {
		if (revocationRetryServerId == null) return false;
		if (revocationRetryGeneration > 0L
				&& revocationGenerations.getOrDefault(revocationRetryServerId, 0L) >= revocationRetryGeneration)
			return true;
		if (bindings.containsKey(revocationRetryServerId)
				|| renewalNotBefore.containsKey(revocationRetryServerId)) return false;
		return enrollments.values().stream()
				.noneMatch(enrollment -> revocationRetryServerId.equals(enrollment.serverId()));
	}

	private static String revocationFingerprint(String serverId, ClientBinding binding,
			Map<String, Enrollment> removedEnrollments, Instant renewal) {
		StringBuilder value = new StringBuilder();
		appendFingerprintPart(value, serverId);
		appendFingerprintPart(value, binding == null ? null : binding.certificatePin());
		appendFingerprintPart(value, binding == null ? null : binding.pendingCertificatePin());
		appendFingerprintPart(value, binding == null ? null : Boolean.toString(binding.revoked()));
		new java.util.TreeMap<>(removedEnrollments).forEach((lookup, enrollment) -> {
			appendFingerprintPart(value, lookup);
			appendFingerprintPart(value, Long.toString(enrollment.expiresAt().toEpochMilli()));
			appendFingerprintPart(value, enrollment.serverId());
			appendFingerprintPart(value, enrollment.pendingCertificatePin());
		});
		appendFingerprintPart(value, renewal == null ? null : Long.toString(renewal.toEpochMilli()));
		return HttpTransportSecrets.sha256Hex(value.toString().getBytes(StandardCharsets.UTF_8));
	}

	private static void appendFingerprintPart(StringBuilder output, String value) {
		if (value == null) output.append("-1:");
		else output.append(value.length()).append(':').append(value);
	}

	private void trimRevocationMarkers(String preservedServerId) throws java.io.IOException {
		var iterator = revocationMarkers.keySet().iterator();
		while (revocationMarkers.size() > MAX_REVOCATION_MARKERS && iterator.hasNext()) {
			String candidate = iterator.next();
			if (!candidate.equals(preservedServerId) && !serverStatePresent(candidate)) {
				iterator.remove();
				revocationGenerations.remove(candidate);
			}
		}
		if (revocationMarkers.size() > MAX_REVOCATION_MARKERS)
			throw new java.io.IOException("HTTP enrollment revocation history exceeds its bound");
	}

	private Map<String, Set<String>> copyRevocationMarkers() {
		Map<String, Set<String>> copy = new HashMap<>();
		revocationMarkers.forEach((server, fingerprints) -> copy.put(server, new LinkedHashSet<>(fingerprints)));
		return copy;
	}

	private boolean serverStatePresent(String serverId) {
		return bindings.containsKey(serverId) || renewalNotBefore.containsKey(serverId)
				|| enrollments.values().stream().anyMatch(enrollment -> serverId.equals(enrollment.serverId()));
	}

	private synchronized void loadState() throws java.io.IOException {
		if (stateFile == null || !Files.exists(stateFile, LinkOption.NOFOLLOW_LINKS)) return;
		if (!Files.isRegularFile(stateFile, LinkOption.NOFOLLOW_LINKS) || Files.size(stateFile) > MAX_STATE_BYTES)
			throw new java.io.IOException("HTTP enrollment state is invalid");
		PrivateFilePermissions.ownerOnlyFile(stateFile);
		Properties properties = new Properties();
		try (var input = Files.newInputStream(stateFile, LinkOption.NOFOLLOW_LINKS)) { properties.load(input); }
		String version = properties.getProperty("version");
		if (!("1".equals(version) || "2".equals(version) || "3".equals(version) || "4".equals(version)
				|| "5".equals(version) || "6".equals(version) || "7".equals(version) || "8".equals(version)))
			throw new java.io.IOException("HTTP enrollment state is invalid");
		for (String key : properties.stringPropertyNames()) {
			if (key.startsWith("binding.")) {
				String serverId = new String(Base64.getUrlDecoder().decode(key.substring("binding.".length())), StandardCharsets.UTF_8);
				serverId = HttpTlsIdentity.canonicalServerId(serverId);
				String[] value = properties.getProperty(key, "").split(":", -1);
				if (!((value.length == 2 && "1".equals(version))
						|| (value.length == 3 && ("2".equals(version) || "3".equals(version)
								|| "4".equals(version) || "5".equals(version) || "6".equals(version) || "7".equals(version) || "8".equals(version))))
						|| !value[0].matches("[0-9a-f]{64}"))
					throw new java.io.IOException("HTTP enrollment state is invalid");
				String pending = value.length == 3 && !"-".equals(value[1]) ? value[1] : null;
				String revoked = value[value.length - 1];
				if ((pending != null && !pending.matches("[0-9a-f]{64}")) || !("0".equals(revoked) || "1".equals(revoked)))
					throw new java.io.IOException("HTTP enrollment state is invalid");
				if ("0".equals(revoked)) {
					if (bindings.size() >= MAX_BINDINGS) throw new java.io.IOException("HTTP enrollment state exceeds its bound");
					bindings.put(serverId, new ClientBinding(value[0], pending, false));
				}
			} else if (key.startsWith("enrollment.")
								&& ("3".equals(version) || "4".equals(version) || "5".equals(version)
										|| "6".equals(version) || "7".equals(version) || "8".equals(version))) {
				String lookup = key.substring("enrollment.".length());
				if (!lookup.matches("[A-Za-z0-9_-]{43}")) throw new java.io.IOException("HTTP enrollment state is invalid");
				byte[] tokenHash;
				try { tokenHash = Base64.getUrlDecoder().decode(lookup); }
				catch (IllegalArgumentException invalid) { throw new java.io.IOException("HTTP enrollment state is invalid", invalid); }
				if (tokenHash.length != 32) throw new java.io.IOException("HTTP enrollment state is invalid");
				String[] value = properties.getProperty(key, "").split(":", -1);
				if (!(value.length == 2 && "3".equals(version))
								&& !(value.length == 3 && ("4".equals(version) || "5".equals(version)
										|| "6".equals(version) || "7".equals(version) || "8".equals(version))))
					throw new java.io.IOException("HTTP enrollment state is invalid");
				Instant expiresAt;
				String serverId;
				String pendingPin = value.length == 3 && !"-".equals(value[2]) ? value[2] : null;
				try {
					expiresAt = Instant.ofEpochMilli(Long.parseLong(value[0]));
					serverId = HttpTlsIdentity.canonicalServerId(new String(Base64.getUrlDecoder().decode(value[1]), StandardCharsets.UTF_8));
				} catch (RuntimeException invalid) { throw new java.io.IOException("HTTP enrollment state is invalid", invalid); }
				if (pendingPin != null && !pendingPin.matches("[0-9a-f]{64}"))
					throw new java.io.IOException("HTTP enrollment state is invalid");
				if (expiresAt.isAfter(clock.instant())) {
					if (enrollments.size() >= MAX_PENDING_ENROLLMENTS)
						throw new java.io.IOException("HTTP enrollment state exceeds its bound");
					enrollments.put(lookup, new Enrollment(tokenHash, expiresAt, serverId, pendingPin));
				}
			} else if (key.startsWith("renewal.") && ("5".equals(version) || "6".equals(version) || "7".equals(version) || "8".equals(version))) {
				String encodedServer = key.substring("renewal.".length());
				String serverId;
				Instant notBefore;
				try {
					serverId = HttpTlsIdentity.canonicalServerId(new String(
							Base64.getUrlDecoder().decode(encodedServer), StandardCharsets.UTF_8));
					String canonicalEncoding = Base64.getUrlEncoder().withoutPadding().encodeToString(
							serverId.getBytes(StandardCharsets.UTF_8));
					if (!canonicalEncoding.equals(encodedServer)) throw new IllegalArgumentException();
					String value = properties.getProperty(key, "");
					if (!value.matches("[0-9]{1,19}")) throw new IllegalArgumentException();
					notBefore = Instant.ofEpochMilli(Long.parseLong(value));
				} catch (RuntimeException invalid) {
					throw new java.io.IOException("HTTP enrollment state is invalid", invalid);
				}
				if (renewalNotBefore.size() >= MAX_BINDINGS
						|| renewalNotBefore.putIfAbsent(serverId, notBefore) != null)
					throw new java.io.IOException("HTTP enrollment state exceeds its bound");
			} else if (key.startsWith("revocation.") && ("6".equals(version) || "7".equals(version) || "8".equals(version))) {
				String encodedServer = key.substring("revocation.".length());
				String serverId;
				try {
					serverId = HttpTlsIdentity.canonicalServerId(new String(
							Base64.getUrlDecoder().decode(encodedServer), StandardCharsets.UTF_8));
					String canonicalEncoding = Base64.getUrlEncoder().withoutPadding().encodeToString(
							serverId.getBytes(StandardCharsets.UTF_8));
					if (!canonicalEncoding.equals(encodedServer)) throw new IllegalArgumentException();
				} catch (RuntimeException invalid) {
					throw new java.io.IOException("HTTP enrollment state is invalid", invalid);
				}
				String[] fingerprints = properties.getProperty(key, "").split(",", -1);
				if (fingerprints.length == 0
						|| "6".equals(version) && fingerprints.length != 1
						|| "7".equals(version) && fingerprints.length > LEGACY_MAX_REVOCATION_FINGERPRINTS_PER_SERVER
						|| "8".equals(version) && fingerprints.length != 1
						|| revocationMarkers.size() >= MAX_REVOCATION_MARKERS
						|| revocationMarkers.containsKey(serverId))
					throw new java.io.IOException("HTTP enrollment state exceeds its bound");
				Set<String> history = new LinkedHashSet<>();
				for (String fingerprint : fingerprints)
					if (!fingerprint.matches("[0-9a-f]{64}") || !history.add(fingerprint))
						throw new java.io.IOException("HTTP enrollment state is invalid");
				if ("7".equals(version) && history.size() > 1) {
					history.clear();
					history.add(fingerprints[fingerprints.length - 1]);
				}
				revocationMarkers.put(serverId, history);
				if ("6".equals(version) || "7".equals(version)) revocationGenerations.put(serverId, 1L);
			} else if (key.startsWith("revocationGeneration.") && "8".equals(version)) {
				String encodedServer = key.substring("revocationGeneration.".length());
				String serverId;
				try {
					serverId = HttpTlsIdentity.canonicalServerId(new String(Base64.getUrlDecoder().decode(encodedServer), StandardCharsets.UTF_8));
					String canonicalEncoding = Base64.getUrlEncoder().withoutPadding().encodeToString(serverId.getBytes(StandardCharsets.UTF_8));
					if (!canonicalEncoding.equals(encodedServer)) throw new IllegalArgumentException();
				} catch (RuntimeException invalid) { throw new java.io.IOException("HTTP enrollment state is invalid", invalid); }
				String value = properties.getProperty(key, "");
				try {
					long generation = Long.parseLong(value);
					if (generation <= 0L || revocationGenerations.size() >= MAX_REVOCATION_MARKERS
							|| revocationGenerations.putIfAbsent(serverId, generation) != null)
						throw new IllegalArgumentException();
				} catch (RuntimeException invalid) { throw new java.io.IOException("HTTP enrollment state is invalid", invalid); }
			} else if (!"version".equals(key)) throw new java.io.IOException("HTTP enrollment state is invalid");
		}
		Instant now = clock.instant();
		renewalNotBefore.entrySet().removeIf(entry -> !bindings.containsKey(entry.getKey())
				|| !entry.getValue().isAfter(now));
		if (reservedBindingCount() > MAX_BINDINGS)
			throw new java.io.IOException("HTTP enrollment state exceeds its bound");
		if (!revocationGenerations.keySet().equals(revocationMarkers.keySet()))
			throw new java.io.IOException("HTTP enrollment state is invalid");
	}

	private synchronized void persistState() throws java.io.IOException {
		if (stateFile == null) return;
		Instant now = clock.instant();
		renewalNotBefore.entrySet().removeIf(entry -> !bindings.containsKey(entry.getKey())
				|| !entry.getValue().isAfter(now));
		if (reservedBindingCount() > MAX_BINDINGS || enrollments.size() > MAX_PENDING_ENROLLMENTS
				|| revocationMarkers.size() > MAX_REVOCATION_MARKERS
				|| !revocationGenerations.keySet().equals(revocationMarkers.keySet())
				|| revocationMarkers.values().stream().anyMatch(history -> history.size() != 1)
				|| revocationGenerations.values().stream().anyMatch(generation -> generation == null || generation <= 0L))
			throw new java.io.IOException("HTTP enrollment state exceeds its bound");
		Properties properties = new Properties();
		properties.setProperty("version", "8");
		for (Map.Entry<String, ClientBinding> entry : bindings.entrySet()) {
			String key = Base64.getUrlEncoder().withoutPadding().encodeToString(entry.getKey().getBytes(StandardCharsets.UTF_8));
			properties.setProperty("binding." + key, entry.getValue().certificatePin() + ":"
					+ (entry.getValue().pendingCertificatePin() == null ? "-" : entry.getValue().pendingCertificatePin())
					+ ":" + (entry.getValue().revoked() ? "1" : "0"));
		}
		for (Map.Entry<String, Enrollment> entry : enrollments.entrySet()) {
			String server = Base64.getUrlEncoder().withoutPadding().encodeToString(
					entry.getValue().serverId().getBytes(StandardCharsets.UTF_8));
			properties.setProperty("enrollment." + entry.getKey(),
					entry.getValue().expiresAt().toEpochMilli() + ":" + server + ":"
							+ (entry.getValue().pendingCertificatePin() == null ? "-" : entry.getValue().pendingCertificatePin()));
		}
		for (Map.Entry<String, Instant> entry : renewalNotBefore.entrySet()) {
			String server = Base64.getUrlEncoder().withoutPadding().encodeToString(
					entry.getKey().getBytes(StandardCharsets.UTF_8));
			properties.setProperty("renewal." + server, Long.toString(entry.getValue().toEpochMilli()));
		}
		for (Map.Entry<String, Set<String>> entry : revocationMarkers.entrySet()) {
			String key = Base64.getUrlEncoder().withoutPadding().encodeToString(
					entry.getKey().getBytes(StandardCharsets.UTF_8));
			properties.setProperty("revocation." + key, String.join(",", entry.getValue()));
		}
		for (Map.Entry<String, Long> entry : revocationGenerations.entrySet()) {
			String key = Base64.getUrlEncoder().withoutPadding().encodeToString(
					entry.getKey().getBytes(StandardCharsets.UTF_8));
			properties.setProperty("revocationGeneration." + key, Long.toString(entry.getValue()));
		}
		java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
		properties.store(bytes, "VotingPlugin HTTP transport authority state");
		if (bytes.size() > MAX_STATE_BYTES) throw new java.io.IOException("HTTP enrollment state exceeds its byte bound");
		Path temporary = Files.createTempFile(stateFile.getParent(), stateFile.getFileName().toString(), ".tmp");
		try {
			PrivateFilePermissions.ownerOnlyFile(temporary);
			Files.write(temporary, bytes.toByteArray(), StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
			DurableFiles.forceFile(temporary);
			try { Files.move(temporary, stateFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
			catch (java.nio.file.AtomicMoveNotSupportedException unsupported) { Files.move(temporary, stateFile, StandardCopyOption.REPLACE_EXISTING); }
			try {
				PrivateFilePermissions.ownerOnlyFile(stateFile);
				DurableFiles.forceDirectory(stateFile.getParent());
			} catch (java.io.IOException postPublicationFailure) {
				throw new DurableFiles.PublishedException(postPublicationFailure);
			}
		} finally { Files.deleteIfExists(temporary); }
	}

	private static Path stateFile(Path directory) throws java.io.IOException {
		if (directory == null) throw new IllegalArgumentException("State directory is required");
		Path stateDirectory = directory.toAbsolutePath().normalize();
		Files.createDirectories(stateDirectory);
		if (Files.isSymbolicLink(stateDirectory) || !Files.isDirectory(stateDirectory, LinkOption.NOFOLLOW_LINKS))
			throw new java.io.IOException("HTTP enrollment state directory is unsafe");
		PrivateFilePermissions.ownerOnlyDirectory(stateDirectory);
		// Existing can mean a previous create succeeded but its parent fsync did not.
		DurableFiles.forceDirectory(stateDirectory.getParent());
		Path file = stateDirectory.resolve("http-transport-clients.properties");
		if (Files.isSymbolicLink(file)) throw new java.io.IOException("Refusing unsafe HTTP enrollment state path");
		return file;
	}

	private void expireEnrollments() {
		Instant now = clock.instant();
		enrollments.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
	}

	private int reservedBindingCount() {
		java.util.Set<String> reserved = new java.util.HashSet<>(bindings.keySet());
		for (Enrollment enrollment : enrollments.values())
			if (enrollment.pendingCertificatePin() != null) reserved.add(enrollment.serverId());
		return reserved.size();
	}

	private boolean hasPendingCertificate(String serverId) {
		for (Enrollment enrollment : enrollments.values())
			if (serverId.equals(enrollment.serverId()) && enrollment.pendingCertificatePin() != null) return true;
		return false;
	}

	private Map.Entry<String, Enrollment> pendingCertificate(String serverId, String pin) {
		for (Map.Entry<String, Enrollment> entry : enrollments.entrySet()) {
			Enrollment enrollment = entry.getValue();
			if (serverId.equals(enrollment.serverId()) && samePin(enrollment.pendingCertificatePin(), pin)) return entry;
		}
		return null;
	}

	private record Enrollment(byte[] tokenHash, Instant expiresAt, String serverId, String pendingCertificatePin) {
		private Enrollment { tokenHash = tokenHash.clone(); }
		@Override public byte[] tokenHash() { return tokenHash.clone(); }
	}
	private static boolean samePin(String expected, String actual) {
		return expected != null && actual != null && HttpTransportSecrets.constantTimeEquals(
				expected.getBytes(StandardCharsets.US_ASCII), actual.getBytes(StandardCharsets.US_ASCII));
	}

	private record ClientBinding(String certificatePin, String pendingCertificatePin, boolean revoked) { }
}
