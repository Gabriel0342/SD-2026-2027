package tpf;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Thread-safe view of the registered replicas and their effective free space. */
public final class ReplicaRegistry {
    public enum State { VIVA, SUSPEITA, MORTA }

    public static final class Snapshot {
        public final String id;
        public final long quota, used, reserved, lastHeartbeat;
        public final int chunks;
        public final State state;
        Snapshot(Replica replica) {
            id = replica.id; quota = replica.quota; used = replica.used; reserved = replica.reserved;
            lastHeartbeat = replica.monitor.lastHeartbeat(); chunks = replica.chunks;
            state = replica.state;
        }
        public long free() { return Math.max(0, quota - used - reserved); }
    }

    private static final class Replica {
        final String id;
        final HeartbeatMonitor monitor = new HeartbeatMonitor();
        long quota, used, reserved;
        int chunks;
        State state = State.MORTA;
        Replica(String id) { this.id = id; }
    }

    private static final class Reservation {
        final String replica;
        final long bytes;
        Reservation(String replica, long bytes) { this.replica = replica; this.bytes = bytes; }
    }

    private final Map<String, Replica> replicas = new LinkedHashMap<>();
    private final Map<String, Reservation> reservations = new HashMap<>(); // key = chunkId + '|' + replicaId
    private final Placement placement = new Placement(Wire.ALFA, Wire.MARGEM_BYTES);
    private final Consumer<String> events;

    public ReplicaRegistry(Consumer<String> events) { this.events = events == null ? ignored -> { } : events; }

    public synchronized void registerKnown(String id) {
        replicas.computeIfAbsent(id, Replica::new);
    }

    public synchronized void onHeartbeat(String id, long used, long quota, int chunks) {
        if (id == null || id.isBlank() || used < 0 || quota < 0 || used > quota || chunks < 0)
            throw new IllegalArgumentException("invalid heartbeat");
        Replica r = replicas.computeIfAbsent(id, Replica::new);
        State before = r.state;
        r.used = Math.max(r.used, used); // Usage is monotonic in this sprint (DELETE is out of scope).
        r.quota = quota;
        r.chunks = chunks;
        State after = convert(r.monitor.onHeartbeat(System.currentTimeMillis()));
        r.state = after;
        if (before != after) events.accept(id + " " + before + " -> " + after);
    }

    /** Atomically selects replicas and reserves their capacity before returning. */
    public synchronized List<String> reserveAndChoose(String chunkId, long size, int count, Set<String> exclude) {
        atualizarEstados(System.currentTimeMillis());
        Collection<Placement.Candidate> candidates = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Replica r : replicas.values()) {
            if (convert(r.monitor.state(now)) == State.VIVA) {
                long free = r.quota - r.used - r.reserved;
                if (free > 0) candidates.add(new Placement.Candidate(r.id, free));
            }
        }
        List<String> chosen = placement.choose(chunkId, size, count, candidates, exclude);
        for (String id : chosen) {
            Replica r = replicas.get(id);
            r.reserved = Math.addExact(r.reserved, size);
            reservations.put(reservationKey(chunkId, id), new Reservation(id, size));
        }
        return List.copyOf(chosen);
    }

    /** Moves a pending STORE reservation into used space once its authenticated ACK arrives. */
    public synchronized boolean confirmarReserva(String chunkId, String replicaId, long size) {
        Reservation reservation = reservations.remove(reservationKey(chunkId, replicaId));
        Replica r = replicas.get(replicaId);
        if (r == null) return false;
        long bytes = reservation == null ? size : reservation.bytes;
        r.reserved = Math.max(0, r.reserved - bytes);
        r.used = r.used > Long.MAX_VALUE - bytes ? Long.MAX_VALUE : r.used + bytes;
        r.chunks++;
        return true;
    }

    public synchronized void libertar(String chunkId, String replicaId) {
        Reservation reservation = reservations.remove(reservationKey(chunkId, replicaId));
        Replica r = replicas.get(replicaId);
        if (reservation != null && r != null) r.reserved = Math.max(0, r.reserved - reservation.bytes);
    }

    public synchronized void remove(String id) { replicas.remove(id); }

    public synchronized State estado(String id) {
        atualizarEstados(System.currentTimeMillis());
        Replica replica = replicas.get(id);
        return replica == null ? State.MORTA : replica.state;
    }

    public synchronized List<Snapshot> snapshots() {
        atualizarEstados(System.currentTimeMillis());
        return replicas.values().stream().map(Snapshot::new)
                .sorted(Comparator.comparing(s -> s.id)).toList();
    }

    public synchronized void atualizarEstados(long agora) {
        for (Replica r : replicas.values()) {
            State current = convert(r.monitor.state(agora));
            State previous = r.state;
            if (current != previous) {
                r.state = current;
                events.accept(r.id + " " + previous + " -> " + current);
            }
        }
    }

    private static String reservationKey(String chunkId, String replicaId) { return chunkId + "|" + replicaId; }
    private static State convert(HeartbeatMonitor.State state) { return State.valueOf(state.name()); }
}
