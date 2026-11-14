package raft04;

/** Contrato textual de topicos e mensagens entre os processos. */
public final class Wire {
    // ---------------- Parametros globais ----------------
    // Estes valores definem tempos, portas, grupo e regras da simulacao.
    public static final double LIMIAR_ALARME = 28.0;
    public static final int LIMITE_RETIDOS = 10;   // acima deste numero de retidos, a replica recupera
    public static final int T_TCP_MS = 5000;       // tempo maximo de ligacao e de espera por cada linha
    public static final int PORTO_BASE = 7000;     // porto de recuperacao do lider da particao k: 7000 + k
    public static final long PERIODO_HB_MS = Long.getLong("raft04.periodo", 2000L);
    public static final int FATOR_TIMEOUT = Integer.getInteger("raft04.fator", 3);
    public static final long VERIFICACAO_MS = 200L;
    public static final long ELEICAO_MIN_MS =
            Long.getLong("raft04.eleicaoMin", 3L * PERIODO_HB_MS);
    public static final long ELEICAO_MAX_MS =
            Long.getLong("raft04.eleicaoMax", 6L * PERIODO_HB_MS);
    public static final int GRUPO = 3;

    private Wire() { }

    // ---------------- Topicos e enderecos ----------------
    /** Topico de entradas destinado a particao k. */
    public static String topicIn(int k)   { return "raft04-in-p" + k; }
    /** Topico de LOTEs e heartbeats emitidos pelo lider da particao k. */
    public static String topicData(int k) { return "raft04-p" + k; }
    public static String topicElection(int k) { return "raft04-eleicao-p" + k; }
    public static int nodePort(int k, int i) { return 7100 + k * GRUPO + i; }
    public static boolean isolated(int k, int i) {
        // Um ficheiro marcador permite simular uma particao de rede.
        return java.nio.file.Files.exists(java.nio.file.Paths.get("isolar-p" + k + "-n" + i));
    }

    public static String in(String sensor, double temp, long lamport, VectorClock vector) {
        // Formato comum das leituras enviadas pelo produtor.
        return "IN;" + sensor + ";" + temp + ";" + lamport + ";" + vector;
    }

    /** Obtem e valida o tipo textual da mensagem. */
    public static String type(String msg) {
        String m = msg.trim();
        int i = m.indexOf(';');
        if (i <= 0) {
            throw new IllegalArgumentException("Mensagem sem tipo: " + msg);
        }
        return m.substring(0, i);
    }

    /** Obtem o corpo depois do primeiro ponto e virgula. */
    public static String body(String msg) {
        String m = msg.trim();
        return m.substring(m.indexOf(';') + 1);
    }

}
