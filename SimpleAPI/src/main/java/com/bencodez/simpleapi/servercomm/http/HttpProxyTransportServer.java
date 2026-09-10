package com.bencodez.simpleapi.servercomm.http;

import com.bencodez.simpleapi.servercomm.codec.JsonEnvelope;
import com.bencodez.simpleapi.file.DurableFiles;
import com.bencodez.simpleapi.file.PrivateFilePermissions;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLPeerUnverifiedException;

/**
 * One HTTPS listener for enrollment and the backend-to-proxy long-poll transport.
 * Every normal request is certificate-authenticated in the handler, rather than relying on TLS WANT auth.
 */
public final class HttpProxyTransportServer implements AutoCloseable {
	private static final int MAX_BACKENDS = 128;
	private static final int SETUP_REQUEST_HEADROOM = 8;
	private static final int MAX_ADMITTED_REQUESTS = MAX_BACKENDS + SETUP_REQUEST_HEADROOM;
	private static final long BACKEND_REPLAY_RETENTION_NANOS =
			TimeUnit.MILLISECONDS.toNanos(HttpTransportProtocol.MAX_CLOCK_SKEW_MILLIS) + 1L;
	static {
		// JDK HttpServer reads these once when its internal server configuration is initialized.
		// Set conservative process-wide bounds before this transport creates its listener.
		setDefault("sun.net.httpserver.maxReqTime", "10");
		setDefault("sun.net.httpserver.maxRspTime", "10");
		setDefault("jdk.httpserver.maxConnections", "144");
		setDefault("sun.net.httpserver.maxReqHeaders", "32");
		setDefault("sun.net.httpserver.maxReqHeaderSize", "16384");
	}
	// Keep an idle request open long enough to reuse the TLS connection, but bound backend-origin
	// latency when a message is queued immediately after the request body has already been sent.
	public static final Duration LONG_POLL = Duration.ofSeconds(2);
	private final HttpTlsIdentity identity;
	private final HttpEnrollmentAuthority authority;
	private final HttpsServer server;
	private final ThreadPoolExecutor listenerExecutor;
	private final ThreadPoolExecutor handlerExecutor;
	private final AtomicReference<Thread> handlerWorker = new AtomicReference<>();
	private final Object closeMonitor = new Object();
	private final Semaphore admission = new Semaphore(MAX_ADMITTED_REQUESTS);
	private final Map<String, BackendState> backends = new HashMap<>();
	private final DurableOutgoingQueue durableOutgoing;
	private final Path durableIncomingRoot;
	private final Consumer<ReceivedEnvelope> onEnvelope;
	private final DeliveryAcknowledgement onAcknowledged;
	private final LongSupplier nanoTime;
	private volatile boolean closed;
	private boolean closeFinalizing, closeFinalized;

	/** In-memory constructor for tests; production callers must supply a durable state directory. */
	HttpProxyTransportServer(InetSocketAddress bind, HttpTlsIdentity identity, HttpEnrollmentAuthority authority,
			Consumer<ReceivedEnvelope> onEnvelope) throws Exception {
		this(bind, identity, authority, null, onEnvelope, (serverId, deliveryId) -> { }, System::nanoTime);
	}

	public HttpProxyTransportServer(InetSocketAddress bind, HttpTlsIdentity identity, HttpEnrollmentAuthority authority,
			Path outgoingDirectory, Consumer<ReceivedEnvelope> onEnvelope) throws Exception {
		this(bind, identity, authority, outgoingDirectory, onEnvelope, (serverId, deliveryId) -> { });
	}

	public HttpProxyTransportServer(InetSocketAddress bind, HttpTlsIdentity identity, HttpEnrollmentAuthority authority,
			Path outgoingDirectory, Consumer<ReceivedEnvelope> onEnvelope,
			DeliveryAcknowledgement onAcknowledged) throws Exception {
		this(bind, identity, authority, Objects.requireNonNull(outgoingDirectory, "outgoingDirectory is required"),
				onEnvelope, onAcknowledged, System::nanoTime);
	}

	HttpProxyTransportServer(InetSocketAddress bind, HttpTlsIdentity identity, HttpEnrollmentAuthority authority,
			Path outgoingDirectory, Consumer<ReceivedEnvelope> onEnvelope,
			DeliveryAcknowledgement onAcknowledged, LongSupplier nanoTime) throws Exception {
		if (bind == null || identity == null || authority == null || onEnvelope == null || onAcknowledged == null)
			throw new IllegalArgumentException("HTTP transport configuration is required");
		if (nanoTime == null) throw new IllegalArgumentException("HTTP transport clock is required");
		this.identity = identity; this.authority = authority; this.onEnvelope = onEnvelope;
		this.onAcknowledged = onAcknowledged; this.nanoTime = nanoTime;
		durableOutgoing = outgoingDirectory == null ? null : new DurableOutgoingQueue(outgoingDirectory);
		HttpsServer createdServer = null;
		ThreadPoolExecutor createdListener = null, createdHandler = null;
		try {
			durableIncomingRoot = outgoingDirectory == null ? null : incomingRoot(outgoingDirectory);
			if (durableIncomingRoot != null) for (String serverId : HttpInboundDeliveryStore.discover(durableIncomingRoot)) {
				String canonical;
				try { canonical = HttpTlsIdentity.canonicalServerId(serverId); }
				catch (IllegalArgumentException invalid) { throw new IOException("HTTP inbound backend id is invalid", invalid); }
				if (!canonical.equals(serverId)) throw new IOException("HTTP inbound backend id is not canonical");
				HttpInboundDeliveryStore inbound = HttpInboundDeliveryStore.open(durableIncomingRoot, serverId);
				if (inbound.snapshot().isEmpty()) {
					try { inbound.sealAndDeleteIfEmpty(); }
					catch (IOException cleanupFailure) { inbound.seal(); throw cleanupFailure; }
					continue;
				}
				if (backends.size() >= MAX_BACKENDS) {
					inbound.seal();
					throw new IOException("HTTP backend state exceeds its bound");
				}
				backends.put(serverId, new BackendState(serverId, durableOutgoing, inbound, onAcknowledged, nanoTime));
			}
			if (durableOutgoing != null) for (Map.Entry<String, List<HttpTransportProtocol.Delivery>> pending
					: durableOutgoing.load().entrySet()) {
				BackendState state = backendState(pending.getKey());
				state.restore(pending.getValue());
			}
			createdServer = HttpsServer.create(bind, 32);
			createdServer.setHttpsConfigurator(new HttpsConfigurator(identity.serverContext()) {
				@Override public void configure(HttpsParameters parameters) {
					SSLParameters ssl = HttpPinnedTls.secureParameters(getSSLContext());
					ssl.setWantClientAuth(true); parameters.setSSLParameters(ssl);
				}
			});
			// Long polls are blocking by design. Capacity is bounded by admission, while enough workers
			// remain available for all admitted polls plus setup requests.
			createdListener = executor("SimpleAPI-HTTP-listener", MAX_ADMITTED_REQUESTS, MAX_ADMITTED_REQUESTS);
			// The proxy router mutates shared presence, vote, and reward state. A separate
			// bounded FIFO lane keeps wire order without blocking long-poll workers.
			createdHandler = executor("SimpleAPI-HTTP-handler", 1,
					HttpBackendTransportConnector.CALLBACK_QUEUE_CAPACITY, handlerWorker);
			createdServer.setExecutor(createdListener);
			createdServer.createContext("/v1/enroll", exchange -> enroll((HttpsExchange) exchange));
			createdServer.createContext("/v1/renew", exchange -> renew((HttpsExchange) exchange));
			createdServer.createContext("/v1/transport", exchange -> transport((HttpsExchange) exchange));
			server = createdServer;
			listenerExecutor = createdListener;
			handlerExecutor = createdHandler;
		} catch (Exception | Error setupFailure) {
			if (createdServer != null) createdServer.stop(0);
			if (createdHandler != null) shutdown(createdHandler);
			if (createdListener != null) shutdown(createdListener);
			releaseBackendOwnership();
			if (durableOutgoing != null) durableOutgoing.close();
			throw setupFailure;
		}
	}

