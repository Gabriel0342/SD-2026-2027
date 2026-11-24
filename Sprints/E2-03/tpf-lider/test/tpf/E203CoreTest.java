package tpf;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

/** Focused checks for deterministic placement, atomic reservations and the durable catalog. */
final class E203CoreTest {
    private E203CoreTest() { }

    static void run() throws Exception {
        TestSupport.section("E203CoreTest");
        placementDistribution();
        reservationAtomicity();
        catalogPersistence();
    }

    private static void placementDistribution() {
        Placement placement = new Placement(1.0, 0);
        List<Placement.Candidate> candidates = List.of(
                new Placement.Candidate("a", 8_000_000), new Placement.Candidate("b", 2_000_000),
                new Placement.Candidate("c", 4_000_000), new Placement.Candidate("d", 6_000_000));
        int[] first = new int[4];
        boolean distinct = true, deterministic = true;
        for (int i = 0; i < 10_000; i++) {
            String id = "chunk-" + i;
            List<String> selected = placement.choose(id, 1, 3, candidates, Set.of());
            distinct &= selected.size() == 3 && Set.copyOf(selected).size() == 3;
            deterministic &= selected.equals(placement.choose(id, 1, 3, candidates, Set.of()));
            first["abcd".indexOf(selected.get(0))]++;
        }
        TestSupport.check(distinct, "placement selects R distinct candidates");
        TestSupport.check(deterministic, "placement is deterministic for a fixed chunk and free-space view");
        TestSupport.check(Math.abs(first[0] / 10_000.0 - 0.40) < 0.03
                        && Math.abs(first[1] / 10_000.0 - 0.10) < 0.025,
                "alpha=1 first-choice share follows free space (8 MB about 40%, 2 MB about 10%)");
    }

    private static void reservationAtomicity() throws Exception {
        ReplicaRegistry registry = new ReplicaRegistry(ignored -> { });
        registry.onHeartbeat("a", 0, 150_000, 0);
        registry.onHeartbeat("b", 0, 150_000, 0);
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var one = pool.submit(() -> { start.await(); return registry.reserveAndChoose("chunk-1", 70_000, 1, Set.of()); });
            var two = pool.submit(() -> { start.await(); return registry.reserveAndChoose("chunk-2", 70_000, 1, Set.of()); });
            start.countDown();
            List<String> first = one.get(), second = two.get();
            TestSupport.check(first.size() == 1 && second.size() == 1 && !first.get(0).equals(second.get(0)),
                    "concurrent selections reserve capacity before another chunk is placed");
            ReplicaRegistry.Snapshot reserved = registry.snapshots().stream()
                    .filter(s -> s.id.equals(first.get(0))).findFirst().orElseThrow();
            TestSupport.check(reserved.reserved == 70_000 && reserved.free() == 80_000,
                    "reservation reduces effective free space immediately");
            TestSupport.check(registry.confirmarReserva("chunk-1", first.get(0), 70_000)
                            && registry.snapshots().stream().filter(s -> s.id.equals(first.get(0)))
                            .findFirst().orElseThrow().used == 70_000,
                    "valid ACK transfers reserved bytes to used bytes");
            registry.libertar("chunk-2", second.get(0));
            TestSupport.check(registry.snapshots().stream().filter(s -> s.id.equals(second.get(0)))
                            .findFirst().orElseThrow().reserved == 0,
                    "timeout releases its pending reservation");
        } finally { pool.shutdownNow(); }
    }

    private static void catalogPersistence() throws Exception {
        Path dir = TestSupport.tempDir("tpf-e203-catalog-");
        try {
            Path file = dir.resolve("catalogo.txt");
            Catalog catalog = new Catalog(file);
            Catalog.Entry entry = catalog.adicionar("a".repeat(64), 1, 4, 8192);
            catalog.confirmar(entry.chunkId, "replica-a");
            catalog.marcarDuravel(entry.chunkId);
            String line = Files.readString(file, StandardCharsets.UTF_8).trim();
            TestSupport.check(line.equals("a".repeat(64) + ";1;4;8192;replica-a;DURAVEL"),
                    "catalog uses the required line format and atomic rewrite");
            Catalog reloaded = new Catalog(file);
            TestSupport.check(reloaded.get(entry.chunkId).state().equals("DURAVEL")
                            && reloaded.copias(entry.chunkId).equals(Set.of("replica-a")),
                    "catalog loads confirmed copies and state after leader restart");
        } finally { TestSupport.deleteRecursively(dir); }
    }
}
