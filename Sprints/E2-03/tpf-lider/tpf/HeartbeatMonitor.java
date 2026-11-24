package tpf;

/** Tracks one replica's liveness from the time of its last valid heartbeat. */
final class HeartbeatMonitor {
    enum State { VIVA, SUSPEITA, MORTA }

    private long lastHeartbeat;
    private State state = State.MORTA;

    synchronized State onHeartbeat(long now) {
        lastHeartbeat = now;
        state = State.VIVA;
        return state;
    }

    synchronized State state(long now) {
        if (lastHeartbeat == 0 || now - lastHeartbeat >= Wire.MORTA_MS) return State.MORTA;
        if (now - lastHeartbeat >= Wire.SUSPEITA_MS) return State.SUSPEITA;
        return State.VIVA;
    }

    synchronized long lastHeartbeat() { return lastHeartbeat; }
}
