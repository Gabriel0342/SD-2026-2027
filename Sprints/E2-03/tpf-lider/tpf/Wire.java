package tpf;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Values and wire format shared by the leader and replica processes. */
public final class Wire {
    public static final int R = positiveInt("tpf.r", 3);
    public static final double ALFA = nonNegativeDouble("tpf.alfa", 1.0);
    public static final long PERIODO_HB_MS = positiveLong("tpf.periodo", 2_000);
    public static final long SUSPEITA_MS = positiveLong("tpf.suspeita", 6_000);
    public static final long MORTA_MS = positiveLong("tpf.morta", 10_000);
    public static final long TOKEN_VALIDADE_MS = positiveLong("tpf.tokenValidade", 600_000);
    public static final long ACK_TIMEOUT_MS = positiveLong("tpf.ackTimeout", 4_000);
    public static final int CHUNK_MAX_BYTES = positiveInt("tpf.chunkMax", 65_536);
    public static final int PAD_BYTES = positiveInt("tpf.pad", 4_096);
    public static final long SELAGEM_MS = positiveLong("tpf.selagem", 5_000);
    public static final long MARGEM_BYTES = positiveLong("tpf.margem", 65_536);
    public static final int KEY_VERSION = 1;
    public static final int W_MIN = positiveInt("tpf.wMin", 2);
    public static final int MAX_RONDAS = positiveInt("tpf.maxRondas", 3);
    public static final long BACKOFF_MIN_MS = positiveLong("tpf.backoffMin", 1_000);
    public static final long BACKOFF_MAX_MS = positiveLong("tpf.backoffMax", 30_000);
    public static final String TOPICO_LIDER = "tpf-lider";
    public static final String TOPICO_CTL = "tpf-ctl";
    public static final int DEFAULT_PORT = positiveInt("tpf.porto", 8765);

    private Wire() { }

    public static String topicReplica(String id) { return "tpf-r-" + id; }

    public static String b64(byte[] bytes) { return Base64.getEncoder().encodeToString(bytes); }
    public static byte[] unb64(String text) { return Base64.getDecoder().decode(text); }

    /** Splits protocol fields while preserving empty trailing fields. */
    public static String[] fields(String message) { return message.split(";", -1); }
    public static String text(byte[] bytes) { return new String(bytes, StandardCharsets.UTF_8); }

    private static int positiveInt(String name, int fallback) {
        long value = positiveLong(name, fallback);
        if (value > Integer.MAX_VALUE) throw new IllegalArgumentException("-D" + name + " is too large");
        return (int) value;
    }
    private static long positiveLong(String name, long fallback) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) return fallback;
        long parsed = Long.parseLong(value.trim());
        if (parsed <= 0) throw new IllegalArgumentException("-D" + name + " must be positive");
        return parsed;
    }
    private static double nonNegativeDouble(String name, double fallback) {
        String raw = System.getProperty(name);
        double value = raw == null || raw.isBlank() ? fallback : Double.parseDouble(raw.trim());
        if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException("-D" + name + " must be non-negative");
        return value;
    }
}
