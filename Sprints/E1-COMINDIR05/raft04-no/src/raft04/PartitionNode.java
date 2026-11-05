package raft04;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.*;

/**
 * Nó Raft de uma partição. A eleição usa o tópico dedicado; os lotes continuam
 * a ser a unidade de replicação e de heartbeat.
 */
public final class PartitionNode {
    private final int partition;
    private final int self;
    private final int n;
    private final String api;
    private final Partitioner partitioner;
    private final RecordFile file;
    private final Path eventFile;
    private final Object lock = new Object();
    private final RaftState state;
    private long seq;
    private long hb;
    private long lamport;
    private VectorClock vector;
    private List<SensorRecord> pending = new ArrayList<>();
    private ScheduledExecutorService heartbeats;

    private PartitionNode(int partition, int self, int n, String api) throws Exception {
        this.partition = partition; this.self = self; this.n = n; this.api = api;
        partitioner = new Partitioner(n);
        file = new RecordFile("no-p" + partition + "-n" + self + ".txt");
        eventFile = Paths.get("eventos-p" + partition + "-n" + self + ".txt");
        SensorRecord last = file.last(n);
        seq = last == null ? 0 : last.getSeq();
        lamport = last == null ? 0 : last.lamport;
        vector = last == null ? new VectorClock(n) : last.vector.copy();
        state = new RaftState(partition, self,
                Paths.get("estado-p" + partition + "-n" + self + ".txt"), this::event);
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException(
                "Uso: PartitionNode <partição> <nó> [N]");
        Properties config = RuntimeConfig.load();
        int n = args.length > 2 ? Integer.parseInt(args[2])
                : Integer.parseInt(RuntimeConfig.value(config, "partitions", "3"));
        String api = RuntimeConfig.value(config, "leader.api", "http://127.0.0.1:5002/api/v0");
        PartitionNode node = new PartitionNode(Integer.parseInt(args[0]),
                Integer.parseInt(args[1]), n, api);
        node.start();
    }

    private void start() throws Exception {
        Thread input = new Thread(this::subscribeInput, "raft-input-p" + partition + "-n" + self);
        Thread data = new Thread(this::subscribeData, "raft-data-p" + partition + "-n" + self);
        Thread election = new Thread(this::subscribeElection,
                "raft-election-p" + partition + "-n" + self);
        input.start(); data.start(); election.start();
        Thread timer = new Thread(() -> {
            while (true) {
                try {
                    long candidateTerm = state.tick();
                    if (candidateTerm >= 0) publish(Wire.topicElection(partition),
                            new VoteRequest(candidateTerm, self, seq, lastTerm()).encode());
                    Thread.sleep(Wire.VERIFICACAO_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); return;
                } catch (RuntimeException e) {
                    event("ERRO;p" + partition + ";eleicao=" + e.getMessage());
                }
            }
        }, "raft-timer-p" + partition + "-n" + self);
        timer.setDaemon(true); timer.start();
        serveRecovery();
    }

    private void subscribeInput() {
        try {
            new IpfsPubSub(api).subscribe(Wire.topicIn(partition), this::onInput);
        } catch (Exception e) { event("ERRO;p" + partition + ";entrada=" + e.getMessage()); }
    }

    private void subscribeData() {
        try {
            new IpfsPubSub(api).subscribe(Wire.topicData(partition), this::onBatch);
        } catch (Exception e) { event("ERRO;p" + partition + ";dados=" + e.getMessage()); }
    }

    private void subscribeElection() {
        try {
            new IpfsPubSub(api).subscribe(Wire.topicElection(partition), message -> {
                try {
                    if (message.startsWith("PEDIDO_VOTO;")) {
                        VoteRequest request = VoteRequest.decode(message);
                        if (request.candidate() != self && !Wire.isolated(partition, self)) {
                            publish(Wire.topicElection(partition),
                                    state.vote(request, seq, lastTerm()).encode());
                        }
                    } else if (message.startsWith("VOTO;")) {
                        VoteReply reply = VoteReply.decode(message);
                        if (reply.candidate() == self && state.receive(reply)) becomeLeader();
                    }
                } catch (RuntimeException e) { event("ERRO;p" + partition + ";voto=" + e.getMessage()); }
            });
        } catch (Exception e) { event("ERRO;p" + partition + ";eleicao=" + e.getMessage()); }
    }

