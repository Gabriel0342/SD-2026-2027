package tpf;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The leader's folder of sealed chunks, {@code selados/}. Each chunk is stored as two files:
 * <ul>
 *   <li>{@code <chunkId>.bin}: the ciphertext (GCM tag included), so the file name is
 *       the SHA-256 of the file content;</li>
 *   <li>{@code <chunkId>.hdr}: one text line {@code keyVersion;salt;nonce}, with the
 *       key version in decimal and the salt and nonce in Base64.</li>
 * </ul>
 * Both files contain only what a replica will be allowed to see: no plaintext, no LSNs.
 */
final class SealedStore {

    static final String BIN = ".bin";
    static final String HDR = ".hdr";

    /** A chunk id is a SHA-256 in lowercase hexadecimal. Checked before building any path from it. */
    private static final Pattern CHUNK_ID = Pattern.compile("[0-9a-f]{64}");

    private final Path dir;

    /** Opens the folder, creating it if needed. */
    SealedStore(Path dir) throws IOException {
        this.dir = dir;
        Files.createDirectories(dir);
    }

    /**
     * Removes temporary files left by a crash in the middle of {@link #write}: those
     * chunks were never committed. Only the leader calls this, at startup, because a
     * temporary file may belong to a write that is still in progress.
     */
    void deleteLeftoverTempFiles() throws IOException {
        try (DirectoryStream<Path> leftovers = Files.newDirectoryStream(dir, "*" + DurableFiles.TMP_SUFFIX)) {
            for (Path tmp : leftovers) {
                Files.deleteIfExists(tmp);
            }
        }
    }

    Path dir() {
        return dir;
    }

    Path binPath(String chunkId) {
        return dir.resolve(checkId(chunkId) + BIN);
    }

    Path hdrPath(String chunkId) {
        return dir.resolve(checkId(chunkId) + HDR);
    }

    /**
     * Stores a sealed chunk. The header is written first and the {@code .bin} last, so
     * the presence of the {@code .bin} file is the commit point: after a crash, a chunk
     * either exists completely or (at most) leaves an orphan {@code .hdr} behind.
     */
    void write(ChunkCrypto.Sealed sealed) throws IOException {
        DurableFiles.writeAtomically(hdrPath(sealed.id), headerLine(sealed).getBytes(StandardCharsets.US_ASCII));
        DurableFiles.writeAtomically(binPath(sealed.id), sealed.ciphertext);
    }

    /** Reads a chunk back from its {@code .bin} and {@code .hdr} files (without decrypting it). */
    ChunkCrypto.Sealed read(String chunkId) throws IOException {
        byte[] ciphertext = Files.readAllBytes(binPath(chunkId));
        String header = Files.readString(hdrPath(chunkId), StandardCharsets.US_ASCII).trim();
        String[] fields = header.split(";", -1);
        if (fields.length != 3) {
            throw new IOException("malformed header " + hdrPath(chunkId) + ": expected keyVersion;salt;nonce");
        }
        try {
            int keyVersion = Integer.parseInt(fields[0]);
            byte[] salt = Base64.getDecoder().decode(fields[1]);
            byte[] nonce = Base64.getDecoder().decode(fields[2]);
            return new ChunkCrypto.Sealed(chunkId, keyVersion, salt, nonce, ciphertext);
        } catch (IllegalArgumentException e) {
            throw new IOException("malformed header " + hdrPath(chunkId) + ": " + e.getMessage(), e);
        }
    }

    /** Ids of all committed chunks (those whose {@code .bin} exists), in file-name order. */
    List<String> ids() throws IOException {
        List<String> ids = new ArrayList<>();
        try (DirectoryStream<Path> bins = Files.newDirectoryStream(dir, "*" + BIN)) {
            for (Path bin : bins) {
                String name = bin.getFileName().toString();
                String id = name.substring(0, name.length() - BIN.length());
                if (CHUNK_ID.matcher(id).matches()) {
                    ids.add(id);
                }
            }
        }
        Collections.sort(ids);
        return ids;
    }

    /** The {@code .hdr} line: {@code keyVersion;Base64(salt);Base64(nonce)}. */
    static String headerLine(ChunkCrypto.Sealed sealed) {
        Base64.Encoder b64 = Base64.getEncoder();
        return sealed.keyVersion + ";" + b64.encodeToString(sealed.salt) + ";" + b64.encodeToString(sealed.nonce) + "\n";
    }

    private static String checkId(String chunkId) {
        if (!CHUNK_ID.matcher(chunkId).matches()) {
            throw new IllegalArgumentException("invalid chunk id: " + chunkId);
        }
        return chunkId;
    }
}
