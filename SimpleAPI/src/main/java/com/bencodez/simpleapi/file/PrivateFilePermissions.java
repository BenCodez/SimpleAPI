package com.bencodez.simpleapi.file;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Enforces and verifies owner-only access for persisted private material. */
public final class PrivateFilePermissions {
	private static final Set<PosixFilePermission> OWNER_FILE = EnumSet.of(
			PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
	private static final Set<PosixFilePermission> OWNER_DIRECTORY = EnumSet.of(
			PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
	private static final Set<AclEntryPermission> OWNER_ACL = EnumSet.allOf(AclEntryPermission.class);

	private PrivateFilePermissions() { }

	/** Enforces 0600-equivalent access, or fails when the provider cannot prove it. */
	public static void ownerOnlyFile(Path path) throws IOException {
		enforce(path, OWNER_FILE);
	}

	/** Enforces 0700-equivalent access, or fails when the provider cannot prove it. */
	public static void ownerOnlyDirectory(Path path) throws IOException {
		enforce(path, OWNER_DIRECTORY);
	}

	private static void enforce(Path path, Set<PosixFilePermission> permissions) throws IOException {
		if (path == null) throw new IllegalArgumentException("Private path is required");
		if (Files.isSymbolicLink(path)) throw new IOException("Refusing symbolic link for private storage: " + path);
		try {
			Files.setPosixFilePermissions(path, permissions);
			if (!Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).equals(permissions))
				throw new IOException("Could not verify owner-only POSIX permissions for " + path);
			return;
		} catch (UnsupportedOperationException unsupported) {
			// A non-POSIX provider must expose an ACL that can be reduced and verified.
		}
		enforceWithAcl(path);
	}

	private static void enforceWithAcl(Path path) throws IOException {
		try {
			AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
			if (view == null) throw new IOException("Owner-only private storage is unsupported for " + path);
			enforceWithAcl(view, path);
		} catch (UnsupportedOperationException unsupported) {
			throw new IOException("Owner-only private storage is unsupported for " + path, unsupported);
		}
	}

	/** Package-visible for deterministic ACL-provider tests. */
	static void enforceWithAcl(AclFileAttributeView view, Path path) throws IOException {
		try {
			if (view == null) throw new IOException("Owner-only private storage is unsupported for " + path);
			UserPrincipal owner = view.getOwner();
			if (owner == null || owner instanceof GroupPrincipal)
				throw new IOException("Private storage owner is not an individual account for " + path);
			AclEntry ownerEntry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
					.setPermissions(OWNER_ACL).build();
			view.setAcl(List.of(ownerEntry));
			List<AclEntry> acl = view.getAcl();
			if (acl.size() != 1 || !isVerifiedOwnerEntry(acl.get(0), owner))
				throw new IOException("Could not verify owner-only ACL permissions for " + path);
		} catch (UnsupportedOperationException unsupported) {
			throw new IOException("Owner-only private storage is unsupported for " + path, unsupported);
		}
	}

	private static boolean isVerifiedOwnerEntry(AclEntry entry, UserPrincipal owner) {
		return entry.type() == AclEntryType.ALLOW && owner.equals(entry.principal()) && entry.flags().isEmpty()
				&& entry.permissions().equals(OWNER_ACL);
	}
}
