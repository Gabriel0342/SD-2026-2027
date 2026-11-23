package tpf;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.SecureRandom;
import java.util.List;

/**
 * Loads the leader's master key (MK) from master.key, creating it on the
 * very first start.
 *
 * The MK never leaves the leader: every chunk key is derived from it, so whoever
 * holds this file can read the whole archive, and whoever loses it loses the archive.
 * Keep a backup outside the repository (the .gitignore already excludes it).
 */
public final class MasterKey {

    /** AES-256 / HKDF-SHA256 input key size, in bytes. */
    public static final int SIZE_BYTES = 32;

    private MasterKey() {
    }

    /**
     * Returns the 32-byte master key stored in file.
     *
     *   - If the file does not exist, a new key is generated with SecureRandom
     *     and stored readable by its owner only.
     *   - If the file exists but does not hold exactly 32 bytes, the leader refuses to
     *     start: silently replacing it would make every sealed chunk unreadable.
     */
    public static byte[] loadOrCreate(Path file) throws IOException {
        if (Files.notExists(file)) {
            byte[] key = new byte[SIZE_BYTES];
            new SecureRandom().nextBytes(key);
            create(file, key);
            System.out.println("[master.key] generated a new master key at " + file.toAbsolutePath()
                    + " -- keep a backup outside the repository!");
            return key;
        }

        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("startup refused: " + file + " is not a regular file");
        }
        long size = Files.size(file);
        if (size != SIZE_BYTES) {
            throw new IllegalStateException("startup refused: " + file + " has " + size
                    + " bytes, but a master key must have exactly " + SIZE_BYTES + " bytes");
        }
        return Files.readAllBytes(file);
    }

    /**
     * Writes the key through a temporary file and an atomic rename, so that a crash
     * never leaves a truncated master.key behind, and then restricts the
     * permissions to "read-only for the owner" (the equivalent of chmod 400).
     */
    private static void create(Path file, byte[] key) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.deleteIfExists(tmp);

        boolean posix = isPosix();
        if (posix) {
            // Create the temporary file already private (rw-------) so the key is never world-readable.
            Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        }
        try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buffer = ByteBuffer.wrap(key);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);

        if (posix) {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--------"));
        } else {
            restrictToOwnerOnWindows(file);
        }
    }

    private static boolean isPosix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    /**
     * Windows has no POSIX permission bits, so the file's ACL is replaced by a single
     * entry that lets its owner read it (and delete it, as chmod 400 would
     * still allow on Linux). Nobody else gets any access.
     */
    private static void restrictToOwnerOnWindows(Path file) {
        AclFileAttributeView view = Files.getFileAttributeView(file, AclFileAttributeView.class);
        if (view == null) {
            System.err.println("[master.key] WARNING: could not restrict the permissions of " + file);
            return;
        }
        try {
            UserPrincipal owner = view.getOwner();
            AclEntry ownerReadOnly = AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(owner)
                    .setPermissions(
                            AclEntryPermission.READ_DATA,
                            AclEntryPermission.READ_ATTRIBUTES,
                            AclEntryPermission.READ_NAMED_ATTRS,
                            AclEntryPermission.READ_ACL,
                            AclEntryPermission.SYNCHRONIZE,
                            AclEntryPermission.DELETE)
                    .build();
            view.setAcl(List.of(ownerReadOnly));
        } catch (IOException e) {
            System.err.println("[master.key] WARNING: could not restrict the permissions of " + file + ": " + e);
        }
    }
}