	private void releaseBackendOwnership() {
		synchronized (backends) {
			for (BackendState backend : backends.values()) backend.seal();
			backends.clear();
		}
	}

	private void renew(HttpsExchange exchange) throws IOException {
		if (!"/v1/renew".equals(exchange.getRequestURI().getPath()) || exchange.getRequestURI().getRawQuery() != null) { reply(exchange, 404, new byte[0]); return; }
		if (!"POST".equals(exchange.getRequestMethod())) { reply(exchange, 405, new byte[0]); return; }
		if (!json(exchange)) { reply(exchange, 415, new byte[0]); return; }
		int bodyError = fixedBodyErrorStatus(exchange.getRequestHeaders(), 1024);
		if (bodyError != 0) { reply(exchange, bodyError, new byte[0]); return; }
		if (!admission.tryAcquire()) { reply(exchange, 429, new byte[0]); return; }
		try {
			String serverId = HttpTransportProtocol.parseRenewal(read(exchange.getRequestBody(), 1024));
			X509Certificate certificate = peerCertificate(exchange);
			if (certificate == null || !authority.authenticate(serverId, certificate)) { reply(exchange, 401, new byte[0]); return; }
			HttpTlsIdentity.IssuedClientCertificate issued = authority.renew(serverId, certificate);
			reply(exchange, 201, HttpTransportProtocol.enrollmentResponse(issued));
		} catch (HttpEnrollmentAuthority.RenewalRateLimitException limited) {
			exchange.getResponseHeaders().set("Retry-After", "60");
			reply(exchange, 429, new byte[0]);
		} catch (IllegalArgumentException rejected) { reply(exchange, 403, new byte[0]);
		} catch (Exception failure) { reply(exchange, 503, new byte[0]);
		} finally { admission.release(); }
	}

	public void start() { if (closed) throw new IllegalStateException("HTTP transport is closed"); server.start(); }
	public int port() { return server.getAddress().getPort(); }
	public URI endpoint(String host) {
		if (host == null || host.isBlank()) throw new IllegalArgumentException("Advertised host is required");
		try {
			URI endpoint = new URI("https", null, host, port(), "/", null, null);
			if (endpoint.getHost() == null) throw new IllegalArgumentException("Advertised host is invalid");
			return endpoint;
		} catch (java.net.URISyntaxException invalid) {
			throw new IllegalArgumentException("Advertised host is invalid", invalid);
		}
	}

	/**
	 * Queues a proxy-origin envelope durably before reporting acceptance.
	 * @throws DeliveryRetryException if publication needs recovery; persist its delivery ID
	 *         and retry the same envelope through the stable-ID overload
	 */
	public boolean send(String serverId, JsonEnvelope envelope) {
		return send(serverId, UUID.randomUUID().toString(), envelope, true);
	}

	/** Carries the generated ID needed to recover a quarantined or indeterminate send. */
	@SuppressWarnings("serial")
	public static final class DeliveryRetryException extends IllegalStateException {
		private final String deliveryId;
		private DeliveryRetryException(String deliveryId, Throwable cause) {
			super("HTTP delivery requires a same-ID retry: " + deliveryId, cause);
			this.deliveryId = deliveryId;
		}
		public String deliveryId() { return deliveryId; }
	}

	/**
	 * Queues a proxy-origin envelope with a stable, caller-persisted delivery ID.
	 * Callers recovering {@link DeliveryRetryException} must persist its ID and retry
	 * the identical envelope through this overload until it returns {@code true}.
	 */
	public boolean send(String serverId, String deliveryId, JsonEnvelope envelope) {
		return send(serverId, deliveryId, envelope, false);
	}

	/**
	 * Returns whether this server still owns any durable proxy-to-backend
	 * delivery, including a publication whose durability is awaiting a same-ID
	 * retry. Callers can use this before retiring the HTTP transport so accepted
	 * work is not stranded solely because another transport was configured.
	 */
	public boolean hasPendingDeliveries() {
		if (durableOutgoing != null) return durableOutgoing.hasPendingDeliveries();
		synchronized (backends) {
			for (BackendState backend : backends.values()) {
				if (backend.hasPendingOutgoing()) return true;
			}
		}
		return false;
	}

	private boolean send(String serverId, String deliveryId, JsonEnvelope envelope, boolean generatedId) {
		if (closed || serverId == null || envelope == null) return false;
		try {
			serverId = HttpTlsIdentity.canonicalServerId(serverId);
			HttpTransportProtocol.validId(deliveryId);
			HttpTransportProtocol.validateEnvelope(envelope);
		}
		catch (IllegalArgumentException invalid) { return false; }
		BackendState backend;
		final String canonicalServerId = serverId;
		try { backend = backendState(canonicalServerId); }
		catch (IOException persistenceFailure) { return false; }
		try { return backend.enqueue(new HttpTransportProtocol.Delivery(deliveryId, envelope), generatedId); }
		catch (DeliveryRetryException failure) { throw failure; }
		catch (IllegalStateException indeterminate) {
			if (generatedId) throw new DeliveryRetryException(deliveryId, indeterminate);
			throw indeterminate;
		}
	}

	@Override public void close() {
		boolean callbackWorker = Thread.currentThread() == handlerWorker.get();
		boolean finalizeHere = false;
		synchronized (closeMonitor) {
			if (!closed) {
				closed = true;
				server.stop(1);
				closeFinalizing = true;
				if (callbackWorker) {
					Thread finalizer = new Thread(this::finishClose, "SimpleAPI-HTTP-proxy-close");
					finalizer.setDaemon(true);
					finalizer.start();
					return;
				}
				finalizeHere = true;
			} else if (callbackWorker || closeFinalized) {
				return;
			}
		}
		if (finalizeHere) finishClose(); else awaitClose();
	}

