package raft04;

/** Mapeia sensores para particoes de forma deterministica. */
public final class Partitioner {
    // Numero de particoes usado para decidir onde cada sensor sera tratado.
    private final int n;

    /** Valida o numero de particoes usado para dimensionar os vetores. */
    public Partitioner(int n) {
        if (n < 1) {
            throw new IllegalArgumentException("numero de particoes invalido: " + n);
        }
        this.n = n;
    }

    /** Usa o hash do sensor, normalizado para nunca produzir indice negativo. */
    public int of(String sensorId) {
        // O mesmo sensor produz sempre a mesma particao em todos os processos.
        return Math.floorMod(sensorId.hashCode(), n);
    }

    public int count() {
        // Permite aos consumidores conhecer a dimensao configurada.
        return n;
    }
}


