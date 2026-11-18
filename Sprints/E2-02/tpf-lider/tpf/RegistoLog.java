package tpf;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.function.Consumer;

/**
 * The leader's durable log, {@code registos.log}: one line per record, in LSN order,
 * with the format {@code lsn;instante;autor;canal;texto em Base64}.
 *
 * <p>A record is only acknowledged to the user after its line has been forced to
 * disk with {@code FileChannel.force(true)}, so an acknowledged record is never
 * lost by a crash of the leader process.
 */
final class RegistoLog implements Closeable {

    private final Path file;
    private final FileChannel channel;
    private final long lastLsn;
    /** Set when a failed append could not be undone: the file is then in an unknown state. */
    private boolean broken;

    private RegistoLog(Path file, FileChannel channel, long lastLsn) {
        this.file = file;
        this.channel = channel;
        this.lastLsn = lastLsn;
    }

    /**
     * Opens (or creates) the log, replaying every existing record through
     * {@code visitor} in LSN order.
     *
     * <ul>
     *   <li>LSNs must start at 1 and increase by exactly 1; anything else means the log
     *       was corrupted or edited by hand, and the leader refuses to start.</li>
     *   <li>Bytes after the last newline are the remains of an append interrupted by a
     *       crash. That record was never acknowledged (the reply only happens after
     *       {@code fsync}), so the incomplete line is cut off.</li>
     * </ul>
     */
    static RegistoLog open(Path file, Consumer<Registo> visitor) throws IOException {
        boolean existed = Files.exists(file);
        long lastLsn = 0;
        long validBytes = 0;          // offset just after the last complete line
        long tornBytes = 0;           // bytes of an incomplete last line, if any

        if (existed) {
            try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
                ByteArrayOutputStream line = new ByteArrayOutputStream();
                long offset = 0;
                int lineNumber = 0;
                int b;
                while ((b = in.read()) != -1) {
                    offset++;
                    if (b != '\n') {
                        line.write(b);
                        continue;
                    }
                    lineNumber++;
                    Registo registo = parseLine(line, lineNumber);
                    if (registo.lsn != lastLsn + 1) {
                        throw new IllegalStateException("startup refused: " + file + " line " + lineNumber
                                + " has LSN " + registo.lsn + " but LSN " + (lastLsn + 1) + " was expected");
                    }
                    visitor.accept(registo);
                    lastLsn = registo.lsn;
                    validBytes = offset;
                    line.reset();
                }
                tornBytes = line.size();
            }
        }

        FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            if (tornBytes > 0) {
                System.err.println("[registos.log] discarding " + tornBytes
                        + " bytes of an incomplete last line (append interrupted by a crash)");
                channel.truncate(validBytes);
                channel.force(true);
            }
            channel.position(channel.size());
        } catch (IOException e) {
            channel.close();
            throw e;
        }
        if (!existed) {
            DurableFiles.forceDirectory(file.toAbsolutePath().getParent());
        }
        return new RegistoLog(file, channel, lastLsn);
    }

    private static Registo parseLine(ByteArrayOutputStream bytes, int lineNumber) {
        String text = bytes.toString(StandardCharsets.UTF_8);
        if (text.endsWith("\r")) {
            text = text.substring(0, text.length() - 1);   // tolerate a file edited on Windows
        }
        try {
            return Registo.fromLogLine(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("startup refused: registos.log line " + lineNumber
                    + " is corrupted (" + e.getMessage() + ")", e);
        }
    }

    /** LSN of the last record found in the file when it was opened (0 for an empty log). */
    long lastLsn() {
        return lastLsn;
    }

    /**
     * Appends one record and forces it to disk. When this method returns normally
     * the record is durable; when it throws, the file is left exactly as before, so
     * the caller can safely reuse the same LSN.
     */
    synchronized void append(Registo registo) throws IOException {
        if (broken) {
            throw new IOException(file + " is in an unknown state after a failed write; restart the leader");
        }
        byte[] line = (registo.toLogLine() + "\n").getBytes(StandardCharsets.UTF_8);
        long sizeBefore = channel.size();
        try {
            DurableFiles.writeFully(channel, line);
            channel.force(true);    // fsync: only now is the record durable
        } catch (IOException e) {
            // Undo a partial append, otherwise the next record would reuse this LSN and
            // the log would end up with two lines for the same LSN.
            try {
                channel.truncate(sizeBefore);
                channel.position(sizeBefore);
                channel.force(true);
            } catch (IOException undoFailure) {
                e.addSuppressed(undoFailure);
                broken = true;
            }
            throw e;
        }
    }

    @Override
    public synchronized void close() throws IOException {
        channel.close();
    }
}
