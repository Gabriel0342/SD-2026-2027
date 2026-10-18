package rep02;

public final class Partitioner {
    private final int n;

    public Partitioner(int n) {
        if (n < 1) {
            throw new IllegalArgumentException("N???mero de parti??????es inv???lido: " + n);
        }
        this.n = n;
    }

    // parti??????o de um sensor: determin???stica e igual em qualquer JVM
    public int of(String sensorId) {
        return Math.floorMod(sensorId.hashCode(), n);
    }

    public int count() {
        return n;
    }
}


