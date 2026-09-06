package com.bencodez.simpleapi.servercomm.http;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bencodez.simpleapi.servercomm.codec.JsonEnvelope;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HttpOutgoingOwnershipTest {
	@TempDir Path directory;

	@Test
	void senderWaitingForBackendMonitorCannotReopenJournalAfterClose() throws Exception {
		Path root = directory.resolve("outgoing");
		HttpTlsIdentity identity = HttpTlsIdentity.loadOrCreate(directory.resolve("proxy"), "localhost");
		HttpEnrollmentAuthority authority = new HttpEnrollmentAuthority(identity, directory.resolve("authority"));
		HttpProxyTransportServer server = new HttpProxyTransportServer(new InetSocketAddress("localhost", 0),
				identity, authority, root, ignored -> { });
		var field = HttpProxyTransportServer.class.getDeclaredField("backends");
		field.setAccessible(true);
		java.util.concurrent.atomic.AtomicBoolean sent = new java.util.concurrent.atomic.AtomicBoolean(true);
		Thread sender = new Thread(() -> sent.set(server.send("lobby-1", JsonEnvelope.builder("close-race").build())));
		try {
			synchronized (field.get(server)) {
				sender.start();
				long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
				while (sender.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.sleep(5);
				org.junit.jupiter.api.Assertions.assertEquals(Thread.State.BLOCKED, sender.getState());
				server.close();
			}
			sender.join(3000);
			assertFalse(sender.isAlive());
			assertFalse(sent.get());
			try (HttpProxyTransportServer successor = new HttpProxyTransportServer(new InetSocketAddress("localhost", 0),
					identity, authority, root, ignored -> { })) {
				org.junit.jupiter.api.Assertions.assertNotNull(successor.backendStateForTest("lobby-1"));
			}
		} finally {
			server.close();
			sender.join(3000);
		}
	}

	@Test
	void outgoingQueueAllowsOneOwnerAndFailsClosedAfterClose() throws Exception {
		Path root = directory.resolve("outgoing");
		HttpProxyTransportServer.DurableOutgoingQueue owner = new HttpProxyTransportServer.DurableOutgoingQueue(root,
				com.bencodez.simpleapi.file.DurableFiles::forceDirectory);
		HttpProxyTransportServer.BackendState state = new HttpProxyTransportServer.BackendState("lobby-1", owner,
				(server, id) -> { });
		HttpTransportProtocol.Delivery delivery = new HttpTransportProtocol.Delivery(UUID.randomUUID().toString(),
				JsonEnvelope.builder("owner").build());
		assertTrue(state.enqueue(delivery));
		assertThrows(IOException.class, () -> new HttpProxyTransportServer.DurableOutgoingQueue(root,
				com.bencodez.simpleapi.file.DurableFiles::forceDirectory));
		owner.close();
		assertThrows(IOException.class, owner::load);
		assertFalse(state.enqueue(new HttpTransportProtocol.Delivery(UUID.randomUUID().toString(),
				JsonEnvelope.builder("closed").build())));
		assertThrows(IOException.class, () -> state.acknowledge(List.of(delivery.id())));
		HttpProxyTransportServer.DurableOutgoingQueue successor = new HttpProxyTransportServer.DurableOutgoingQueue(root,
				com.bencodez.simpleapi.file.DurableFiles::forceDirectory);
		successor.close();
	}

	@Test
	void proxyClaimsOutgoingOwnershipBeforeAnyBackendStateExists() throws Exception {
		Path root = directory.resolve("outgoing");
		HttpTlsIdentity identity = HttpTlsIdentity.loadOrCreate(directory.resolve("proxy"), "localhost");
		HttpEnrollmentAuthority authority = new HttpEnrollmentAuthority(identity, directory.resolve("authority"));
		HttpProxyTransportServer owner = new HttpProxyTransportServer(new InetSocketAddress("localhost", 0), identity,
				authority, root, ignored -> { });
		try {
			assertThrows(IOException.class, () -> new HttpProxyTransportServer(new InetSocketAddress("localhost", 0), identity,
					authority, root, ignored -> { }),
					"even an idle proxy must own the durable outgoing queue exclusively");
		} finally { owner.close(); }
		try (HttpProxyTransportServer successor = new HttpProxyTransportServer(new InetSocketAddress("localhost", 0), identity,
				authority, root, ignored -> { })) { }
	}
}
