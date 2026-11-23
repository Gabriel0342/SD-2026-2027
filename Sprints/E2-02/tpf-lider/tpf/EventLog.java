package tpf;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * The leader's event log, eventos-lider.txt, with one line per event:
 * instante;evento;detalhe.
 *
 * Events are diagnostic data for the offline analysis: a failure to write one is
 * reported on the console but never makes the operation that caused it fail.
 * Each event is also echoed to the console so a running leader can be followed live
 * (disable it with -Dtpf.ecoEventos=false).
 */
final class EventLog implements Closeable {

    /** A record was accepted and forced to registos.log. */
    static final String REGISTO = "REGISTO";
    /** A chunk was sealed, encrypted and stored in selados/. */
    static final String SELADO = "SELADO";
    /** Something went wrong (I/O failure, unreadable chunk, ...). */
    static final String ERRO = "ERRO";

    private final BufferedWriter writer;
    private final boolean echo = !"false".equalsIgnoreCase(System.getProperty("tpf.ecoEventos"));

    EventLog(Path file) throws IOException {
        this.writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** Appends <now>;<event>;<detail> and flushes it immediately. */
    synchronized void log(String event, String detail) {
        // The detail is free text, but it must stay on a single line.
        String line = System.currentTimeMillis() + ";" + event + ";"
                + detail.replace('\n', ' ').replace('\r', ' ');
        if (echo) {
            System.out.println("[evento] " + line);
        }
        try {
            writer.write(line + "\n");   // always '\n', not the platform separator, like registos.log
            writer.flush();
        } catch (IOException e) {
            System.err.println("[eventos-lider.txt] could not write event: " + e);
        }
    }

    @Override
    public synchronized void close() throws IOException {
        writer.close();
    }
}
