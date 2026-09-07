package com.bencodez.simpleapi.servercomm.http;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bencodez.simpleapi.servercomm.codec.JsonEnvelope;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HttpBackendSendLifecycleTest {
	@TempDir Path directory;

	@Test
	void pausedSendCannotEnterAfterCloseAndFlushCutsOffNewSends() throws Exception {
		HttpTlsIdentity identity = HttpTlsIdentity.loadOrCreate(directory.resolve("proxy"), "localhost");
		HttpEnrollmentAuthority authority = new HttpEnrollmentAuthority(identity, directory.resolve("authority"));
		try (HttpProxyTransportServer server = new HttpProxyTransportServer(new InetSocketAddress("localhost", 0), identity,
				authority, ignored -> { })) {
			server.start();
			HttpConnectionCode code = authority.createConnectionCode("lobby-1", server.endpoint("localhost"), Duration.ofMinutes(5));
			Path firstDirectory = directory.resolve("close-client");
			HttpBackendTransportConnector.enroll(code, "lobby-1", firstDirectory);
			HttpBackendTransportConnector first = new HttpBackendTransportConnector(firstDirectory, ignored -> { });
			try {
				first.start();
				assertTrue(first.awaitFirstResponse(System.nanoTime() + TimeUnit.SECONDS.toNanos(8)));
				assertPausedSendIsRejectedByClose(first);
			} finally { first.close(); }

			Path secondDirectory = directory.resolve("flush-client");
			HttpConnectionCode secondCode = authority.createConnectionCode("lobby-2", server.endpoint("localhost"), Duration.ofMinutes(5));
			HttpBackendTransportConnector.enroll(secondCode, "lobby-2", secondDirectory);
			try (HttpBackendTransportConnector second = new HttpBackendTransportConnector(secondDirectory, ignored -> { })) {
				second.start();
				assertTrue(second.awaitFirstResponse(System.nanoTime() + TimeUnit.SECONDS.toNanos(8)));
				assertTrue(second.send(JsonEnvelope.builder("accepted-before-flush").build()));
				assertPausedSendIsRejectedByFlush(second);
				assertFalse(second.send(JsonEnvelope.builder("rejected-after-flush").build()));
				assertEquals(0, second.queuedOutgoing());
				second.start();
				assertTrue(second.send(JsonEnvelope.builder("accepted-after-restart").build()));
			}
		}
	}

	@Test
	void restartedConnectorRequiresAResponseFromItsNewRun() throws Exception {
		HttpTlsIdentity identity = HttpTlsIdentity.loadOrCreate(directory.resolve("restart-proxy"), "localhost");
		HttpEnrollmentAuthority authority = new HttpEnrollmentAuthority(identity, directory.resolve("restart-authority"));
		try (HttpProxyTransportServer server = new HttpProxyTransportServer(new InetSocketAddress("localhost", 0), identity,
				authority, ignored -> { })) {
			server.start();
			HttpConnectionCode code = authority.createConnectionCode("lobby-1", server.endpoint("localhost"), Duration.ofMinutes(5));
			Path clientDirectory = directory.resolve("restart-client");
			HttpBackendTransportConnector.enroll(code, "lobby-1", clientDirectory);
			try (HttpBackendTransportConnector connector = new HttpBackendTransportConnector(clientDirectory, ignored -> { })) {
				connector.start();
				assertTrue(connector.awaitFirstResponse(System.nanoTime() + TimeUnit.SECONDS.toNanos(8)));
				assertTrue(connector.flushOutgoing(System.nanoTime() + TimeUnit.SECONDS.toNanos(5)));
				server.close();

				connector.start();
				assertFalse(connector.awaitFirstResponse(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250)),
						"a completed flush must not satisfy the restarted run's first-response wait");
			}
		}
	}

	@Test
	void startOpensSendAdmissionBeforeThePollerCanRun() throws Exception {
		HttpTlsIdentity identity = HttpTlsIdentity.loadOrCreate(directory.resolve("startup-proxy"), "localhost");
		HttpEnrollmentAuthority authority = new HttpEnrollmentAuthority(identity, directory.resolve("startup-authority"));
		try (HttpProxyTransportServer server = new HttpProxyTransportServer(new InetSocketAddress("localhost", 0), identity,
				authority, ignored -> { })) {
			server.start();
			HttpConnectionCode code = authority.createConnectionCode("lobby-1", server.endpoint("localhost"), Duration.ofMinutes(5));
			Path credentials = directory.resolve("startup-client");
			HttpBackendTransportConnector.enroll(code, "lobby-1", credentials);
			try (HttpBackendTransportConnector connector = new HttpBackendTransportConnector(credentials, ignored -> { })) {
				Object state = field("state").get(connector);
				AtomicBoolean running = (AtomicBoolean) field("running").get(connector);
				Thread starter = new Thread(connector::start, "backend-start");
				synchronized (state) {
					starter.start();
					long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
					while (!running.get() && System.nanoTime() < deadline) Thread.onSpinWait();
					assertTrue(running.get());
					assertEquals(null, field("poller").get(connector),
							"the poller must not become runnable before send admission opens");
				}
				starter.join(3000);
				assertFalse(starter.isAlive());
				assertTrue(connector.send(JsonEnvelope.builder("accepted-at-start").build()));
			}
		}
	}

	private static void assertPausedSendIsRejectedByClose(HttpBackendTransportConnector connector) throws Exception {
		Object state = field("state").get(connector);
		AtomicBoolean closing = (AtomicBoolean) field("closing").get(connector);
		AtomicBoolean accepted = new AtomicBoolean(true);
		CountDownLatch senderStarted = new CountDownLatch(1);
		Thread sender = new Thread(() -> {
			senderStarted.countDown();
			accepted.set(connector.send(JsonEnvelope.builder("paused-send").build()));
		}, "paused-backend-send");
		Thread closer = new Thread(connector::close, "backend-close");
		synchronized (state) {
			sender.start();
			assertTrue(senderStarted.await(1, TimeUnit.SECONDS));
			closer.start();
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
			while (!closing.get() && System.nanoTime() < deadline) Thread.onSpinWait();
			assertTrue(closing.get(), "close must transition running before it waits for state");
		}
		sender.join(3000);
		closer.join(3000);
		assertFalse(sender.isAlive());
		assertFalse(closer.isAlive());
		assertFalse(accepted.get());
		assertEquals(0, connector.queuedOutgoing());
	}

	private static void assertPausedSendIsRejectedByFlush(HttpBackendTransportConnector connector) throws Exception {
		Object state = field("state").get(connector);
		AtomicBoolean running = (AtomicBoolean) field("running").get(connector);
		AtomicBoolean accepted = new AtomicBoolean(true), flushed = new AtomicBoolean();
		CountDownLatch senderStarted = new CountDownLatch(1);
		Thread sender = new Thread(() -> {
			senderStarted.countDown();
			accepted.set(connector.send(JsonEnvelope.builder("paused-flush-send").build()));
		}, "paused-flush-send");
		Thread flusher = new Thread(() -> flushed.set(connector.flushOutgoing(
				System.nanoTime() + TimeUnit.SECONDS.toNanos(5))), "backend-flush");
		synchronized (state) {
			sender.start();
			assertTrue(senderStarted.await(1, TimeUnit.SECONDS));
			flusher.start();
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
			while (running.get() && System.nanoTime() < deadline) Thread.onSpinWait();
			assertFalse(running.get(), "flush must cut off admission before it waits for state");
		}
		sender.join(3000);
		flusher.join(6000);
		assertFalse(sender.isAlive());
		assertFalse(flusher.isAlive());
		assertFalse(accepted.get());
		assertTrue(flushed.get());
		assertEquals(0, connector.queuedOutgoing());
	}

	private static Field field(String name) throws Exception {
		Field field = HttpBackendTransportConnector.class.getDeclaredField(name);
		field.setAccessible(true);
		return field;
	}
}
