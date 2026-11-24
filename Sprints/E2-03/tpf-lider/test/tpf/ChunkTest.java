package tpf;

import static tpf.TestSupport.check;
import static tpf.TestSupport.expectThrows;
import static tpf.TestSupport.section;

import java.util.Arrays;
import java.util.List;

/** Serialization of chunks (binary format + padding) and of registos.log lines. */
final class ChunkTest {

    private static final int PAD = 4096;

    private ChunkTest() {
    }

    static void run() {
        section("ChunkTest");

        List<Registo> registos = List.of(
                new Registo(1, 1_700_000_000_000L, "alice", "arquivo", "olá, mundo"),
                new Registo(2, 1_700_000_000_001L, "bob", "arquivo", ""),                       // empty text
                new Registo(3, 1_700_000_000_002L, "carol", "arquivo", "a;b\nc — ünïcödé 🚀"));  // separators, unicode
        Chunk chunk = new Chunk(registos);

        byte[] padded = chunk.serializar(PAD);
        check(padded.length % PAD == 0, "serializar pads to a multiple of PAD_BYTES (" + padded.length + " bytes)");
        check(padded.length == Chunk.roundUp(chunk.payloadBytes(), PAD), "padding goes up to the next multiple only");
        check(allZero(padded, chunk.payloadBytes(), padded.length), "the padding bytes are zeros");

        Chunk back = Chunk.ler(padded);
        check(back.registos.equals(registos), "ler rebuilds exactly the same records and ignores the padding");
        check(back.firstLsn() == 1 && back.lastLsn() == 3, "firstLsn/lastLsn give the LSN range");

        Chunk unpadded = Chunk.ler(chunk.serializar(1));
        check(unpadded.registos.equals(registos), "ler does not depend on the amount of padding");

        // A payload that is already an exact multiple of PAD_BYTES gets no padding at all.
        int textBytes = PAD - Chunk.HEADER_BYTES - Chunk.recordBytes(new Registo(1, 0, "a", "c", ""));
        Chunk exact = new Chunk(List.of(new Registo(1, 0, "a", "c", "x".repeat(textBytes))));
        check(exact.payloadBytes() == PAD && exact.serializar(PAD).length == PAD,
                "a payload of exactly PAD_BYTES is not padded further");

        expectThrows(IllegalArgumentException.class, "an empty chunk is rejected", () -> new Chunk(List.of()));
        expectThrows(UnsupportedOperationException.class, "a chunk is immutable",
                () -> chunk.registos.add(registos.get(0)));
        expectThrows(IllegalArgumentException.class, "ler rejects bytes without the magic number",
                () -> Chunk.ler(new byte[PAD]));
        expectThrows(IllegalArgumentException.class, "ler rejects a truncated chunk",
                () -> Chunk.ler(Arrays.copyOf(padded, 20)));

        // registos.log lines
        for (Registo registo : registos) {
            check(Registo.fromLogLine(registo.toLogLine()).equals(registo),
                    "registos.log line round-trip for LSN " + registo.lsn + ": " + registo.toLogLine());
        }
        check(registos.get(2).toLogLine().split(";", -1).length == 5,
                "the text is Base64, so ';' and newlines in it never break the line format");
        check(Registo.sanitizeField("a;b\nc").equals("a_b c"), "author/channel are sanitized for the line format");
        expectThrows(IllegalArgumentException.class, "a malformed line is rejected",
                () -> Registo.fromLogLine("1;2;3"));
    }

    private static boolean allZero(byte[] bytes, int from, int to) {
        for (int i = from; i < to; i++) {
            if (bytes[i] != 0) return false;
        }
        return true;
    }
}
