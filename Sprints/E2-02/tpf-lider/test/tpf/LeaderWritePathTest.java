package tpf;

import static tpf.TestSupport.QUIET;
import static tpf.TestSupport.check;
import static tpf.TestSupport.expectThrows;
import static tpf.TestSupport.section;
import static tpf.TestSupport.waitUntil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.stream.Stream;

/**
 * End-to-end test of the leader's local write path, without Discord: it drives
 * {@link LeaderBot#registar} and {@link LeaderBot#rajada} directly and checks the
 * acceptance criteria of sprint E2-02 on the files of a temporary leader folder.
 */
final class LeaderWritePathTest {

    private static final String SECRET = "segredo-que-nao-pode-aparecer-em-claro";

    private LeaderWritePathTest() {
    }

    static void run() throws Exception {
        section("LeaderWritePathTest (SELAGEM_MS=" + Wire.SELAGEM_MS + ", CHUNK_MAX_BYTES=" + Wire.CHUNK_MAX_BYTES
                + ", PAD_BYTES=" + Wire.PAD_BYTES + ")");
        Path dir = TestSupport.tempDir("tpf-lider-test");
        try {
            writePath(dir);
        } finally {
            TestSupport.deleteRecursively(dir);
        }
        refusesBadStartupState();
    }

    private static void writePath(Path dir) throws Exception {
        Path log = dir.resolve(LeaderBot.LOG_FILE);
        Path keyFile = dir.resolve(LeaderBot.MASTER_KEY_FILE);
        long sealMs = Wire.SELAGEM_MS;

        // ---- first start: master.key is created
        LeaderBot bot = new LeaderBot(dir);
        check(Files.size(keyFile) == MasterKey.SIZE_BYTES, "the first start generates a 32-byte master.key");
        check(isOwnerReadOnly(keyFile), "master.key is readable by its owner only");

        // ---- one record: LSN 1, durable before registar returns, sealed ~SELAGEM_MS later
        long start = System.currentTimeMillis();
        long lsn = bot.registar("alice", "arquivo", SECRET);
        check(lsn == 1, "the first record gets LSN 1");
        List<String> lines = Files.readAllLines(log);
        check(lines.size() == 1 && lines.get(0).startsWith("1;") && Registo.fromLogLine(lines.get(0)).texto.equals(SECRET),
                "its line is already in registos.log when registar returns");
        check(countSealed(dir) == 0, "nothing is sealed before SELAGEM_MS");
        waitUntil(() -> countSealed(dir) == 1, sealMs + 5000, "the single record to be sealed");
        long elapsed = System.currentTimeMillis() - start;
        check(elapsed >= sealMs - 50, "a single record is sealed by time, after ~SELAGEM_MS (" + elapsed + " ms)");

        // ---- burst of 500: several chunks sealed by size, then the rest by time
        long[] lsns = bot.rajada("admin", "arquivo", 500, 0);
        check(lsns[0] == 2 && lsns[1] == 501, "/rajada 500 0 goes through registar: LSN 2-501");
        check(countSealed(dir) >= 1 + 3, "/rajada 500 0 seals several chunks by size right away (" + (countSealed(dir) - 1) + ")");
        waitUntil(() -> allSealed(dir), sealMs + 5000, "the burst to be fully sealed");

        SealedCheck.Report report = SealedCheck.run(dir, keyFile, QUIET);
        check(report.failures() == 0 && report.sealedRecords() == 501 && report.duplicatedLsns().isEmpty(),
                "every chunk opens with the master key and holds exactly the records of registos.log ("
                        + report.chunks() + " chunks, sizes multiple of PAD_BYTES, names = SHA-256 of content)");
        check(!anySealedFileContains(dir, SECRET), "no file in selados/ contains a registered text in clear");
        check(countEvents(dir, EventLog.REGISTO) == 501 && countEvents(dir, EventLog.SELADO) == report.chunks(),
                "eventos-lider.txt has one REGISTO per record and one SELADO per chunk");

        // ---- restart: the numbering continues from registos.log
        bot.close();
        bot = new LeaderBot(dir);
        check(bot.registar("alice", "arquivo", "depois do reinicio") == 502, "after a restart the next LSN is 502 (no repeats, no gaps)");

        // ---- stop before SELAGEM_MS: the unsealed records are re-queued at the next start
        bot.registar("alice", "arquivo", "registo 503");
        bot.registar("alice", "arquivo", "registo 504");
        bot.close();
        report = SealedCheck.run(dir, keyFile, QUIET);
        check(report.unsealedLsns().equals(List.of(502L, 503L, 504L)), "stopping before SELAGEM_MS leaves LSN 502-504 unsealed");
        bot = new LeaderBot(dir);
        waitUntil(() -> allSealed(dir), sealMs + 5000, "the re-queued records to be sealed");
        report = SealedCheck.run(dir, keyFile, QUIET);
        check(report.failures() == 0 && report.unsealedLsns().isEmpty() && report.duplicatedLsns().isEmpty(),
                "after restarting, the re-queued records are sealed exactly once");
        check(bot.registar("alice", "arquivo", "x") == 505, "and the numbering still continues (LSN 505)");
        bot.close();

        // ---- an append interrupted by a crash (line without '\n') is discarded at startup
        Files.write(log, "506;123;alice;arquivo;bWVpbyBlc2NyaXRv".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        bot = new LeaderBot(dir);
        check(bot.registar("alice", "arquivo", "y") == 506, "an incomplete last line is discarded and its LSN reused");
        bot.close();
        check(Files.readAllLines(log).size() == 506, "registos.log ends with exactly 506 complete lines");

        // ---- tampering is detected when opening
        String victim = new SealedStore(dir.resolve(LeaderBot.SEALED_DIR)).ids().get(0);
        Path bin = dir.resolve(LeaderBot.SEALED_DIR).resolve(victim + SealedStore.BIN);
        byte[] original = Files.readAllBytes(bin);
        byte[] tampered = original.clone();
        tampered[tampered.length / 2] ^= 0x01;
        Files.write(bin, tampered);
        check(SealedCheck.run(dir, keyFile, QUIET).failures() == 1, "a changed byte in one .bin makes that chunk fail to open");
        Files.write(bin, original);

        Path otherKey = dir.resolve("outra.key");
        Files.write(otherKey, new byte[MasterKey.SIZE_BYTES]);
        report = SealedCheck.run(dir, otherKey, QUIET);
        check(report.failures() == report.chunks(), "with another master.key no chunk opens");
    }

    /** The leader refuses to start with a master.key of the wrong size or a log with an LSN gap. */
    private static void refusesBadStartupState() throws IOException {
        Path dir = TestSupport.tempDir("tpf-lider-bad");
        try {
            Files.write(dir.resolve(LeaderBot.MASTER_KEY_FILE), new byte[31]);
            expectThrows(IllegalStateException.class, "startup is refused when master.key does not have 32 bytes",
                    () -> new LeaderBot(dir));

            Files.delete(dir.resolve(LeaderBot.MASTER_KEY_FILE));
            String gap = new Registo(1, 0, "a", "arquivo", "um").toLogLine() + "\n"
                    + new Registo(3, 0, "a", "arquivo", "tres").toLogLine() + "\n";
            Files.writeString(dir.resolve(LeaderBot.LOG_FILE), gap);
            expectThrows(IllegalStateException.class, "startup is refused when registos.log has an LSN gap",
                    () -> new LeaderBot(dir));
        } finally {
            TestSupport.deleteRecursively(dir);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static long countSealed(Path dir) {
        try (Stream<Path> files = Files.list(dir.resolve(LeaderBot.SEALED_DIR))) {
            return files.filter(p -> p.toString().endsWith(SealedStore.BIN)).count();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static boolean allSealed(Path dir) {
        try {
            SealedCheck.Report report = SealedCheck.run(dir, dir.resolve(LeaderBot.MASTER_KEY_FILE), QUIET);
            return report.unsealedLsns().isEmpty();
        } catch (IOException | RuntimeException e) {
            return false;   // a chunk may be in the middle of being written
        }
    }

    private static boolean anySealedFileContains(Path dir, String text) throws IOException {
        byte[] needle = text.getBytes(StandardCharsets.UTF_8);
        try (Stream<Path> files = Files.list(dir.resolve(LeaderBot.SEALED_DIR))) {
            for (Path file : (Iterable<Path>) files::iterator) {
                if (ChunkCryptoTest.contains(Files.readAllBytes(file), needle)) return true;
            }
        }
        return false;
    }

    private static long countEvents(Path dir, String event) throws IOException {
        try (Stream<String> lines = Files.lines(dir.resolve(LeaderBot.EVENTS_FILE))) {
            return lines.filter(line -> line.split(";", 3)[1].equals(event)).count();
        }
    }

    private static boolean isOwnerReadOnly(Path file) throws IOException {
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            return PosixFilePermissions.toString(Files.getPosixFilePermissions(file)).equals("r--------");
        }
        List<AclEntry> acl = Files.getFileAttributeView(file, AclFileAttributeView.class).getAcl();
        return acl.size() == 1
                && acl.get(0).principal().equals(Files.getOwner(file))
                && acl.get(0).permissions().contains(AclEntryPermission.READ_DATA)
                && !acl.get(0).permissions().contains(AclEntryPermission.WRITE_DATA);
    }
}
