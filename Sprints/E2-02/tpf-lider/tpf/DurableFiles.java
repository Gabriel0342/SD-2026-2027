package tpf;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Small helpers to write files that survive a crash of the leader at any instant. */
final class DurableFiles {

    /** Suffix of the temporary files used by {@link #writeAtomically(Path, byte[])}. */
    static final String TMP_SUFFIX = ".tmp";

    private DurableFiles() {
    }

    /**
     * Writes {@code data} to {@code target} so that, after a crash, the target either
     * does not exist or holds all of {@code data}: never a partial file.
     *
     * <p>The bytes go to a temporary file first, are forced to disk with
     * {@code fsync}, and only then is the temporary file renamed with
     * {@code ATOMIC_MOVE}.
     */
    static void writeAtomically(Path target, byte[] data) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + TMP_SUFFIX);
        try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            writeFully(channel, data);
            channel.force(true);
        }
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        forceDirectory(target.toAbsolutePath().getParent());
    }

    /** Writes every byte of {@code data}; a single {@code write} call may write fewer bytes. */
    static void writeFully(FileChannel channel, byte[] data) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    /**
     * Forces a directory entry (a newly created or renamed file) to disk. This is
     * required on Linux for the rename to survive a power loss. Windows cannot open a
     * directory as a channel (and NTFS journals metadata anyway), so failures are ignored.
     */
    static void forceDirectory(Path dir) {
        if (dir == null) {
            return;
        }
        try (FileChannel channel = FileChannel.open(dir, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
            // Not supported on this platform: nothing else we can do.
        }
    }
}
