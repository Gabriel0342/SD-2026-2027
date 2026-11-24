package tpf;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

/**
 * Minimal assertion helpers. The project may only use JDA, Java-WebSocket and the JDK,
 * so the tests do not depend on JUnit: a failed check throws an AssertionError,
 * which makes mvn test fail.
 */
final class TestSupport {

    /** Number of checks that passed, for the final summary. */
    static int passed;

    /** A PrintStream that discards everything (keeps tool output out of the test log). */
    static final PrintStream QUIET = new PrintStream(OutputStream.nullOutputStream());

    private TestSupport() {
    }

    @FunctionalInterface
    interface Action {
        void run() throws Exception;
    }

    static void section(String name) {
        System.out.println();
        System.out.println("== " + name);
    }

    static void check(boolean condition, String description) {
        if (!condition) {
            throw new AssertionError("FAILED: " + description);
        }
        passed++;
        System.out.println("  ok   " + description);
    }

    /** Runs action and checks that it throws an exception of the given type. */
    static <T extends Throwable> T expectThrows(Class<T> type, String description, Action action) {
        try {
            action.run();
        } catch (Throwable thrown) {
            if (type.isInstance(thrown)) {
                check(true, description + "  [" + thrown.getClass().getSimpleName() + "]");
                return type.cast(thrown);
            }
            throw new AssertionError("FAILED: " + description + ": expected " + type.getSimpleName()
                    + " but got " + thrown, thrown);
        }
        throw new AssertionError("FAILED: " + description + ": no exception was thrown");
    }

    /** Polls condition every 20 ms until it holds or timeoutMs expires. */
    static void waitUntil(BooleanSupplier condition, long timeoutMs, String description) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("FAILED: timed out after " + timeoutMs + " ms waiting for: " + description);
            }
            Thread.sleep(20);
        }
    }

    static Path tempDir(String prefix) throws IOException {
        return Files.createTempDirectory(prefix);
    }

    static void deleteRecursively(Path dir) {
        if (dir == null || Files.notExists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    path.toFile().setWritable(true);
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    System.err.println("  (could not delete " + path + ": " + e + ")");
                }
            });
        } catch (IOException e) {
            System.err.println("  (could not clean " + dir + ": " + e + ")");
        }
    }
}
