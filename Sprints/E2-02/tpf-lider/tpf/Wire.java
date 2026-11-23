package tpf;

/**
 * Parameters shared by the leader (and, in later sprints, by the replicas).
 *
 * Every tunable value can be overridden on the command line with a system
 * property using the "tpf." prefix, for example
 * -Dtpf.chunkMax=16384 -Dtpf.selagem=2000. Only the parameters needed
 * by the leader's local write path (sprint E2-02) exist so far.
 */
public final class Wire {

    /** A chunk is sealed as soon as its serialized records reach this many bytes (tpf.chunkMax). */
    public static final int CHUNK_MAX_BYTES = positiveInt("tpf.chunkMax", 65536);

    /** Serialized chunks are padded up to a multiple of this many bytes (tpf.pad). */
    public static final int PAD_BYTES = positiveInt("tpf.pad", 4096);

    /** A chunk is sealed this many milliseconds after its first record entered it (tpf.selagem). */
    public static final long SELAGEM_MS = positiveLong("tpf.selagem", 5000);

    /**
     * Version of the master key used to derive the chunk keys. Key rotation is
     * out of scope for this project, so it is always 1 (the field still travels
     * with every chunk so that a future rotation would be possible).
     */
    public static final int KEY_VERSION = 1;

    private Wire() {
    }

    /** Reads an int system property, failing loudly instead of silently using the default. */
    private static int positiveInt(String name, int defaultValue) {
        long value = positiveLong(name, defaultValue);
        if (value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("-D" + name + " is too large: " + value);
        }
        return (int) value;
    }

    /** Reads a long system property that must be strictly positive. */
    private static long positiveLong(String name, long defaultValue) {
        String raw = System.getProperty(name);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            long value = Long.parseLong(raw.trim());
            if (value <= 0) {
                throw new IllegalArgumentException("-D" + name + " must be positive, got " + raw);
            }
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("-D" + name + " is not a number: " + raw, e);
        }
    }
}
