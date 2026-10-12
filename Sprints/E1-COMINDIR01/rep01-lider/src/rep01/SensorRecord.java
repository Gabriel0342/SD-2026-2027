package rep01;

import java.time.LocalDateTime;

public class SensorRecord {
    // O lider atribui o unico numero de sequencia global; as replicas usam-no para ordenar.
    private final long seq;
    private final String sensorId;
    private final double temperature;
    private final String timestamp;

    public SensorRecord(long seq, String sensorId, double temperature, String timestamp) {
        this.seq = seq; // num sequencial global do registo
        this.sensorId = sensorId;
        this.temperature = temperature;
        this.timestamp = timestamp;
    }

    // cria um registo com o timestamp atual
    public static SensorRecord now(long seq, String sensorId, double temperature) {
        return new SensorRecord(seq, sensorId, temperature, LocalDateTime.now().withNano(0).toString());
    }

    // O mesmo formato textual e usado no ficheiro e nas mensagens, sem serializacao Java.
    public String toLine() {
        return seq + ";" + sensorId + ";" + temperature + ";" + timestamp;
    }

    // Fronteira de validacao: uma mensagem invalida nao deve ser aplicada pela replica.
    public static SensorRecord fromLine(String line) {
        String[] f = line.trim().split(";");
        if (f.length != 4) {
            throw new IllegalArgumentException("Registo mal formado: " + line);
        }
        return new SensorRecord(Long.parseLong(f[0]), f[1], Double.parseDouble(f[2]), f[3]);
    }

    // Getters
    public long getSeq() {
        return seq;
    }

    public String getSensorId() {
        return sensorId;
    }

    public double getTemperature() {
        return temperature;
    }

    public String getTimestamp() {
        return timestamp;
    }
}