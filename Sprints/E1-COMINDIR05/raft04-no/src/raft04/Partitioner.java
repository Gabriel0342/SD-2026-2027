package raft04;

/** Mapeia sensores para partiÃ§Ãµes de forma determinÃ­stica. */
public final class Partitioner {
    private final int n;

    /** Valida o nÃºmero de partiÃ§Ãµes usado para dimensionar os vetores. */
    public Partitioner(int n) {
        if (n < 1) {
            throw new IllegalArgumentException("numero de particoes invalido: " + n);
        }
        this.n = n;
    }

    /** Usa o hash do sensor, normalizado para nunca produzir Ã­ndice negativo. */
    public int of(String sensorId) {
        return Math.floorMod(sensorId.hashCode(), n);
    }

    public int count() {
        return n;
    }
}



