package tpf;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * An immutable group of consecutive records, the unit that is sealed (encrypted)
 * and, in later sprints, replicated.
 *
 * Binary format (before encryption):
 * All integers are big-endian and all strings are UTF-8.
 *
 * Header (9 bytes)
 *   magic        4 bytes   "TPFC" (0x54504643)
 *   version      1 byte    format version, currently 1
 *   count        4 bytes   number of records that follow (int)
 * Record (repeated 'count' times, 28 bytes + strings)
 *   lsn          8 bytes   long
 *   instante     8 bytes   long, milliseconds since the Unix epoch
 *   autorLen     4 bytes   int, followed by autorLen bytes of 'autor'
 *   canalLen     4 bytes   int, followed by canalLen bytes of 'canal'
 *   textoLen     4 bytes   int, followed by textoLen bytes of 'texto'
 * Padding
 *   zero bytes up to the next multiple of PAD_BYTES (none if already a multiple)
 *
 * The reader stops after count records, so the padding is simply ignored.
 * Padding hides the exact amount of data: after encryption a replica only learns
 * the size rounded up to PAD_BYTES.
 */
public final class Chunk {

    /** "TPFC" in ASCII: identifies a serialized chunk. */
    static final int MAGIC = 0x54504643;
    /** Current version of the binary format. */
    static final byte FORMAT_VERSION = 1;
    /** Size of the header (magic + version + count), in bytes. */
    public static final int HEADER_BYTES = 4 + 1 + 4;
    /** Fixed part of each record: lsn + instante + the three length prefixes. */
    private static final int RECORD_FIXED_BYTES = 8 + 8 + 4 + 4 + 4;

    /** The records of this chunk, in LSN order. Unmodifiable. */
    public final List<Registo> registos;

    public Chunk(List<Registo> registos) {
        if (registos.isEmpty()) {
            throw new IllegalArgumentException("a chunk needs at least one record");
        }
        this.registos = List.copyOf(registos);   // defensive, unmodifiable copy: a chunk never changes
    }

    /** Number of bytes registo takes in the serialized format (without padding). */
    public static int recordBytes(Registo registo) {
        return RECORD_FIXED_BYTES
                + utf8Length(registo.autor)
                + utf8Length(registo.canal)
                + utf8Length(registo.texto);
    }

    /** Serialized size of the header plus all records, before padding. */
    public int payloadBytes() {
        int total = HEADER_BYTES;
        for (Registo registo : registos) {
            total += recordBytes(registo);
        }
        return total;
    }

    public long firstLsn() {
        return registos.get(0).lsn;
    }

    public long lastLsn() {
        return registos.get(registos.size() - 1).lsn;
    }

    /**
     * Serializes the records and appends zero bytes until the length is a multiple
     * of padBytes.
     */
    public byte[] serializar(int padBytes) {
        if (padBytes <= 0) {
            throw new IllegalArgumentException("padBytes must be positive");
        }
        int payload = payloadBytes();
        int padded = roundUp(payload, padBytes);

        // A new ByteBuffer is zero-filled, so the bytes after the payload are already the padding.
        ByteBuffer out = ByteBuffer.allocate(padded);
        out.putInt(MAGIC).put(FORMAT_VERSION).putInt(registos.size());
        for (Registo registo : registos) {
            out.putLong(registo.lsn).putLong(registo.instante);
            putString(out, registo.autor);
            putString(out, registo.canal);
            putString(out, registo.texto);
        }
        return out.array();
    }

    /**
     * Inverse of serializar(int): rebuilds the records and ignores the padding.
     * Throws IllegalArgumentException if the bytes are not a valid serialized chunk.
     */
    public static Chunk ler(byte[] dados) {
        ByteBuffer in = ByteBuffer.wrap(dados);
        try {
            if (in.getInt() != MAGIC) {
                throw new IllegalArgumentException("not a chunk (bad magic number)");
            }
            byte version = in.get();
            if (version != FORMAT_VERSION) {
                throw new IllegalArgumentException("unsupported chunk format version " + version);
            }
            int count = in.getInt();
            // Every record takes at least RECORD_FIXED_BYTES, which bounds a sane count.
            if (count <= 0 || count > in.remaining() / RECORD_FIXED_BYTES) {
                throw new IllegalArgumentException("invalid record count " + count);
            }
            List<Registo> registos = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                long lsn = in.getLong();
                long instante = in.getLong();
                String autor = getString(in);
                String canal = getString(in);
                String texto = getString(in);
                registos.add(new Registo(lsn, instante, autor, canal, texto));
            }
            return new Chunk(registos);   // whatever is left in 'in' is padding
        } catch (BufferUnderflowException e) {
            throw new IllegalArgumentException("truncated chunk", e);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static void putString(ByteBuffer out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.putInt(bytes.length).put(bytes);
    }

    private static String getString(ByteBuffer in) {
        int length = in.getInt();
        if (length < 0 || length > in.remaining()) {
            throw new IllegalArgumentException("invalid string length " + length);
        }
        byte[] bytes = new byte[length];
        in.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    /** Smallest multiple of multiple that is greater than or equal to value. */
    static int roundUp(int value, int multiple) {
        int remainder = value % multiple;
        return remainder == 0 ? value : Math.addExact(value, multiple - remainder);
    }
}
