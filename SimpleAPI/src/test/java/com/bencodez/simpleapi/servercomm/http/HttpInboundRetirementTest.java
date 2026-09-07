package com.bencodez.simpleapi.servercomm.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
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

	@Test
	void successfulRetirementReclaimsPerJournalOwnershipSidecars() throws Exception {
		Path parent = directory.resolve("incoming");
		Files.createDirectory(parent);
		for (int index = 0; index < 200; index++) {
			HttpInboundDeliveryStore store = HttpInboundDeliveryStore.open(parent, "backend-" + index);
			store.sealAndDeleteIfEmpty();
		}

		try (var files = Files.list(parent)) {
			assertEquals(java.util.List.of(".http-inbound-owners.lock"),
					files.map(path -> path.getFileName().toString()).sorted().toList());
		}
		assertTrue(HttpInboundDeliveryStore.discover(parent).isEmpty());
	}

	@Test
	void sidecarCleanupFailureStillCompletesRetirement() throws Exception {
		Path parent = directory.resolve("incoming");
		Files.createDirectory(parent);
		HttpInboundDeliveryStore store = HttpInboundDeliveryStore.open(parent, "lobby-1");
		Path sidecar = parent.resolve(".http-inbound-owner-lobby-1.lock");
		try (var files = org.mockito.Mockito.mockStatic(com.bencodez.simpleapi.file.DurableFiles.class,
				org.mockito.Mockito.CALLS_REAL_METHODS)) {
			files.when(() -> com.bencodez.simpleapi.file.DurableFiles.deleteIfExists(sidecar))
					.thenThrow(new IOException("injected sidecar cleanup failure"));
			store.sealAndDeleteIfEmpty();
		}

		HttpInboundDeliveryStore successor = HttpInboundDeliveryStore.open(parent, "lobby-1");
		successor.sealAndDeleteIfEmpty();
	}

	@Test
	void retirementCompletesWhenOwnershipReleaseOrCloseFails() throws Exception {
		Path parent = directory.resolve("incoming");
		Files.createDirectory(parent);
		HttpInboundDeliveryStore store = HttpInboundDeliveryStore.open(parent, "lobby-1");
		FileLock originalLock = (FileLock) field("ownershipLock").get(store);
		FileChannel originalChannel = (FileChannel) field("ownershipChannel").get(store);
		originalLock.release();
		originalChannel.close();
		FileLock failingLock = org.mockito.Mockito.mock(FileLock.class);
		FileChannel failingChannel = org.mockito.Mockito.mock(FileChannel.class);
		org.mockito.Mockito.when(failingLock.isValid()).thenReturn(true);
		org.mockito.Mockito.doThrow(new IOException("injected release failure")).when(failingLock).release();
		org.mockito.Mockito.doThrow(new IOException("injected close failure")).when(failingChannel).close();
		field("ownershipLock").set(store, failingLock);
		field("ownershipChannel").set(store, failingChannel);

		assertDoesNotThrow(store::sealAndDeleteIfEmpty);
		assertThrows(IOException.class, () -> store.reserve(UUID.randomUUID().toString()));
		HttpInboundDeliveryStore successor = HttpInboundDeliveryStore.open(parent, "lobby-1");
		successor.sealAndDeleteIfEmpty();
	}

	private static Field field(String name) throws Exception {
		Field field = HttpInboundDeliveryStore.class.getDeclaredField(name);
		field.setAccessible(true);
		return field;
	}
}
