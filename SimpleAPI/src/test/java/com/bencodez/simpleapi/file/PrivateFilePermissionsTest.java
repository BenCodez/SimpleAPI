package com.bencodez.simpleapi.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PrivateFilePermissionsTest {

	@TempDir
	Path tempDir;

	@Test
	void fileIsOwnerReadWriteOnlyOnPosixProvider() throws Exception {
		Path file = Files.createFile(tempDir.resolve("secret"));
		Assumptions.assumeTrue(Files.getFileAttributeView(file, PosixFileAttributeView.class) != null);

		PrivateFilePermissions.ownerOnlyFile(file);

		assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
				Files.getPosixFilePermissions(file));
	}

	@Test
	void directoryIsOwnerReadWriteExecuteOnlyOnPosixProvider() throws Exception {
		Path directory = Files.createDirectory(tempDir.resolve("private"));
		Assumptions.assumeTrue(Files.getFileAttributeView(directory, PosixFileAttributeView.class) != null);

		PrivateFilePermissions.ownerOnlyDirectory(directory);

		assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
				PosixFilePermission.OWNER_EXECUTE), Files.getPosixFilePermissions(directory));
	}

	@Test
	void aclReplacementLeavesOnlyOwnerAllowEntry() throws Exception {
		Path file = tempDir.resolve("acl-secret");
		UserPrincipal owner = mock(UserPrincipal.class);
		AclFileAttributeView acl = mock(AclFileAttributeView.class);
		when(acl.getOwner()).thenReturn(owner);
		when(acl.getAcl()).thenReturn(List.of(allow(owner), allow(mock(UserPrincipal.class))));
		try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
			stubAcl(files, file, acl);
			doAnswer(invocation -> {
				when(acl.getAcl()).thenReturn(invocation.getArgument(0));
				return null;
			}).when(acl).setAcl(org.mockito.ArgumentMatchers.anyList());

			PrivateFilePermissions.ownerOnlyFile(file);

			var replacement = org.mockito.ArgumentCaptor.forClass(List.class);
			verify(acl).setAcl(replacement.capture());
			assertEquals(List.of(allow(owner)), replacement.getValue());
		}
	}

	@Test
	void ignoredSetAclOrRemainingNonOwnerAccessIsRejected() throws Exception {
		Path file = tempDir.resolve("bad-acl");
		UserPrincipal owner = mock(UserPrincipal.class);
		AclFileAttributeView acl = mock(AclFileAttributeView.class);
		when(acl.getOwner()).thenReturn(owner);
		when(acl.getAcl()).thenReturn(List.of(allow(owner), allow(mock(UserPrincipal.class))));
		doAnswer(invocation -> null).when(acl).setAcl(org.mockito.ArgumentMatchers.anyList());
		try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
			stubAcl(files, file, acl);

			assertThrows(IOException.class, () -> PrivateFilePermissions.ownerOnlyFile(file));
		}
	}

	@Test
	void missingAclAndPosixSupportIsRejected() throws Exception {
		Path file = tempDir.resolve("unsupported");
		try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
			files.when(() -> Files.setPosixFilePermissions(eq(file), org.mockito.ArgumentMatchers.anySet()))
					.thenThrow(new UnsupportedOperationException());
			files.when(() -> Files.getFileAttributeView(file, PosixFileAttributeView.class)).thenReturn(null);
			files.when(() -> Files.getFileAttributeView(file, AclFileAttributeView.class)).thenReturn(null);
			files.when(() -> Files.getFileAttributeView(file, AclFileAttributeView.class,
					java.nio.file.LinkOption.NOFOLLOW_LINKS)).thenReturn(null);
			assertThrows(IOException.class, () -> PrivateFilePermissions.ownerOnlyFile(file));
		}
	}

	@Test
	void groupOwnerIsRejected() throws Exception {
		Path file = tempDir.resolve("group-owner");
		AclFileAttributeView acl = mock(AclFileAttributeView.class);
		when(acl.getOwner()).thenReturn(mock(GroupPrincipal.class));
		try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
			stubAcl(files, file, acl);
			assertThrows(IOException.class, () -> PrivateFilePermissions.ownerOnlyFile(file));
		}
	}

	@Test
	void aclReadbackFailureIsRejected() throws Exception {
		Path file = tempDir.resolve("readback-failure");
		UserPrincipal owner = mock(UserPrincipal.class);
		AclFileAttributeView acl = mock(AclFileAttributeView.class);
		when(acl.getOwner()).thenReturn(owner);
		when(acl.getAcl()).thenThrow(new IOException("readback"));
		try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
			stubAcl(files, file, acl);
			assertThrows(IOException.class, () -> PrivateFilePermissions.ownerOnlyFile(file));
		}
	}

	@Test
	void unsupportedAclMutationIsRejectedAsIoException() throws Exception {
		Path file = tempDir.resolve("acl-unsupported-mutation");
		UserPrincipal owner = mock(UserPrincipal.class);
		AclFileAttributeView acl = mock(AclFileAttributeView.class);
		when(acl.getOwner()).thenReturn(owner);
		doAnswer(invocation -> { throw new UnsupportedOperationException("ACL mutation"); })
				.when(acl).setAcl(org.mockito.ArgumentMatchers.anyList());
		try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
			stubAcl(files, file, acl);
			assertThrows(IOException.class, () -> PrivateFilePermissions.ownerOnlyFile(file));
		}
	}

	@Test
	void symlinkIsRejectedWithoutChangingReferentPermissions() throws Exception {
		Path target = Files.createFile(tempDir.resolve("referent"));
		Assumptions.assumeTrue(Files.getFileAttributeView(target, PosixFileAttributeView.class) != null);
		Set<PosixFilePermission> original = Set.of(PosixFilePermission.OWNER_READ,
				PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_READ);
		Files.setPosixFilePermissions(target, original);
		Path link = tempDir.resolve("referent-link");
		try {
			Files.createSymbolicLink(link, target.getFileName());
		} catch (UnsupportedOperationException | IOException unavailable) {
			Assumptions.assumeTrue(false, "symbolic links unavailable: " + unavailable);
			return;
		}

		assertThrows(IOException.class, () -> PrivateFilePermissions.ownerOnlyFile(link));
		assertEquals(original, Files.getPosixFilePermissions(target));
	}

	private static AclEntry allow(UserPrincipal principal) {
		return AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(principal)
				.setPermissions(EnumSet.allOf(AclEntryPermission.class)).setFlags(Set.of()).build();
	}

	private static void stubAcl(org.mockito.MockedStatic<Files> files, Path path, AclFileAttributeView acl) {
		files.when(() -> Files.setPosixFilePermissions(eq(path), org.mockito.ArgumentMatchers.anySet()))
				.thenThrow(new UnsupportedOperationException());
		files.when(() -> Files.getFileAttributeView(path, PosixFileAttributeView.class)).thenReturn(null);
		files.when(() -> Files.getFileAttributeView(path, AclFileAttributeView.class)).thenReturn(acl);
		files.when(() -> Files.getFileAttributeView(path, AclFileAttributeView.class,
				java.nio.file.LinkOption.NOFOLLOW_LINKS)).thenReturn(acl);
	}
}
