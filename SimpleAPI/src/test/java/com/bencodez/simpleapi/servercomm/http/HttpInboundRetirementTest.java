package com.bencodez.simpleapi.servercomm.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HttpInboundRetirementTest {
	@TempDir Path directory;

	@Test
	void failedRetirementKeepsOwnershipAndRestoresRootOnNextWrite() throws Exception {
		Path parent = directory.resolve("incoming");
		Files.createDirectory(parent);
		HttpInboundDeliveryStore store = HttpInboundDeliveryStore.open(parent, "lobby-1");
		AtomicBoolean fail = new AtomicBoolean(true);
		try (var forces = org.mockito.Mockito.mockStatic(com.bencodez.simpleapi.file.DurableFiles.class,
				org.mockito.Mockito.CALLS_REAL_METHODS)) {
			forces.when(() -> com.bencodez.simpleapi.file.DurableFiles.forceDirectory(parent)).thenAnswer(call -> {
				if (fail.getAndSet(false)) throw new IOException("injected retirement force failure");
				return call.callRealMethod();
			});
			assertThrows(IOException.class, store::sealAndDeleteIfEmpty);
			assertThrows(IOException.class, () -> HttpInboundDeliveryStore.open(parent, "lobby-1"));
			String id = UUID.randomUUID().toString();
			store.reserve(id);
			store.markRunning(id);
			assertEquals(HttpInboundDeliveryStore.State.RUNNING, store.state(id));
		}
		store.seal();
	}

	@Test
	void failedRetirementCanRetryAfterDeleteFailure() throws Exception {
		Path parent = directory.resolve("incoming");
		Files.createDirectory(parent);
		HttpInboundDeliveryStore store = HttpInboundDeliveryStore.open(parent, "lobby-1");
		Path root = parent.resolve("lobby-1");
		AtomicBoolean fail = new AtomicBoolean(true);
		try (var files = org.mockito.Mockito.mockStatic(Files.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
			files.when(() -> Files.delete(root)).thenAnswer(call -> {
				if (fail.getAndSet(false)) throw new IOException("injected retirement delete failure");
				return call.callRealMethod();
			});
			// CALLS_REAL_METHODS executes the unstubbed delete while registering it.
			Files.createDirectory(root);
			assertThrows(IOException.class, store::sealAndDeleteIfEmpty);
			assertThrows(IOException.class, () -> HttpInboundDeliveryStore.open(parent, "lobby-1"));
			store.sealAndDeleteIfEmpty();
		}
		HttpInboundDeliveryStore successor = HttpInboundDeliveryStore.open(parent, "lobby-1");
		successor.seal();
	}
}
