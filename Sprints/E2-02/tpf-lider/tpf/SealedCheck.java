package tpf;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Offline check of the leader's sealed chunks. It can run with the leader stopped or
 * running, and never modifies any file.
 *
 * <pre>
 * SealedCheck [pastaLider] [masterKey]
 *   pastaLider  leader data folder (default: -Dtpf.pasta, or the current directory)
 *   masterKey   key used to open the chunks (default: pastaLider/master.key);
 *               pass a different key to see every chunk fail to open
 * </pre>
 *
 * For every {@code selados/<chunkId>.bin} it verifies that:
 * <ol>
 *   <li>the file name is the SHA-256 of the file content;</li>
 *   <li>the chunk opens with the master key (hash, key version and GCM tag);</li>
 *   <li>the padded plaintext is a multiple of {@code PAD_BYTES};</li>
 *   <li>its records have consecutive LSNs and are exactly the lines of {@code registos.log}
 *       (LSN, instant, author, channel and text);</li>
 *   <li>its LSN range matches the {@code SELADO} event in {@code eventos-lider.txt};</li>
 *   <li>none of its record texts (longer than 8 characters) appears in clear in the
 *       {@code .bin} or {@code .hdr} files.</li>
 * </ol>
 * Finally it reports which logged LSNs are not (yet) in any sealed chunk.
 */
public final class SealedCheck {

    /** Texts up to this length are too short for a meaningful "plaintext leaked" search. */
    private static final int MIN_LEAK_TEXT_BYTES = 9;

    /** Summary of a run, used by the tests. */
    record Report(int chunks, int failures, int sealedRecords, List<Long> unsealedLsns, List<Long> duplicatedLsns) {
    }

    /** Outcome of checking one chunk; {@code firstLsn} orders the output (unreadable chunks go last). */
    private record ChunkResult(long firstLsn, String summary, List<String> problems) {
    }

    private SealedCheck() {
    }

    public static void main(String[] args) throws IOException {
        Path dataDir = Path.of(args.length > 0 ? args[0] : System.getProperty("tpf.pasta", "."));
        Path keyFile = args.length > 1 ? Path.of(args[1]) : dataDir.resolve(LeaderBot.MASTER_KEY_FILE);
        run(dataDir, keyFile, System.out);
    }

    static Report run(Path dataDir, Path keyFile, PrintStream out) throws IOException {
        byte[] masterKey = Files.readAllBytes(keyFile);
        if (masterKey.length != MasterKey.SIZE_BYTES) {
            out.println("FAIL: " + keyFile + " has " + masterKey.length + " bytes instead of " + MasterKey.SIZE_BYTES);
            return new Report(0, 1, 0, List.of(), List.of());
        }
        ChunkCrypto crypto = new ChunkCrypto(masterKey, Wire.KEY_VERSION);

        NavigableMap<Long, Registo> log = readLog(dataDir.resolve(LeaderBot.LOG_FILE));
        Map<String, String> sealedEvents = readSealedEvents(dataDir.resolve(LeaderBot.EVENTS_FILE));
        SealedStore store = new SealedStore(dataDir.resolve(LeaderBot.SEALED_DIR));
        List<String> ids = store.ids();

        out.printf("SealedCheck: folder %s, key %s%n", dataDir.toAbsolutePath().normalize(), keyFile);
        out.printf("  %d chunk(s) in %s, %d record(s) in %s, PAD_BYTES=%d%n%n",
                ids.size(), LeaderBot.SEALED_DIR, log.size(), LeaderBot.LOG_FILE, Wire.PAD_BYTES);

        // Chunk ids are hashes, so the file order is random: check them all, then print in LSN order.
        Map<Long, Integer> timesSealed = new HashMap<>();
        List<ChunkResult> results = new ArrayList<>();
        for (String id : ids) {
            results.add(checkChunk(id, store, crypto, log, sealedEvents, timesSealed));
        }
        results.sort(Comparator.comparingLong(ChunkResult::firstLsn));

        int failures = 0;
        for (ChunkResult result : results) {
            if (result.problems().isEmpty()) {
                out.println("  OK    " + result.summary());
            } else {
                failures++;
                out.println("  FAIL  " + result.summary());
                for (String problem : result.problems()) {
                    out.println("          - " + problem);
                }
            }
        }

        // Coverage: every logged LSN should be in exactly one chunk (except the open chunk's).
        List<Long> unsealed = new ArrayList<>();
        List<Long> duplicated = new ArrayList<>();
        for (long lsn : log.keySet()) {
            int count = timesSealed.getOrDefault(lsn, 0);
            if (count == 0) unsealed.add(lsn);
            if (count > 1) duplicated.add(lsn);
        }
        int sealedRecords = log.size() - unsealed.size();
        out.printf("%nCoverage: %d of %d logged record(s) are sealed", sealedRecords, log.size());
        out.println(unsealed.isEmpty() ? "" : "; not sealed (yet): LSN " + compactRanges(unsealed));
        if (!duplicated.isEmpty()) {
            failures++;
            out.println("FAIL: LSN in more than one chunk: " + compactRanges(duplicated));
        }
        out.println(failures == 0
                ? "RESULT: OK (" + ids.size() + " chunk(s) verified)"
                : "RESULT: " + failures + " FAILURE(S)");
        return new Report(ids.size(), failures, sealedRecords, unsealed, duplicated);
    }

