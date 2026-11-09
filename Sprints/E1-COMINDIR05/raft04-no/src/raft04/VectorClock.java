package raft04;

import java.util.Arrays;

/** Relogio vetorial: cada componente representa o progresso causal de uma particao. */
public final class VectorClock {
    // Cada posicao representa o progresso causal de uma particao.
    public enum Order { BEFORE, AFTER, EQUAL, CONCURRENT }

    private final long[] v;

    /** Cria um vetor de dimensao N, inicialmente zero. */
    public VectorClock(int n) {
        this.v = new long[n];
    }

    private VectorClock(long[] v) {
        this.v = v;
    }

    /** Funde contextos causais pelo maximo componente a componente. */
    public void merge(VectorClock other) {
        // O merge conserva o maior conhecimento causal observado.
        for (int i = 0; i < v.length; i++) {
            v[i] = Math.max(v[i], other.v[i]);
        }
    }

    /** Regista um evento local na particao k. */
    public void tick(int k) {
        // Um evento local incrementa apenas a componente da sua particao.
        v[k]++;
    }

    public long get(int k) {
        return v[k];
    }

    public VectorClock copy() {
        // A copia evita que um novo registo altere o vetor ja persistido.
        return new VectorClock(v.clone());
    }

    /** Classifica a relacao causal entre os dois vetores. */
    public Order compare(VectorClock other) {
        // A comparacao identifica precedencia, igualdade ou concorrencia.
        boolean menor = false, maior = false;
        for (int i = 0; i < v.length; i++) {
            if (v[i] < other.v[i]) menor = true;
            if (v[i] > other.v[i]) maior = true;
        }
        if (menor && maior) return Order.CONCURRENT;
        if (menor) return Order.BEFORE;
        if (maior) return Order.AFTER;
        return Order.EQUAL;
    }

    /** Serializa o vetor para ficheiros e mensagens. */
    @Override
    public String toString() {
        // O formato textual e usado dentro dos registos e mensagens IN.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(v[i]);
        }
        return sb.toString();
    }

    /** Reconstroi e valida um vetor, incluindo a sua dimensao. */
    public static VectorClock parse(String s, int n) {
        // A dimensao e validada para impedir vetores incompatíveis.
        String[] f = s.trim().split(",");
        if (f.length != n) {
            throw new IllegalArgumentException("Vetor com dimensao " + f.length + ", esperado " + n + ": " + s);
        }
        long[] v = new long[n];
        for (int i = 0; i < n; i++) {
            v[i] = Long.parseLong(f[i]);
        }
        return new VectorClock(v);
    }

    @Override
    public boolean equals(Object o) {
        // Dois vetores sao iguais quando todas as componentes coincidem.
        return o instanceof VectorClock && Arrays.equals(v, ((VectorClock) o).v);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(v);
    }
}
