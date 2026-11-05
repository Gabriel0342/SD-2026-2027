package raft04;

/** Registo persistido e transportado com seq, dados e carimbos causais. */
public class SensorRecord {
    private final long seq;
    public final long term;
    private final String sensorId;
    private final double temperature;
    private final String timestamp;
    public final long lamport;
    public final VectorClock vector;

    /** ConstrÃ³i um registo que jÃ¡ recebeu todos os seus carimbos. */
    public SensorRecord(long seq, long term, String sensorId, double temperature, String timestamp,
                        long lamport, VectorClock vector) {
        this.seq = seq;
        this.term = term;
        this.sensorId = sensorId;
        this.temperature = temperature;
        this.timestamp = timestamp;
        this.lamport = lamport;
        this.vector = vector;
    }

    public SensorRecord(long seq, String sensorId, double temperature, String timestamp,
                        long lamport, VectorClock vector) {
        this(seq, 0, sensorId, temperature, timestamp, lamport, vector);
    }

    /** Cria um registo com milissegundos para permitir medir a latÃªncia do lote. */
    public static SensorRecord now(long seq, String sensorId, double temperature,
                                   long lamport, VectorClock vector) {
        return new SensorRecord(seq, 0, sensorId, temperature,
                Long.toString(System.currentTimeMillis()), lamport, vector);
    }

    public static SensorRecord now(long seq, long term, String sensorId, double temperature,
                                   long lamport, VectorClock vector) {
        return new SensorRecord(seq, term, sensorId, temperature,
                Long.toString(System.currentTimeMillis()), lamport, vector);
    }

    /** Formato Ãºnico usado no ficheiro, PubSub e recuperaÃ§Ã£o TCP. */
    public String toLine() {
        return seq + ";" + term + ";" + sensorId + ";" + temperature + ";" + timestamp
                + ";" + lamport + ";" + vector;
    }

    /** Faz o parsing e valida a dimensÃ£o do vetor recebido. */
    public static SensorRecord fromLine(String line, int n) {
        String[] f = line.trim().split(";");
        if (f.length != 7) {
            throw new IllegalArgumentException("Registo mal formado: " + line);
        }
        return new SensorRecord(Long.parseLong(f[0]), Long.parseLong(f[1]), f[2],
                Double.parseDouble(f[3]), f[4], Long.parseLong(f[5]),
                VectorClock.parse(f[6], n));
    }

    public static SensorRecord fromLine(String line) { return fromLine(line, 3); }

    public long getSeq() { return seq; }
    public String getSensorId() { return sensorId; }
    public double getTemperature() { return temperature; }
    public String getTimestamp() { return timestamp; }
}
