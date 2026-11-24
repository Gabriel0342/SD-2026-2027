package tpf;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Leader-only catalog of sealed chunks and the replicas that ACKed them. */
public final class Catalog {
    public static final class Entry {
        public final String chunkId;
        public final long lsnFrom, lsnTo, size;
        private final Set<String> replicas = new LinkedHashSet<>();
        private String state;
        Entry(String id, long from, long to, long size, Set<String> replicas, String state) {
            this.chunkId = id; this.lsnFrom = from; this.lsnTo = to; this.size = size;
            this.replicas.addAll(replicas); this.state = state;
        }
        public synchronized Set<String> replicas() { return Set.copyOf(replicas); }
        public synchronized String state() { return state; }
        synchronized boolean confirm(String id) { return replicas.add(id); }
        synchronized boolean contains(String id) { return replicas.contains(id); }
        synchronized void state(String next) { state = next; }
        String line() { return chunkId + ";" + lsnFrom + ";" + lsnTo + ";" + size + ";"
                + String.join(",", replicas()) + ";" + state(); }
    }

    private final Path file;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public Catalog(Path file) throws IOException {
        this.file = file;
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        if (Files.exists(file)) load();
    }

    public synchronized Entry adicionar(String chunkId, long from, long to, long size) throws IOException {
        if (from <= 0 || to < from || size < 0) throw new IllegalArgumentException("invalid catalog entry");
        Entry existing = entries.get(chunkId);
        if (existing != null) return existing;
        Entry entry = new Entry(chunkId, from, to, size, Set.of(), "SELADO");
        entries.put(chunkId, entry);
        gravarAtomico();
        return entry;
    }

    public synchronized boolean confirmar(String chunkId, String replicaId) throws IOException {
        Entry entry = require(chunkId);
        boolean changed = entry.confirm(replicaId);
        if (changed) gravarAtomico();
        return changed;
    }

    public synchronized void marcarDuravel(String chunkId) throws IOException {
        Entry entry = require(chunkId);
        if (!"DURAVEL".equals(entry.state())) { entry.state("DURAVEL"); gravarAtomico(); }
    }

    public synchronized Set<String> copias(String chunkId) { return require(chunkId).replicas(); }
    public synchronized Entry get(String chunkId) { return entries.get(chunkId); }
    public synchronized List<Entry> entries() { return List.copyOf(entries.values()); }
    public synchronized long countState(String state) { return entries.values().stream().filter(e -> state.equals(e.state())).count(); }
    public synchronized long countUnderReplicated() { return entries.values().stream().filter(e -> e.replicas().size() < Wire.R).count(); }

    public synchronized void gravarAtomico() throws IOException {
        StringBuilder content = new StringBuilder();
        for (Entry entry : entries.values()) content.append(entry.line()).append('\n');
        byte[] bytes = content.toString().getBytes(StandardCharsets.UTF_8);
        Path absolute = file.toAbsolutePath();
        Path tmp = Files.createTempFile(absolute.getParent(), absolute.getFileName().toString(), ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try { Files.move(tmp, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { throw new IOException("atomic move is not supported for " + file, e); }
        } finally { Files.deleteIfExists(tmp); }
    }

    private Entry require(String id) {
        Entry entry = entries.get(id);
        if (entry == null) throw new IllegalArgumentException("unknown chunk " + id);
        return entry;
    }

    private void load() throws IOException {
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            String[] fields = line.split(";", -1);
            if (fields.length != 6) throw new IOException("malformed catalog line: " + line);
            try {
                Set<String> replicas = new LinkedHashSet<>();
                if (!fields[4].isBlank()) for (String id : fields[4].split(",")) replicas.add(id);
                Entry entry = new Entry(fields[0], Long.parseLong(fields[1]), Long.parseLong(fields[2]),
                        Long.parseLong(fields[3]), replicas, fields[5]);
                if (!entry.state().equals("SELADO") && !entry.state().equals("DURAVEL"))
                    throw new IOException("invalid catalog state: " + entry.state());
                entries.put(entry.chunkId, entry);
            } catch (IllegalArgumentException e) { throw new IOException("malformed catalog line: " + line, e); }
        }
    }
}