	private void finishClose() {
		boolean handlersTerminated;
		boolean listenersTerminated;
		try { handlersTerminated = shutdown(handlerExecutor); }
		finally { listenersTerminated = shutdown(listenerExecutor); }
		// Listener workers can still be inside BackendState.acknowledge(), whose
		// durable removal must complete before outgoing ownership is released.
		if (!handlersTerminated || !listenersTerminated) {
			Thread reaper = new Thread(this::finishCloseAfterExecutors, "SimpleAPI-HTTP-proxy-close-reaper");
			reaper.setDaemon(true);
			reaper.start();
			return;
		}
		sealAfterHandlers();
	}
	private void finishCloseAfterExecutors() {
		boolean interrupted = false;
		while (!handlerExecutor.isTerminated() || !listenerExecutor.isTerminated()) try {
			if (!handlerExecutor.isTerminated()) handlerExecutor.awaitTermination(1, TimeUnit.DAYS);
			if (!listenerExecutor.isTerminated()) listenerExecutor.awaitTermination(1, TimeUnit.DAYS);
		} catch (InterruptedException ignored) { interrupted = true; }
		if (interrupted) Thread.currentThread().interrupt();
		sealAfterHandlers();
	}
	private void sealAfterHandlers() {
		try {
			synchronized (backends) {
				for (BackendState backend : backends.values()) { backend.seal(); backend.signal(); }
				backends.clear();
			}
		} finally { try { if (durableOutgoing != null) durableOutgoing.close(); }
		finally { synchronized (closeMonitor) { closeFinalizing = false; closeFinalized = true; closeMonitor.notifyAll(); } } }
	}

	private void awaitClose() {
		boolean interrupted = false;
		synchronized (closeMonitor) {
			while (closeFinalizing && !closeFinalized) try { closeMonitor.wait(); }
			catch (InterruptedException stopRequested) { interrupted = true; }
		}
		if (interrupted) Thread.currentThread().interrupt();
	}

	private void enroll(HttpsExchange exchange) throws IOException {
		if (!"/v1/enroll".equals(exchange.getRequestURI().getPath()) || exchange.getRequestURI().getRawQuery() != null) { reply(exchange, 404, new byte[0]); return; }
		if (!"POST".equals(exchange.getRequestMethod())) { reply(exchange, 405, new byte[0]); return; }
		if (!json(exchange)) { reply(exchange, 415, new byte[0]); return; }
		int bodyError = fixedBodyErrorStatus(exchange.getRequestHeaders(), 8192);
		if (bodyError != 0) { reply(exchange, bodyError, new byte[0]); return; }
		if (!admission.tryAcquire()) { reply(exchange, 429, new byte[0]); return; }
		try {
			HttpTransportProtocol.Enrollment request = HttpTransportProtocol.parseEnrollment(read(exchange.getRequestBody(), 8192));
			HttpTlsIdentity.IssuedClientCertificate issued = authority.enroll(request.server(), request.token());
			reply(exchange, 201, HttpTransportProtocol.enrollmentResponse(issued));
		} catch (IllegalArgumentException rejected) { reply(exchange, 403, new byte[0]);
		} catch (Exception failure) { reply(exchange, 503, new byte[0]); }
		finally { admission.release(); }
	}

	private void transport(HttpsExchange exchange) throws IOException {
		if (!"/v1/transport".equals(exchange.getRequestURI().getPath()) || exchange.getRequestURI().getRawQuery() != null) { reply(exchange, 404, new byte[0]); return; }
		if (!"POST".equals(exchange.getRequestMethod())) { reply(exchange, 405, new byte[0]); return; }
		if (!json(exchange)) { reply(exchange, 415, new byte[0]); return; }
		int bodyError = fixedBodyErrorStatus(exchange.getRequestHeaders(), HttpTransportProtocol.MAX_BODY_BYTES);
		if (bodyError != 0) { reply(exchange, bodyError, new byte[0]); return; }
		if (!admission.tryAcquire()) { reply(exchange, 429, new byte[0]); return; }
		try {
			HttpTransportProtocol.Packet packet = HttpTransportProtocol.parsePacket(read(exchange.getRequestBody(), HttpTransportProtocol.MAX_BODY_BYTES));
			X509Certificate certificate = peerCertificate(exchange);
			if (certificate == null || !authority.authenticate(packet.server(), certificate)) { reply(exchange, 401, new byte[0]); return; }
			BackendState backend;
			backend = backendState(packet.server());
			if (!backend.beginPoll(packet.session())) { reply(exchange, 409, new byte[0]); return; }
			try {
				handlePacket(packet, backend);
				Response response = backend.await(packet.server(), packet.session(), packet.sequence(), packet.acks());
				reply(exchange, 200, HttpTransportProtocol.response(packet.server(), packet.session(), packet.sequence(),
						response.acks(), packet.acks(), response.messages()));
			} finally { backend.endPoll(); }
		} catch (RateLimitException rateLimited) { reply(exchange, 429, new byte[0]);
		} catch (IllegalArgumentException rejected) { reply(exchange, 400, new byte[0]);
		} catch (Exception failure) { reply(exchange, 503, new byte[0]);
		} finally { admission.release(); }
	}

