package rep03;

/** Contrato textual de tópicos e mensagens entre os processos. */
public final class Wire {
    public static final double LIMIAR_ALARME = 28.0;
    public static final int LIMITE_RETIDOS = 10;   // acima deste numero de retidos, a replica recupera
    public static final int T_TCP_MS = 5000;       // tempo maximo de ligacao e de espera por cada linha
    public static final int PORTO_BASE = 7000;     // porto de recuperacao do lider da particao k: 7000 + k

    private Wire() { }

    /** Tópico de entradas destinado à partição k. */
    public static String topicIn(int k)   { return "rep03-in-p" + k; }
    /** Tópico de REG emitidos pelo líder da partição k. */
    public static String topicData(int k) { return "rep03-p" + k; }

    public static String in(String sensor, double temp, long lamport, VectorClock vector) {
        return "IN;" + sensor + ";" + temp + ";" + lamport + ";" + vector;
    }

    /** Serializa um REG com metadados necessários para recuperação. */
    public static String reg(String host, int port, SensorRecord r) {
        return "REG;" + host + ";" + port + ";" + r.toLine();
    }

    /** Obtém e valida o tipo textual da mensagem. */
    public static String type(String msg) {
        String m = msg.trim();
        int i = m.indexOf(';');
        if (i <= 0) {
            throw new IllegalArgumentException("Mensagem sem tipo: " + msg);
        }
        return m.substring(0, i);
    }

    /** Obtém o corpo depois do primeiro ponto e vírgula. */
    public static String body(String msg) {
        String m = msg.trim();
        return m.substring(m.indexOf(';') + 1);
    }

    /** Divide REG em endereço, porto e linha do SensorRecord. */
    public static String[] regParts(String msg) {
        String[] f = msg.trim().split(";", 4);
        if (f.length != 4 || !f[0].equals("REG")) {
            throw new IllegalArgumentException("REG mal formado: " + msg);
        }
        return new String[] { f[1], f[2], f[3] };
    }
}
