package com.bencodez.simpleapi.servercomm.http;

import com.bencodez.simpleapi.file.DurableFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Crash-durable state for proxy deliveries around a non-transactional application callback. */
final class HttpInboundDeliveryStore {
	private static final String DIRECTORY = "http-transport-inbound-deliveries";
	private static final int MAX_ENTRIES = HttpTransportProtocol.MAX_QUEUE;
	private final Path root;
	private final Map<String, State> entries = new LinkedHashMap<>();
	// A rename completed, but its directory entry still needs a successful fsync.
	private final Set<String> unconfirmedReservations = new HashSet<>();
	private final Set<String> unconfirmedCompletions = new HashSet<>();
	private boolean sealed;

	HttpInboundDeliveryStore(Path credentialDirectory) throws IOException {
		this(credentialDirectory, DIRECTORY);
	}

	static HttpInboundDeliveryStore open(Path parent, String directoryName) throws IOException {
		return new HttpInboundDeliveryStore(parent, directoryName);
	}

	private HttpInboundDeliveryStore(Path parent, String directoryName) throws IOException {
		Path credentials = parent.toAbsolutePath().normalize();
		if (Files.isSymbolicLink(credentials) || !Files.isDirectory(credentials, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("HTTP credential directory is unsafe");
		if (directoryName == null || !directoryName.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))
			throw new IOException("HTTP inbound delivery directory name is invalid");
		ownerOnlyDirectory(credentials);
		root = credentials.resolve(directoryName).normalize();
		if (!root.getParent().equals(credentials)) throw new IOException("HTTP inbound delivery directory is invalid");
		try { Files.createDirectory(root); }
		catch (java.nio.file.FileAlreadyExistsException existing) { }
		requireRoot();
		ownerOnlyDirectory(root);
		// Retry a parent fsync that may have failed after creating this root.
		DurableFiles.forceDirectory(credentials);
		load();
	}

	synchronized State state(String id) { return entries.get(canonical(id)); }

	synchronized void reserve(String id) throws IOException {
		requireWritable();
		id = canonical(id);
		State existing = entries.get(id);
		if (existing == State.RESERVED) {
			confirmReserved(id);
			return;
		}
		if (existing != null) throw new IOException("HTTP inbound delivery fence is already active");
		if (entries.size() >= MAX_ENTRIES) throw new IOException("HTTP inbound delivery fence is full");
		requireRoot();
		Path target = file(id, State.RESERVED);
		if (Files.exists(target, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("HTTP inbound delivery fence is inconsistent");
		Path temporary = Files.createTempFile(root, ".pending-", ".tmp");
		try {
			ownerOnlyFile(temporary);
			Files.writeString(temporary, id, StandardCharsets.US_ASCII, StandardOpenOption.TRUNCATE_EXISTING);
			DurableFiles.forceFile(temporary);
			move(temporary, target);
			try {
				ownerOnlyFile(target);
				DurableFiles.forceDirectory(root);
			} catch (IOException postPublicationFailure) {
				entries.put(id, State.RESERVED);
				unconfirmedReservations.add(id);
				throw new DurableFiles.PublishedException(postPublicationFailure);
			}
			entries.put(id, State.RESERVED);
		} finally { Files.deleteIfExists(temporary); }
	}

	/** Records receipt of a remote acknowledgement before its confirmation is sent. */
	synchronized void recordCompleted(String id) throws IOException {
		requireWritable();
		id = canonical(id);
		if (entries.get(id) == State.COMPLETED) {
			confirmCompleted(id);
			return;
		}
		if (entries.containsKey(id) || entries.size() >= MAX_ENTRIES)
			throw new IOException("HTTP acknowledgement confirmation fence is full");
		requireRoot();
		Path target = file(id, State.COMPLETED);
		Path temporary = Files.createTempFile(root, ".pending-", ".tmp");
		try {
			ownerOnlyFile(temporary);
			Files.writeString(temporary, id, StandardCharsets.US_ASCII, StandardOpenOption.TRUNCATE_EXISTING);
			DurableFiles.forceFile(temporary);
			move(temporary, target);
			try {
				ownerOnlyFile(target);
				DurableFiles.forceDirectory(root);
			}
			catch (IOException postPublicationFailure) {
				entries.put(id, State.COMPLETED);
				unconfirmedCompletions.add(id);
				throw new DurableFiles.PublishedException(postPublicationFailure);
			}
			entries.put(id, State.COMPLETED);
		} finally { Files.deleteIfExists(temporary); }
	}

	synchronized void markRunning(String id) throws IOException { transition(id, State.RESERVED, State.RUNNING); }
	synchronized void markCompleted(String id) throws IOException { transition(id, State.RUNNING, State.COMPLETED); }
	synchronized void seal() { sealed = true; }
	synchronized void sealAndDeleteIfEmpty() throws IOException {
		if (!entries.isEmpty()) throw new IOException("HTTP inbound delivery store is not empty");
		requireRoot();
		try (DirectoryStream<Path> files = Files.newDirectoryStream(root)) {
			if (files.iterator().hasNext()) throw new IOException("HTTP inbound delivery directory is not empty");
		}
		sealed = true;
		Path parent = root.getParent();
		Files.delete(root);
		DurableFiles.forceDirectory(parent);
	}

	synchronized void remove(String id) throws IOException {
		requireWritable();
		id = canonical(id);
		State state = entries.get(id);
		if (state == null) return;
		requireRoot();
		DurableFiles.deleteIfExists(file(id, state));
		entries.remove(id);
		unconfirmedReservations.remove(id);
		unconfirmedCompletions.remove(id);
	}

	synchronized Map<String, State> snapshot() { return Map.copyOf(entries); }

	private void transition(String id, State expected, State replacement) throws IOException {
		requireWritable();
		id = canonical(id);
		if (expected == State.RESERVED) confirmReserved(id);
		if (replacement == State.COMPLETED && entries.get(id) == replacement) {
			confirmCompleted(id);
			return;
		}
		if (entries.get(id) != expected) throw new IOException("HTTP inbound delivery fence state is invalid");
		requireRoot();
		Path source = file(id, expected), target = file(id, replacement);
		if (Files.isSymbolicLink(source) || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
				|| Files.exists(target, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("HTTP inbound delivery fence state is unsafe");
		move(source, target);
		try { DurableFiles.forceDirectory(root); }
		catch (IOException postPublicationFailure) {
			entries.put(id, replacement);
			if (replacement == State.COMPLETED) unconfirmedCompletions.add(id);
			throw new DurableFiles.PublishedException(postPublicationFailure);
		}
		entries.put(id, replacement);
	}

	/** Retries the directory fsync required before exposing a reservation to a callback. */
	synchronized void confirmReserved(String id) throws IOException {
		id = canonical(id);
		if (entries.get(id) != State.RESERVED) throw new IOException("HTTP inbound delivery fence state is invalid");
		if (!unconfirmedReservations.contains(id)) return;
		requireRoot();
		Path target = file(id, State.RESERVED);
		if (Files.isSymbolicLink(target) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
				|| Files.size(target) > 64L
				|| !Files.readString(target, StandardCharsets.US_ASCII).equals(id))
			throw new IOException("HTTP inbound delivery fence state is unsafe");
		DurableFiles.forceDirectory(root);
		unconfirmedReservations.remove(id);
	}

	/** Retries the directory fsync required before exposing a completed delivery for acknowledgement. */
	synchronized void confirmCompleted(String id) throws IOException {
		id = canonical(id);
		if (entries.get(id) != State.COMPLETED) throw new IOException("HTTP inbound delivery fence state is invalid");
		if (!unconfirmedCompletions.contains(id)) return;
		requireRoot();
		Path target = file(id, State.COMPLETED);
		if (Files.isSymbolicLink(target) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
				|| Files.size(target) > 64L
				|| !Files.readString(target, StandardCharsets.US_ASCII).equals(id))
			throw new IOException("HTTP inbound delivery fence state is unsafe");
		DurableFiles.forceDirectory(root);
		unconfirmedCompletions.remove(id);
	}

	private void load() throws IOException {
		try (DirectoryStream<Path> files = Files.newDirectoryStream(root)) {
			for (Path file : files) {
				String name = file.getFileName().toString();
				if (name.startsWith(".pending-") && name.endsWith(".tmp") && !Files.isSymbolicLink(file)
						&& Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
					DurableFiles.deleteIfExists(file);
					continue;
				}
				State state = State.fromFileName(name);
				if (state == null || Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
						|| Files.size(file) > 64L)
					throw new IOException("HTTP inbound delivery fence contains an invalid entry");
				String id;
				try { id = canonical(name.substring(0, name.length() - state.suffix.length())); }
				catch (IllegalArgumentException invalid) {
					throw new IOException("HTTP inbound delivery fence entry is invalid", invalid);
				}
				if (!name.equals(id + state.suffix) || !Files.readString(file, StandardCharsets.US_ASCII).equals(id))
					throw new IOException("HTTP inbound delivery fence entry is invalid");
				State existing = entries.get(id);
				if (existing == null) entries.put(id, state);
				else {
					// A provider without atomic moves may expose both names after an
					// interrupted transition. Preserve the furthest fail-closed state:
					// RUNNING never replays, and COMPLETED alone may be acknowledged.
					State retained = existing.ordinal() >= state.ordinal() ? existing : state;
					State obsolete = retained == existing ? state : existing;
					DurableFiles.deleteIfExists(file(id, obsolete));
					entries.put(id, retained);
				}
				if (entries.get(id) == State.COMPLETED) unconfirmedCompletions.add(id);
				if (entries.get(id) == State.RESERVED) unconfirmedReservations.add(id);
				if (entries.size() > MAX_ENTRIES) throw new IOException("HTTP inbound delivery fence exceeds its bound");
			}
		}
	}

	private Path file(String id, State state) { return root.resolve(id + state.suffix); }
	private void requireWritable() throws IOException {
		if (sealed) throw new IOException("HTTP inbound delivery store ownership has ended");
	}
	private static void move(Path source, Path target) throws IOException {
		try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE); }
		catch (java.nio.file.AtomicMoveNotSupportedException unsupported) { Files.move(source, target); }
	}
	private static String canonical(String id) {
		if (id == null) throw new IllegalArgumentException("HTTP delivery id is invalid");
		String canonical = UUID.fromString(id).toString();
		if (!canonical.equals(id)) throw new IllegalArgumentException("HTTP delivery id is not canonical");
		return canonical;
	}
	private void requireRoot() throws IOException {
		if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("HTTP inbound delivery directory is unsafe");
	}
	private static void ownerOnlyFile(Path path) throws IOException {
		try { Files.setPosixFilePermissions(path, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)); }
		catch (UnsupportedOperationException ignored) { }
	}
	private static void ownerOnlyDirectory(Path path) throws IOException {
		try { Files.setPosixFilePermissions(path, EnumSet.of(PosixFilePermission.OWNER_READ,
				PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)); }
		catch (UnsupportedOperationException ignored) { }
	}

	enum State {
		RESERVED(".reserved"), RUNNING(".running"), COMPLETED(".completed");
		private final String suffix;
		State(String suffix) { this.suffix = suffix; }
		private static State fromFileName(String name) {
			for (State state : values()) if (name.endsWith(state.suffix)) return state;
			return null;
		}
	}
}