    /** Verifies one chunk, collecting every problem found. */
    private static ChunkResult checkChunk(String id, SealedStore store, ChunkCrypto crypto,
                                          NavigableMap<Long, Registo> log, Map<String, String> sealedEvents,
                                          Map<Long, Integer> timesSealed) {
        List<String> problems = new ArrayList<>();
        String shortId = id.substring(0, 12) + "...";
        ChunkCrypto.Sealed sealed;
        byte[] header;
        try {
            sealed = store.read(id);
            header = Files.readAllBytes(store.hdrPath(id));
            if (!ChunkCrypto.sha256Hex(sealed.ciphertext).equals(id)) {
                problems.add("file name is not the SHA-256 of the .bin content");
            }
        } catch (IOException | GeneralSecurityException e) {
            problems.add("cannot read: " + e.getMessage());
            return new ChunkResult(Long.MAX_VALUE, shortId, problems);
        }

        Chunk chunk;
        int paddedBytes;
        try {
            byte[] plaintext = crypto.open(sealed);
            paddedBytes = plaintext.length;
            chunk = Chunk.ler(plaintext);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            problems.add("open failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return new ChunkResult(Long.MAX_VALUE, shortId, problems);
        }

        String range = chunk.firstLsn() + "-" + chunk.lastLsn();
        String summary = String.format("%s  lsn %-11s %4d record(s)  padded %6d B  .bin %6d B",
                shortId, range, chunk.registos.size(), paddedBytes, sealed.ciphertext.length);

        if (paddedBytes % Wire.PAD_BYTES != 0) {
            problems.add("padded size " + paddedBytes + " is not a multiple of PAD_BYTES=" + Wire.PAD_BYTES);
        }

        String binText = new String(sealed.ciphertext, StandardCharsets.ISO_8859_1);
        String hdrText = new String(header, StandardCharsets.ISO_8859_1);
        long expectedLsn = chunk.firstLsn();
        for (Registo registo : chunk.registos) {
            timesSealed.merge(registo.lsn, 1, Integer::sum);
            if (registo.lsn != expectedLsn) {
                problems.add("LSN " + registo.lsn + " found where " + expectedLsn + " was expected");
            }
            expectedLsn = registo.lsn + 1;

            Registo logged = log.get(registo.lsn);
            if (logged == null) {
                problems.add("LSN " + registo.lsn + " is not in registos.log");
            } else if (!logged.equals(registo)) {
                problems.add("LSN " + registo.lsn + " differs from registos.log");
            }

            // Byte-for-byte search of the UTF-8 text, using ISO-8859-1 as a 1:1 byte<->char mapping.
            byte[] textBytes = registo.texto.getBytes(StandardCharsets.UTF_8);
            if (textBytes.length >= MIN_LEAK_TEXT_BYTES) {
                String needle = new String(textBytes, StandardCharsets.ISO_8859_1);
                if (binText.contains(needle) || hdrText.contains(needle)) {
                    problems.add("text of LSN " + registo.lsn + " appears in clear in the chunk files");
                }
            }
        }

        String eventRange = sealedEvents.get(id);
        if (eventRange == null) {
            problems.add("no SELADO event for this chunk in eventos-lider.txt");
        } else if (!eventRange.equals(range)) {
            problems.add("SELADO event says lsn=" + eventRange + " but the chunk holds " + range);
        }
        return new ChunkResult(chunk.firstLsn(), summary, problems);
    }

    /** Reads registos.log without modifying it; an incomplete last line (no newline) is ignored. */
    private static NavigableMap<Long, Registo> readLog(Path file) throws IOException {
        NavigableMap<Long, Registo> log = new TreeMap<>();
        if (Files.notExists(file)) {
            return log;
        }
        String content = Files.readString(file, StandardCharsets.UTF_8);
        int lastNewline = content.lastIndexOf('\n');
        if (lastNewline < 0) {
            return log;
        }
        for (String line : content.substring(0, lastNewline).split("\n")) {
            line = line.strip();
            if (!line.isEmpty()) {
                Registo registo = Registo.fromLogLine(line);
                log.put(registo.lsn, registo);
            }
        }
        return log;
    }

    /** chunkId -> "first-last" from the SELADO events ("chunk=&lt;id&gt; lsn=&lt;a&gt;-&lt;b&gt; ..."). */
    private static Map<String, String> readSealedEvents(Path file) throws IOException {
        Map<String, String> ranges = new HashMap<>();
        if (Files.notExists(file)) {
            return ranges;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String[] fields = line.split(";", 3);   // instante;evento;detalhe
            if (fields.length < 3 || !fields[1].equals(EventLog.SELADO)) {
                continue;
            }
            String chunkId = null;
            String lsnRange = null;
            for (String pair : fields[2].split(" ")) {
                if (pair.startsWith("chunk=")) chunkId = pair.substring("chunk=".length());
                if (pair.startsWith("lsn=")) lsnRange = pair.substring("lsn=".length());
            }
            if (chunkId != null && lsnRange != null) {
                ranges.put(chunkId, lsnRange);
            }
        }
        return ranges;
    }

    /** Formats sorted LSNs compactly, e.g. [1, 2, 3, 7] -> "1-3, 7". */
    static String compactRanges(List<Long> sortedLsns) {
        StringBuilder text = new StringBuilder();
        int i = 0;
        while (i < sortedLsns.size()) {
            int j = i;
            while (j + 1 < sortedLsns.size() && sortedLsns.get(j + 1) == sortedLsns.get(j) + 1) {
                j++;
            }
            if (text.length() > 0) text.append(", ");
            text.append(sortedLsns.get(i));
            if (j > i) text.append('-').append(sortedLsns.get(j));
            i = j + 1;
        }
        return text.toString();
    }
}