	private void handlePacket(HttpTransportProtocol.Packet packet, BackendState backend) throws IOException {
		List<HttpTransportProtocol.Delivery> accepted;
		synchronized (backend) {
			if (!backend.allowRequest()) throw new RateLimitException("transport rate limited");
			if (!backend.acceptSession(packet.session(), packet.sequence())) throw new IllegalArgumentException("stale session request");
		}
		backend.confirmIncoming(packet.ackConfirmations());
		backend.acknowledge(packet.acks());
		synchronized (backend) { accepted = backend.acceptIncoming(packet.messages()); }
		for (HttpTransportProtocol.Delivery delivery : accepted) dispatch(packet.server(), backend, delivery);
	}
	private void dispatch(String serverId, BackendState backend, HttpTransportProtocol.Delivery delivery) {
		Runnable callback = () -> {
			boolean success = false;
			try {
				backend.beginIncoming(delivery.id());
				onEnvelope.accept(new ReceivedEnvelope(serverId, delivery.id(), normalizeBackendIdentity(serverId, delivery.envelope())));
				backend.completeIncomingDurably(delivery.id());
				success = true;
			}
			catch (IOException persistenceFailure) { }
			catch (RuntimeException ignored) { }
			synchronized (backend) { backend.completeIncoming(delivery.id(), success); }
		};
		if (!HttpBackendTransportConnector.executeOrdered(handlerExecutor, callback))
			synchronized (backend) { backend.completeIncoming(delivery.id(), false); }
	}
	private BackendState backendState(String serverId) throws IOException {
		synchronized (backends) {
			if (closed) throw new IOException("HTTP proxy transport is closed");
			BackendState existing = backends.get(serverId);
			if (existing != null) return existing;
			if (backends.size() >= MAX_BACKENDS) reclaimInactiveBackend();
			if (backends.size() >= MAX_BACKENDS) throw new IOException("HTTP backend state exceeds its bound");
			HttpInboundDeliveryStore inbound = null;
			try {
				inbound = durableIncomingRoot == null ? null
						: HttpInboundDeliveryStore.open(durableIncomingRoot, serverId);
				BackendState created = new BackendState(serverId, durableOutgoing, inbound, onAcknowledged, nanoTime);
				backends.put(serverId, created);
				return created;
			} catch (Exception | Error setupFailure) {
				if (inbound != null) inbound.seal();
				throw setupFailure;
			}
		}
	}
	private void reclaimInactiveBackend() throws IOException {
		long now = nanoTime.getAsLong();
		for (Iterator<Map.Entry<String, BackendState>> iterator = backends.entrySet().iterator(); iterator.hasNext();) {
			BackendState state = iterator.next().getValue();
			if (!state.retireIfQuiescent(now, BACKEND_REPLAY_RETENTION_NANOS)) continue;
			iterator.remove();
			return;
		}
	}
	BackendState backendStateForTest(String serverId) throws IOException { return backendState(serverId); }
	int backendCountForTest() { synchronized (backends) { return backends.size(); } }
	private static Path incomingRoot(Path outgoingDirectory) throws IOException {
		Path outgoing = outgoingDirectory.toAbsolutePath().normalize();
		Path parent = outgoing.getParent();
		if (parent == null || outgoing.getFileName() == null) throw new IOException("HTTP incoming queue path is invalid");
		Path root = parent.resolve(outgoing.getFileName().toString() + "-incoming");
		try { Files.createDirectory(root); }
		catch (java.nio.file.FileAlreadyExistsException existing) { }
		if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("HTTP incoming queue directory is invalid");
		PrivateFilePermissions.ownerOnlyDirectory(root);
		// Retry publication durability even when an earlier attempt created the
		// directory but failed before its parent could be forced.
		DurableFiles.forceDirectory(parent);
		return root;
	}
	private static JsonEnvelope normalizeBackendIdentity(String serverId, JsonEnvelope envelope) {
		// The authenticated TLS identity is authoritative; never forward a forged `server` field.
		return envelope.toBuilder().put("server", serverId).build();
	}
	private static X509Certificate peerCertificate(HttpsExchange exchange) {
		try { Certificate[] peer = exchange.getSSLSession().getPeerCertificates();
			return peer.length > 0 && peer[0] instanceof X509Certificate certificate ? certificate : null;
		} catch (SSLPeerUnverifiedException absent) { return null; }
	}
	private static byte[] read(InputStream input, int maximum) throws IOException {
		ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int total = 0, read;
		while ((read = input.read(buffer)) >= 0) { total += read; if (total > maximum) throw new IllegalArgumentException("HTTP body is too large"); output.write(buffer, 0, read); }
		return output.toByteArray();
	}
	private static void reply(HttpsExchange exchange, int status, byte[] body) throws IOException {
		Headers headers = exchange.getResponseHeaders(); headers.set("Cache-Control", "no-store"); headers.set("Content-Type", "application/json; charset=utf-8");
		exchange.sendResponseHeaders(status, body.length); try (var output = exchange.getResponseBody()) { output.write(body); }
	}
	private static boolean json(HttpsExchange exchange) {
		String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
		return contentType != null && contentType.toLowerCase(java.util.Locale.ROOT).matches("application/json(?:\\s*;.*)?");
	}
	static int fixedBodyErrorStatus(Headers headers, int maximum) {
		if (headers.getFirst("Transfer-Encoding") != null) return 400;
		String value = headers.getFirst("Content-Length");
		if (value == null) return 411;
		try {
			long length = Long.parseLong(value);
			if (length <= 0L) return 400;
			return length <= maximum ? 0 : 413;
		} catch (NumberFormatException invalid) { return 400; }
	}
	private static ThreadPoolExecutor executor(String name, int threads, int queue) {
		return executor(name, threads, queue, null);
	}
	private static ThreadPoolExecutor executor(String name, int threads, int queue, AtomicReference<Thread> worker) {
		ThreadFactory factory = task -> {
			Thread thread = new Thread(() -> {
				if (worker != null) worker.set(Thread.currentThread());
				task.run();
			}, name);
			thread.setDaemon(true);
			return thread;
		};
		return new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(queue), factory, new ThreadPoolExecutor.AbortPolicy());
	}
	private static void setDefault(String name, String value) { if (System.getProperty(name) == null) System.setProperty(name, value); }
	static boolean shutdown(ExecutorService executor) {
		executor.shutdown();
		boolean interrupted = false;
		try {
			if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
				executor.shutdownNow();
				return executor.awaitTermination(1, TimeUnit.SECONDS);
			}
		} catch (InterruptedException stopRequested) {
			interrupted = true;
			executor.shutdownNow();
			try { executor.awaitTermination(1, TimeUnit.SECONDS); }
			catch (InterruptedException repeated) { interrupted = true; }
		}
		if (interrupted) Thread.currentThread().interrupt();
		return executor.isTerminated();
	}

	public record ReceivedEnvelope(String serverId, String messageId, JsonEnvelope envelope) { }

	@FunctionalInterface
	public interface DeliveryAcknowledgement {
		void confirm(String serverId, String deliveryId) throws IOException;
	}
	static record Response(Collection<String> acks, Collection<HttpTransportProtocol.Delivery> messages) { }
	static final class RateLimitException extends IllegalArgumentException {
		RateLimitException(String message) { super(message); }
	}
	static final class BackendState {
		private final String serverId;
		private final DurableOutgoingQueue durableOutgoing;
		private final HttpInboundDeliveryStore durableIncoming;
		private final DeliveryAcknowledgement onAcknowledged;
		private final LongSupplier nanoTime;
		private String session; private long sequence = -1L;
		private final LinkedHashMap<String, HttpTransportProtocol.Delivery> outgoing = new LinkedHashMap<>();
		private final Set<String> seen = new LinkedHashSet<>(); private final Set<String> processing = new LinkedHashSet<>();
		private final ArrayDeque<String> acknowledgements = new ArrayDeque<>();
		private final Map<String, Long> deliveredAtNanos = new HashMap<>();
		private double requestTokens = 24.0d;
		private long lastTokenNanos;
		private long lastActivityNanos;
		private boolean activePoll, retired;
		BackendState() { this(null, null, null, (serverId, deliveryId) -> { }, System::nanoTime); }
		BackendState(LongSupplier nanoTime) { this(null, null, null, (serverId, deliveryId) -> { }, nanoTime); }
		private BackendState(String serverId, DurableOutgoingQueue durableOutgoing) {
			this(serverId, durableOutgoing, null, (ignoredServer, ignoredDelivery) -> { }, System::nanoTime);
		}
		BackendState(String serverId, DurableOutgoingQueue durableOutgoing,
				DeliveryAcknowledgement onAcknowledged) {
			this(serverId, durableOutgoing, null, onAcknowledged, System::nanoTime);
		}
		BackendState(String serverId, DurableOutgoingQueue durableOutgoing, HttpInboundDeliveryStore durableIncoming,
				DeliveryAcknowledgement onAcknowledged) {
			this(serverId, durableOutgoing, durableIncoming, onAcknowledged, System::nanoTime);
		}
		private BackendState(String serverId, DurableOutgoingQueue durableOutgoing,
				HttpInboundDeliveryStore durableIncoming, DeliveryAcknowledgement onAcknowledged, LongSupplier nanoTime) {
			this.serverId = serverId; this.durableOutgoing = durableOutgoing;
			this.durableIncoming = durableIncoming; this.onAcknowledged = onAcknowledged; this.nanoTime = nanoTime;
			lastTokenNanos = lastActivityNanos = nanoTime.getAsLong();
			if (durableIncoming != null) for (Map.Entry<String, HttpInboundDeliveryStore.State> entry
					: durableIncoming.snapshot().entrySet()) {
				if (entry.getValue() == HttpInboundDeliveryStore.State.COMPLETED) {
					try {
						durableIncoming.confirmCompleted(entry.getKey());
						seen.add(entry.getKey()); queueAck(entry.getKey());
					} catch (IOException unconfirmed) { }
				}
			}
		}
		private synchronized void restore(Collection<HttpTransportProtocol.Delivery> deliveries) {
			for (HttpTransportProtocol.Delivery delivery : deliveries) outgoing.put(delivery.id(), delivery);
		}
		private synchronized boolean hasPendingOutgoing() { return !outgoing.isEmpty(); }
		private boolean beginPoll(String requestedSession) { synchronized (this) { if (retired || activePoll) return false; activePoll = true; touch(); return true; } }
		private void endPoll() { synchronized (this) { activePoll = false; touch(); notifyAll(); } }
		boolean beginPollForTest() { return beginPoll("test"); }
		void endPollForTest() { endPoll(); }
		private boolean allowRequest() {
			long now = nanoTime.getAsLong(); requestTokens = Math.min(24.0d, requestTokens + ((now - lastTokenNanos) / 1_000_000_000.0d) * 2.0d);
			lastTokenNanos = now; touch(); if (requestTokens < 1.0d) return false; requestTokens -= 1.0d; return true;
		}
		boolean acceptSession(String requested, long requestedSequence) {
			if (!requested.equals(session)) { session = requested; sequence = -1L; deliveredAtNanos.clear(); }
			// The connector allocates a fresh monotonic sequence for every attempt.  Rejecting equality
			// prevents a captured request from being replayed with altered ACKs or a new payload.
			if (requestedSequence <= sequence) return false; sequence = requestedSequence; return true;
		}
		synchronized boolean enqueue(HttpTransportProtocol.Delivery delivery) {
			return enqueue(delivery, false);
		}
		private synchronized boolean enqueue(HttpTransportProtocol.Delivery delivery, boolean generatedId) {
			if (retired) return false;
			HttpTransportProtocol.Delivery existing = outgoing.get(delivery.id());
			if (existing != null) {
				if (!Arrays.equals(HttpTransportProtocol.storedDelivery(existing),
						HttpTransportProtocol.storedDelivery(delivery))) return false;
				if (durableOutgoing != null) try { durableOutgoing.confirm(serverId, delivery.id()); }
				catch (IOException failure) { return false; }
				return true;
			}
			if (outgoing.size() >= HttpTransportProtocol.MAX_QUEUE) return false;
			if (durableOutgoing != null) try { durableOutgoing.persist(serverId, delivery); }
			catch (DurableFiles.PublishedException quarantined) {
				if (generatedId) throw new DeliveryRetryException(delivery.id(), quarantined);
				return false;
			}
			catch (IOException failure) { return false; }
			outgoing.put(delivery.id(), delivery); touch(); signal(); return true;
		}
		void acknowledge(Collection<String> acks) throws IOException {
			for (String id : acks) {
				synchronized (this) { if (!outgoing.containsKey(id)) continue; }
				onAcknowledged.confirm(serverId, id);
				synchronized (this) {
					if (!outgoing.containsKey(id)) continue;
				// A 200 response is the backend's proof that its durable replay fence may
				// be deleted. Never return success while the proxy delivery still exists.
				if (durableOutgoing != null) durableOutgoing.remove(serverId, id);
				outgoing.remove(id); deliveredAtNanos.remove(id);
				}
			}
		}
		List<HttpTransportProtocol.Delivery> acceptIncoming(List<HttpTransportProtocol.Delivery> received) {
			List<HttpTransportProtocol.Delivery> accepted = new java.util.ArrayList<>();
			for (HttpTransportProtocol.Delivery delivery : received) {
				HttpInboundDeliveryStore.State persisted = durableIncoming == null ? null : durableIncoming.state(delivery.id());
				if (persisted == HttpInboundDeliveryStore.State.RUNNING) try {
					if (durableIncoming.recoverKnownNotStartedRunning(delivery.id())) persisted = durableIncoming.state(delivery.id());
				} catch (IOException rollbackUnconfirmed) { continue; }
				if (persisted == HttpInboundDeliveryStore.State.COMPLETED) {
					try { durableIncoming.confirmCompleted(delivery.id()); }
					catch (IOException unconfirmed) { continue; }
					seen.add(delivery.id()); queueAck(delivery.id()); continue;
				}
				if (seen.contains(delivery.id())) {
					seen.add(delivery.id()); queueAck(delivery.id()); continue;
				}
				if (persisted == HttpInboundDeliveryStore.State.RUNNING) continue;
				if (!processing.contains(delivery.id())) {
					processing.add(delivery.id()); accepted.add(delivery);
				}
			}
			return accepted;
		}
		void beginIncoming(String id) throws IOException {
			if (durableIncoming == null) return;
			if (durableIncoming.state(id) == null) durableIncoming.reserve(id);
			durableIncoming.markRunning(id);
		}
		void completeIncomingDurably(String id) throws IOException {
			if (durableIncoming != null) durableIncoming.markCompleted(id);
		}
		synchronized void completeIncoming(String id, boolean success) { processing.remove(id); if (success) { seen.add(id); while (seen.size() > HttpTransportProtocol.MAX_QUEUE) seen.remove(seen.iterator().next()); queueAck(id); signal(); } }
		synchronized void confirmIncoming(Collection<String> ids) throws IOException {
			for (String id : ids) {
				if (durableIncoming != null && durableIncoming.state(id) == HttpInboundDeliveryStore.State.COMPLETED)
					durableIncoming.remove(id);
				seen.remove(id);
				acknowledgements.removeIf(id::equals);
			}
		}
		private void seal() { if (durableIncoming != null) durableIncoming.seal(); }
		private synchronized boolean retireIfQuiescent(long now, long retentionNanos) throws IOException {
			if (retired || activePoll || !outgoing.isEmpty() || !deliveredAtNanos.isEmpty() || !seen.isEmpty()
					|| !processing.isEmpty() || !acknowledgements.isEmpty() || now - lastActivityNanos < retentionNanos
					|| durableIncoming != null && !durableIncoming.snapshot().isEmpty()
					|| durableOutgoing != null && durableOutgoing.hasQuarantined(serverId)) return false;
			if (durableIncoming != null) durableIncoming.sealAndDeleteIfEmpty();
			retired = true;
			return true;
		}
		private void touch() { lastActivityNanos = nanoTime.getAsLong(); }
		private void queueAck(String id) { if (acknowledgements.size() < HttpTransportProtocol.MAX_QUEUE && !acknowledgements.contains(id)) acknowledgements.add(id); }
		synchronized Response await(String serverId, String requestedSession, long requestedSequence) {
			return await(serverId, requestedSession, requestedSequence, List.of());
		}
		synchronized Response await(String serverId, String requestedSession, long requestedSequence,
				Collection<String> ackConfirmations) {
			long deadline = System.nanoTime() + LONG_POLL.toNanos();
			while (acknowledgements.isEmpty() && !hasUndelivered()) {
				long retryRemaining = nanosUntilRedelivery(nanoTime.getAsLong());
				if (retryRemaining <= 0L) break;
				long requestRemaining = deadline - System.nanoTime(); if (requestRemaining <= 0L) break;
				long wait = Math.min(requestRemaining, retryRemaining);
				try { TimeUnit.NANOSECONDS.timedWait(this, wait); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
			}
			List<String> acks = new java.util.ArrayList<>(); while (!acknowledgements.isEmpty() && acks.size() < HttpTransportProtocol.MAX_BATCH) acks.add(acknowledgements.remove());
			List<HttpTransportProtocol.Delivery> candidates = new java.util.ArrayList<>();
			long now = nanoTime.getAsLong();
			if (hasUndelivered() || redeliveryDue(now)) for (HttpTransportProtocol.Delivery delivery : outgoing.values()) {
				if (!deliveredAtNanos.containsKey(delivery.id()) || redeliveryDue(delivery.id(), now)) candidates.add(delivery);
				if (candidates.size() == HttpTransportProtocol.MAX_BATCH) break;
			}
			List<HttpTransportProtocol.Delivery> messages = HttpTransportProtocol.fittingMessages(serverId, requestedSession,
					requestedSequence, acks, ackConfirmations, candidates);
			long deliveredAt = nanoTime.getAsLong();
			for (HttpTransportProtocol.Delivery delivery : messages) deliveredAtNanos.put(delivery.id(), deliveredAt);
			return new Response(acks, messages);
		}
		private boolean hasUndelivered() { for (String id : outgoing.keySet()) if (!deliveredAtNanos.containsKey(id)) return true; return false; }
		private boolean redeliveryDue(long now) {
			for (String id : outgoing.keySet()) if (redeliveryDue(id, now)) return true;
			return false;
		}
		private boolean redeliveryDue(String id, long now) {
			Long deliveredAt = deliveredAtNanos.get(id);
			return deliveredAt != null && now - deliveredAt >= LONG_POLL.toNanos();
		}
		private long nanosUntilRedelivery(long now) {
			long remaining = Long.MAX_VALUE;
			for (String id : outgoing.keySet()) {
				Long deliveredAt = deliveredAtNanos.get(id);
				if (deliveredAt == null) continue;
				long candidate = LONG_POLL.toNanos() - (now - deliveredAt);
				if (candidate <= 0L) return 0L;
				remaining = Math.min(remaining, candidate);
			}
			return remaining;
		}
		private synchronized void signal() { notifyAll(); }
	}

	static final class DurableOutgoingQueue implements AutoCloseable {
		@FunctionalInterface
		interface DirectoryForcer { void force(Path directory) throws IOException; }
		private static final String FILE_PATTERN = "[0-9]{20}-[0-9a-f-]{36}\\.json";
		private final Path root;
		private final DirectoryForcer directoryForcer;
		private final Map<String, Map<String, Path>> files = new HashMap<>();
		private final Map<String, Map<String, Path>> quarantinedFiles = new HashMap<>();
		private FileChannel ownershipChannel;
		private FileLock ownershipLock;
		private long sequence;

		private DurableOutgoingQueue(Path root) throws IOException {
			this(root, DurableFiles::forceDirectory);
		}

		DurableOutgoingQueue(Path root, DirectoryForcer directoryForcer) throws IOException {
			if (root == null || directoryForcer == null)
				throw new IllegalArgumentException("HTTP outgoing queue configuration is required");
			this.directoryForcer = directoryForcer;
			this.root = root.toAbsolutePath().normalize();
			Path parent = this.root.getParent();
			if (parent == null || this.root.getFileName() == null)
				throw new IOException("HTTP outgoing queue directory is invalid");
			try {
				claimOwnership(parent.resolve("." + this.root.getFileName() + ".http-outgoing-owner.lock"));
				try { Files.createDirectory(this.root); }
				catch (java.nio.file.FileAlreadyExistsException existing) { }
				if (Files.isSymbolicLink(this.root) || !Files.isDirectory(this.root, LinkOption.NOFOLLOW_LINKS))
					throw new IOException("HTTP outgoing queue directory is invalid");
				PrivateFilePermissions.ownerOnlyDirectory(this.root);
				// A failed parent fsync can leave the directory present but not durable.
				// Reopening must retry it before the queue can accept work.
				directoryForcer.force(parent);
			} catch (IOException | RuntimeException setupFailure) {
				releaseOwnership();
				throw setupFailure;
			}
		}

		synchronized Map<String, List<HttpTransportProtocol.Delivery>> load() throws IOException {
			requireOwnership();
			Map<String, List<HttpTransportProtocol.Delivery>> loaded = new LinkedHashMap<>();
			int serverDirectories = 0;
			try (DirectoryStream<Path> servers = Files.newDirectoryStream(root)) {
				for (Path directory : servers) {
					if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
						throw new IOException("HTTP outgoing queue contains an invalid entry");
					PrivateFilePermissions.ownerOnlyDirectory(directory);
					String serverId;
					try { serverId = HttpTlsIdentity.canonicalServerId(directory.getFileName().toString()); }
					catch (IllegalArgumentException invalid) { throw new IOException("HTTP outgoing queue server is invalid", invalid); }
					if (!serverId.equals(directory.getFileName().toString()))
						throw new IOException("HTTP outgoing queue server is not canonical");
					List<Path> entries = new java.util.ArrayList<>();
					try (DirectoryStream<Path> messages = Files.newDirectoryStream(directory)) {
						for (Path message : messages) entries.add(message);
					}
					entries.sort(java.util.Comparator.comparing(path -> path.getFileName().toString()));
					List<HttpTransportProtocol.Delivery> deliveries = new java.util.ArrayList<>();
					Map<String, Path> serverFiles = files.computeIfAbsent(serverId, ignored -> new HashMap<>());
					Map<String, Path> quarantined = quarantinedFiles.computeIfAbsent(serverId, ignored -> new HashMap<>());
					int durableEntries = 0;
					for (Path message : entries) {
						String name = message.getFileName().toString();
						if (name.startsWith(".pending-") && name.endsWith(".tmp")
								&& !Files.isSymbolicLink(message) && Files.isRegularFile(message, LinkOption.NOFOLLOW_LINKS)) {
							DurableFiles.deleteIfExists(message);
							continue;
						}
						if (name.startsWith(".pending-") && name.endsWith(".json")) {
							if (Files.isSymbolicLink(message) || !Files.isRegularFile(message, LinkOption.NOFOLLOW_LINKS)
									|| Files.size(message) > HttpTransportProtocol.MAX_ENVELOPE_BYTES * 2L)
								throw new IOException("HTTP outgoing queue quarantine is invalid");
							PrivateFilePermissions.ownerOnlyFile(message);
							HttpTransportProtocol.Delivery delivery;
							try { delivery = HttpTransportProtocol.parseStoredDelivery(Files.readAllBytes(message)); }
							catch (IllegalArgumentException invalid) { throw new IOException("HTTP outgoing queue quarantine is invalid", invalid); }
							if (!name.equals(".pending-" + delivery.id() + ".json")
									|| serverFiles.containsKey(delivery.id()) || quarantined.put(delivery.id(), message) != null)
								throw new IOException("HTTP outgoing queue quarantine id is invalid");
							if (++durableEntries > HttpTransportProtocol.MAX_QUEUE)
								throw new IOException("HTTP outgoing queue exceeds its bound");
							continue;
						}
						if (Files.isSymbolicLink(message) || !Files.isRegularFile(message, LinkOption.NOFOLLOW_LINKS)
								|| !name.matches(FILE_PATTERN) || Files.size(message) > HttpTransportProtocol.MAX_ENVELOPE_BYTES * 2L)
							throw new IOException("HTTP outgoing queue message is invalid");
						PrivateFilePermissions.ownerOnlyFile(message);
						HttpTransportProtocol.Delivery delivery;
						try { delivery = HttpTransportProtocol.parseStoredDelivery(Files.readAllBytes(message)); }
						catch (IllegalArgumentException invalid) { throw new IOException("HTTP outgoing queue message is invalid", invalid); }
						if (!name.endsWith("-" + delivery.id() + ".json") || quarantined.containsKey(delivery.id())
								|| serverFiles.put(delivery.id(), message) != null)
							throw new IOException("HTTP outgoing queue message id is invalid");
						deliveries.add(delivery);
						if (++durableEntries > HttpTransportProtocol.MAX_QUEUE)
							throw new IOException("HTTP outgoing queue exceeds its bound");
						sequence = Math.max(sequence, Long.parseLong(name.substring(0, 20)));
					}
					if (durableEntries == 0 && hasNoIndexedDeliveries(serverId)) {
						deleteVerifiedEmptyDirectory(directory);
						files.remove(serverId);
						quarantinedFiles.remove(serverId);
						continue;
					}
					if (++serverDirectories > MAX_BACKENDS)
						throw new IOException("HTTP outgoing queue exceeds its backend bound");
					// Quarantine-only queues still reserve this backend's runtime state. Omitting
					// them can consume the cap with unrelated inbound journals and make the
					// identical retry unable to reach persist()'s quarantine recovery path.
					loaded.put(serverId, deliveries);
				}
			}
			return loaded;
		}

		private synchronized void persist(String serverId, HttpTransportProtocol.Delivery delivery) throws IOException {
			requireOwnership();
			Path directory = root.resolve(serverId).normalize();
			if (!directory.getParent().equals(root)) throw new IOException("HTTP outgoing queue server is invalid");
			if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS) && serverDirectoryCount() >= MAX_BACKENDS)
				throw new IOException("HTTP outgoing queue exceeds its backend bound");
			boolean created = false;
			try { Files.createDirectory(directory); created = true; }
			catch (java.nio.file.FileAlreadyExistsException existing) { }
			try {
				if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
					throw new IOException("HTTP outgoing queue server directory is invalid");
				PrivateFilePermissions.ownerOnlyDirectory(directory);
				// The child fsync below cannot make this published name durable in its
				// parent. Repeat it so a prior failed attempt is recoverable.
				directoryForcer.force(root);
			} catch (IOException setupFailure) {
				if (created) try { deleteVerifiedEmptyDirectory(directory); }
				catch (IOException cleanupFailure) { setupFailure.addSuppressed(cleanupFailure); }
				throw setupFailure;
			}
			Map<String, Path> serverFiles = files.computeIfAbsent(serverId, ignored -> new HashMap<>());
			Map<String, Path> quarantined = quarantinedFiles.computeIfAbsent(serverId, ignored -> new HashMap<>());
			Path existing = serverFiles.get(delivery.id());
			if (existing != null) {
				if (Files.isSymbolicLink(existing) || !Files.isRegularFile(existing, LinkOption.NOFOLLOW_LINKS)
						|| Files.size(existing) > HttpTransportProtocol.MAX_ENVELOPE_BYTES * 2L
						|| !Arrays.equals(Files.readAllBytes(existing), HttpTransportProtocol.storedDelivery(delivery)))
					throw new IOException("HTTP outgoing queue delivery id conflicts with persisted data");
				PrivateFilePermissions.ownerOnlyFile(existing);
				directoryForcer.force(directory);
				return;
			}
			Path pending = quarantined.get(delivery.id());
			if (pending != null) {
				if (Files.isSymbolicLink(pending) || !Files.isRegularFile(pending, LinkOption.NOFOLLOW_LINKS)
						|| Files.size(pending) > HttpTransportProtocol.MAX_ENVELOPE_BYTES * 2L
						|| !Arrays.equals(Files.readAllBytes(pending), HttpTransportProtocol.storedDelivery(delivery)))
					throw new IOException("HTTP outgoing queue delivery id conflicts with persisted data");
				if (sequence == Long.MAX_VALUE) throw new IOException("HTTP outgoing queue sequence is exhausted");
				String name = String.format(java.util.Locale.ROOT, "%020d-%s.json", ++sequence, delivery.id());
				Path target = directory.resolve(name);
				try { Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE); }
				catch (java.nio.file.AtomicMoveNotSupportedException unsupported) { Files.move(pending, target); }
				try { PrivateFilePermissions.ownerOnlyFile(target); directoryForcer.force(directory); }
				catch (IOException postPublicationFailure) {
					quarantinePublished(directory, target, pending, serverFiles, quarantined, delivery.id(), postPublicationFailure);
				}
				quarantined.remove(delivery.id());
				serverFiles.put(delivery.id(), target);
				return;
			}
			if (serverFiles.size() + quarantined.size() >= HttpTransportProtocol.MAX_QUEUE)
				throw new IOException("HTTP outgoing queue exceeds its bound");
			if (sequence == Long.MAX_VALUE) throw new IOException("HTTP outgoing queue sequence is exhausted");
			String name = String.format(java.util.Locale.ROOT, "%020d-%s.json", ++sequence, delivery.id());
			Path target = directory.resolve(name);
			Path temporary = Files.createTempFile(directory, ".pending-", ".tmp");
			try {
				PrivateFilePermissions.ownerOnlyFile(temporary);
				Files.write(temporary, HttpTransportProtocol.storedDelivery(delivery), StandardOpenOption.TRUNCATE_EXISTING);
				DurableFiles.forceFile(temporary);
				try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE); }
				catch (java.nio.file.AtomicMoveNotSupportedException unsupported) { Files.move(temporary, target); }
				try { PrivateFilePermissions.ownerOnlyFile(target); directoryForcer.force(directory); }
				catch (IOException postPublicationFailure) {
					pending = directory.resolve(".pending-" + delivery.id() + ".json");
					quarantinePublished(directory, target, pending, serverFiles, quarantined, delivery.id(), postPublicationFailure);
				}
				serverFiles.put(delivery.id(), target);
			} finally { Files.deleteIfExists(temporary); }
		}

		/** Counts durable backend directories, including quarantine-only queues omitted from load's deliverable map. */
		private int serverDirectoryCount() throws IOException {
			int count = 0;
			try (DirectoryStream<Path> directories = Files.newDirectoryStream(root)) {
				for (Path directory : directories) {
					if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
						throw new IOException("HTTP outgoing queue contains an invalid entry");
					String serverId;
					try { serverId = HttpTlsIdentity.canonicalServerId(directory.getFileName().toString()); }
					catch (IllegalArgumentException invalid) { throw new IOException("HTTP outgoing queue server is invalid", invalid); }
					if (!serverId.equals(directory.getFileName().toString()))
						throw new IOException("HTTP outgoing queue server is not canonical");
					PrivateFilePermissions.ownerOnlyDirectory(directory);
					if (isEmptyDirectory(directory) && hasNoIndexedDeliveries(serverId)) {
						deleteVerifiedEmptyDirectory(directory);
						continue;
					}
					if (++count > MAX_BACKENDS) throw new IOException("HTTP outgoing queue exceeds its backend bound");
				}
			}
			return count;
		}

		private boolean isEmptyDirectory(Path directory) throws IOException {
			try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) { return !entries.iterator().hasNext(); }
		}

		private boolean hasNoIndexedDeliveries(String serverId) {
			Map<String, Path> serverFiles = files.get(serverId);
			Map<String, Path> quarantined = quarantinedFiles.get(serverId);
			return (serverFiles == null || serverFiles.isEmpty()) && (quarantined == null || quarantined.isEmpty());
		}

		private synchronized boolean hasQuarantined(String serverId) throws IOException {
			requireOwnership();
			Map<String, Path> quarantined = quarantinedFiles.get(serverId);
			return quarantined != null && !quarantined.isEmpty();
		}

		synchronized boolean hasPendingDeliveries() {
			for (Map<String, Path> serverFiles : files.values()) {
				if (!serverFiles.isEmpty()) return true;
			}
			for (Map<String, Path> quarantined : quarantinedFiles.values()) {
				if (!quarantined.isEmpty()) return true;
			}
			return false;
		}

		/** Deletes only a validated, observed-empty backend directory and makes its removal durable. */
		private void deleteVerifiedEmptyDirectory(Path directory) throws IOException {
			if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
					|| !isEmptyDirectory(directory))
				throw new IOException("HTTP outgoing queue server directory is no longer empty");
			Files.delete(directory);
			directoryForcer.force(root);
		}

		private void quarantinePublished(Path directory, Path target, Path pending, Map<String, Path> serverFiles,
				Map<String, Path> quarantined, String id, IOException publicationFailure) throws IOException {
			try {
				try { Files.move(target, pending, StandardCopyOption.ATOMIC_MOVE); }
				catch (java.nio.file.AtomicMoveNotSupportedException unsupported) { Files.move(target, pending); }
				// Record the observed rename before metadata writeback. If the force is
				// indeterminate, a same-process retry must still find this quarantine.
				quarantined.put(id, pending);
				PrivateFilePermissions.ownerOnlyFile(pending);
				directoryForcer.force(directory);
			} catch (IOException quarantineFailure) {
				// A failed quarantine rename leaves the original published name in place on
				// ordinary filesystems. Preserve that observed target for a same-ID retry so
				// persist() confirms it rather than publishing a second file for the ID.
				if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
					quarantined.remove(id);
					serverFiles.put(id, target);
				} else if (Files.isRegularFile(pending, LinkOption.NOFOLLOW_LINKS)) {
					serverFiles.remove(id);
					quarantined.put(id, pending);
				}
				quarantineFailure.addSuppressed(publicationFailure);
				throw new IllegalStateException("HTTP outgoing queue publication could not be quarantined", quarantineFailure);
			}
			throw new DurableFiles.PublishedException(publicationFailure);
		}

		private synchronized void confirm(String serverId, String id) throws IOException {
			requireOwnership();
			Map<String, Path> serverFiles = files.get(serverId);
			Path file = serverFiles == null ? null : serverFiles.get(id);
			if (file == null || Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
				throw new IOException("HTTP outgoing queue delivery is unavailable");
			PrivateFilePermissions.ownerOnlyFile(file);
			directoryForcer.force(file.getParent());
		}

		private synchronized void remove(String serverId, String id) throws IOException {
			requireOwnership();
			Map<String, Path> serverFiles = files.get(serverId);
			if (serverFiles == null) throw new IOException("HTTP outgoing queue acknowledgement is unknown");
			Path file = serverFiles.get(id);
			if (file != null) {
				Files.deleteIfExists(file);
				// Also makes a retried deletion durable if an earlier directory force failed after unlinking the file.
				DurableFiles.forceDirectory(file.getParent());
				serverFiles.remove(id);
			}
			if (serverFiles.isEmpty()) {
				Path directory = root.resolve(serverId).normalize();
				if (!directory.getParent().equals(root) || Files.isSymbolicLink(directory))
					throw new IOException("HTTP outgoing queue server directory is invalid");
				try {
					Files.deleteIfExists(directory);
					directoryForcer.force(root);
					files.remove(serverId);
					quarantinedFiles.remove(serverId);
				} catch (java.nio.file.DirectoryNotEmptyException unexpectedEntry) {
					// The acknowledged delivery is already durably removed; unrelated/tampered entries
					// must not make its acknowledgement permanently unprocessable.
				}
			}
		}

		@Override public synchronized void close() { releaseOwnership(); }

		private void requireOwnership() throws IOException {
			if (ownershipLock == null || !ownershipLock.isValid())
				throw new IOException("HTTP outgoing queue ownership has ended");
		}
		private void claimOwnership(Path sidecar) throws IOException {
			if (Files.isSymbolicLink(sidecar) || Files.exists(sidecar, LinkOption.NOFOLLOW_LINKS)
					&& !Files.isRegularFile(sidecar, LinkOption.NOFOLLOW_LINKS))
				throw new IOException("HTTP outgoing queue ownership lock is unsafe");
			FileChannel channel = FileChannel.open(sidecar, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
					LinkOption.NOFOLLOW_LINKS);
			try {
				PrivateFilePermissions.ownerOnlyFile(sidecar);
				FileLock lock;
				try { lock = channel.tryLock(); }
				catch (OverlappingFileLockException alreadyOwned) { throw new IOException("HTTP outgoing queue is already owned", alreadyOwned); }
				if (lock == null) throw new IOException("HTTP outgoing queue is already owned");
				ownershipChannel = channel;
				ownershipLock = lock;
			} catch (IOException | RuntimeException failure) {
				try { channel.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
				throw failure;
			}
		}
		private void releaseOwnership() {
			FileLock lock = ownershipLock;
			FileChannel channel = ownershipChannel;
			ownershipLock = null;
			ownershipChannel = null;
			if (lock != null) try { lock.release(); } catch (IOException ignored) { }
			if (channel != null) try { channel.close(); } catch (IOException ignored) { }
		}

	}
}
