package tpf;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

/**
 * One archived record, in clear text. It only ever exists on the leader: replicas
 * receive records exclusively inside encrypted chunks.
 */
public final class Registo {

    /** Log sequence number: 1, 2, 3, ... with no gaps or repetitions. */
    public final long lsn;
    /** When the leader accepted the record, in milliseconds since the Unix epoch. */
    public final long instante;
    /** Discord user name of the author. */
    public final String autor;
    /** Discord channel where the record was written. */
    public final String canal;
    /** The archived text itself. */
    public final String texto;

    public Registo(long lsn, long instante, String autor, String canal, String texto) {
        this.lsn = lsn;
        this.instante = instante;
        this.autor = Objects.requireNonNull(autor, "autor");
        this.canal = Objects.requireNonNull(canal, "canal");
        this.texto = Objects.requireNonNull(texto, "texto");
    }

    /**
     * Formats the record as one line of registos.log:
     * lsn;instante;autor;canal;texto em Base64 (without the trailing newline).
     *
     * Only the text is Base64-encoded, because it is the only free-form field: the
     * author and channel are sanitized by sanitizeField(String) before a
     * record is created, so they never contain a ";" or line breaks.
     */
    public String toLogLine() {
        String text64 = Base64.getEncoder().encodeToString(texto.getBytes(StandardCharsets.UTF_8));
        return lsn + ";" + instante + ";" + autor + ";" + canal + ";" + text64;
    }

    /** Parses a line written by toLogLine(); throws IllegalArgumentException if malformed. */
    public static Registo fromLogLine(String line) {
        String[] fields = line.split(";", -1);   // -1 keeps an empty last field (empty text)
        if (fields.length != 5) {
            throw new IllegalArgumentException("expected 5 fields, found " + fields.length);
        }
        try {
            long lsn = Long.parseLong(fields[0]);
            long instante = Long.parseLong(fields[1]);
            String texto = new String(Base64.getDecoder().decode(fields[4]), StandardCharsets.UTF_8);
            return new Registo(lsn, instante, fields[2], fields[3], texto);
        } catch (IllegalArgumentException e) {   // NumberFormatException is a subclass
            throw new IllegalArgumentException("malformed field: " + e.getMessage(), e);
        }
    }

    /**
     * Makes a value safe for the ";"-separated log format by replacing the
     * separator and line breaks. Discord user and channel names normally never contain
     * them, but display names can, and a single bad line would corrupt the log.
     */
    public static String sanitizeField(String value) {
        return value.replace(';', '_').replace('\n', ' ').replace('\r', ' ');
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Registo other)) return false;
        return lsn == other.lsn && instante == other.instante && autor.equals(other.autor)
                && canal.equals(other.canal) && texto.equals(other.texto);
    }

    @Override
    public int hashCode() {
        return Objects.hash(lsn, instante, autor, canal, texto);
    }

    @Override
    public String toString() {
        return "Registo{lsn=" + lsn + ", instante=" + instante + ", autor=" + autor
                + ", canal=" + canal + ", texto=" + texto.length() + " chars}";
    }
}