    private void onInput(String message) {
        if (Wire.isolated(partition, self)) return;
        try {
            String[] f = message.trim().split(";", -1);
            if (f.length != 5 || !"IN".equals(f[0]) || partitioner.of(f[1]) != partition) return;
            synchronized (lock) {
                long currentTerm = state.leaderTerm();
                if (currentTerm < 0) { event("IGNORADO;p" + partition + ";motivo=sem-lider"); return; }
                VectorClock next = vector.copy(); next.merge(VectorClock.parse(f[4], n));
                next.tick(partition);
                long nextSeq = seq + 1;
                SensorRecord record = SensorRecord.now(nextSeq, currentTerm, f[1],
                        Double.parseDouble(f[2]), Math.max(lamport, Long.parseLong(f[3])) + 1, next);
                file.append(record); seq = nextSeq; lamport = record.lamport; vector = next;
                pending.add(record);
            }
        } catch (Exception e) { event("REJEITADO;p" + partition + ";" + e.getMessage()); }
    }

    private void onBatch(String message) {
        try {
            Batch batch = Batch.decode(message, n);
            if (batch.leader == self || !state.acceptBatch(batch.term, batch.leader)) return;
            synchronized (lock) {
                for (SensorRecord record : batch.records) {
                    if (record.getSeq() > seq) { file.append(record); seq = record.getSeq(); }
                }
            }
            event("RECEBIDO;p" + partition + ";termo=" + batch.term + ";lider=n" + batch.leader);
        } catch (Exception e) { event("ERRO;p" + partition + ";lote=" + e.getMessage()); }
    }

    private void becomeLeader() {
        synchronized (lock) {
            if (heartbeats != null) return;
            pending = new ArrayList<>(); hb = 0;
            heartbeats = Executors.newSingleThreadScheduledExecutor();
            heartbeats.scheduleAtFixedRate(this::sendBatch, 0, Wire.PERIODO_HB_MS,
                    TimeUnit.MILLISECONDS);
        }
    }

    private void sendBatch() {
        Batch batch;
        synchronized (lock) {
            if (state.leaderTerm() < 0) { heartbeats.shutdown(); heartbeats = null; return; }
            List<SensorRecord> records = pending; pending = new ArrayList<>(); hb++;
            batch = new Batch(state.term(), self, "127.0.0.1", Wire.nodePort(partition, self),
                    hb, seq, lastTerm(), records);
        }
        try {
            publish(Wire.topicData(partition), batch.encode());
            event("LOTE;p" + partition + ";termo=" + batch.term + ";lider=n" + self
                    + ";hb=" + batch.hb);
        } catch (RuntimeException e) { event("ERRO;p" + partition + ";publicacao=" + e.getMessage()); }
    }

    private void publish(String topic, String message) {
        if (Wire.isolated(partition, self)
                && !topic.equals(Wire.topicIn(partition))) return;
        try { new IpfsPubSub(api).publish(topic, message); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private long lastTerm() {
        try { SensorRecord last = file.last(n); return last == null ? 0 : last.term; }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }

    private void serveRecovery() {
        try (ServerSocket server = new ServerSocket(Wire.nodePort(partition, self))) {
            while (true) {
                try (Socket socket = server.accept();
                     BufferedReader in = new BufferedReader(new InputStreamReader(
                             socket.getInputStream(), StandardCharsets.UTF_8));
                     PrintWriter out = new PrintWriter(new OutputStreamWriter(
                             socket.getOutputStream(), StandardCharsets.UTF_8), true)) {
                    String request = in.readLine();
                    if (request != null && request.startsWith("PEDIDO ")) {
                        String[] f = request.split("\\s+");
                        for (SensorRecord record : file.range(Long.parseLong(f[1]), Long.parseLong(f[2])))
                            out.println(record.toLine());
                    }
                } catch (Exception e) { event("ERRO;p" + partition + ";tcp=" + e.getMessage()); }
            }
        } catch (IOException e) { event("ERRO;p" + partition + ";servidor=" + e.getMessage()); }
    }

    private void event(String message) {
        String line = System.currentTimeMillis() + ";" + message;
        System.out.println(line);
        try { Files.writeString(eventFile, line + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
        catch (IOException e) { throw new UncheckedIOException("Não foi possível escrever eventos", e); }
    }
}
