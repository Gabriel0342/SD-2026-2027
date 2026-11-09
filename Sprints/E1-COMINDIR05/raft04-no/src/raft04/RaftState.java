package raft04;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

public final class RaftState {
    public enum Role { SEGUIDOR, CANDIDATO, LIDER }
    private final int partition;
    private final int self;
    private final int groupSize;
    private final long minNanos;
    private final long maxNanos;
    private final Path file;
    private final Consumer<String> log;
    private final Set<Integer> votes = new HashSet<>();
    private long term;
    private int votedFor;
    private long deadline;
    private Role role = Role.SEGUIDOR;

    public RaftState(int partition, int self, Path file, Consumer<String> log) throws IOException {
        this.partition = partition; this.self = self; this.groupSize = Wire.GRUPO;
        minNanos = Wire.ELEICAO_MIN_MS * 1_000_000L;
        maxNanos = Wire.ELEICAO_MAX_MS * 1_000_000L;
        this.file = file; this.log = log;
        if (Files.exists(file)) {
            String saved = Files.readString(file).trim();
            if (!saved.isEmpty()) {
                String[] f = saved.split(";", -1);
                if (f.length != 2) throw new IOException("Estado Raft mal formado: " + file);
                term = Long.parseLong(f[0]); votedFor = Integer.parseInt(f[1]);
            }
        }
        resetDeadline();
    }

    private void resetDeadline() {
        long delay = maxNanos == minNanos ? minNanos
                : ThreadLocalRandom.current().nextLong(minNanos, maxNanos + 1);
        deadline = System.nanoTime() + delay;
    }

    private void persist() {
        // O estado e persistido antes de publicar qualquer voto. Assim, uma
        // falha apos a publicacao nao permite votar novamente no mesmo termo.
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, term + ";" + votedFor + "\n", StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Nao foi possivel persistir o estado Raft", e);
        }
    }

    private void adopt(long newTerm) {
        if (newTerm <= term) return;
        // Um termo superior invalida imediatamente o papel de lider/candidato.
        if (role == Role.LIDER) log.accept("DEMISSAO;p" + partition + ";termo=" + term);
        term = newTerm; votedFor = 0; role = Role.SEGUIDOR; votes.clear();
        persist(); resetDeadline();
    }

    public synchronized long tick() {
        if (role == Role.LIDER || System.nanoTime() < deadline) return -1;
        // O candidato vota primeiro em si proprio e so depois pede os restantes votos.
        term++; votedFor = self; role = Role.CANDIDATO; votes.clear(); votes.add(self);
        persist(); resetDeadline();
        log.accept("CANDIDATO;p" + partition + ";termo=" + term + ";no=" + self);
        return term;
    }

    public synchronized VoteReply vote(VoteRequest request, long mySeq, long myTerm) {
        if (request.term() > term) adopt(request.term());
        boolean current = request.term() == term;
        boolean upToDate = request.lastTerm() > myTerm
                || request.lastTerm() == myTerm && request.lastSeq() >= mySeq;
        // A maioria so e segura se cada no conceder, no maximo, um voto por termo
        // e se o candidato nao tiver um historico mais atrasado.
        boolean grant = current && (votedFor == 0 || votedFor == request.candidate()) && upToDate;
        if (grant) { votedFor = request.candidate(); persist(); resetDeadline(); }
        log.accept("VOTO;p" + partition + ";termo=" + term + ";candidato=n"
                + request.candidate() + ";" + (grant ? "concedido" : "recusado"));
        return new VoteReply(term, request.candidate(), self, grant);
    }

    public synchronized boolean receive(VoteReply reply) {
        if (reply.term() > term) { adopt(reply.term()); return false; }
        if (role != Role.CANDIDATO || reply.term() != term
                || reply.candidate() != self || !reply.granted()) return false;
        votes.add(reply.voter());
        if (votes.size() <= groupSize / 2) return false;
        // Com tres nos, dois votos formam a maioria e permitem assumir a lideranca.
        role = Role.LIDER;
        log.accept("LIDER;p" + partition + ";termo=" + term + ";no=" + self + ";votos=" + votes);
        return true;
    }

    public synchronized boolean acceptBatch(long batchTerm, int leader) {
        if (batchTerm < term) return false;
        if (batchTerm > term) adopt(batchTerm);
        if (role == Role.LIDER && leader != self) return false;
        role = Role.SEGUIDOR; resetDeadline();
        return true;
    }

    public synchronized long term() { return term; }
    public synchronized long leaderTerm() { return role == Role.LIDER ? term : -1; }
    public synchronized Role role() { return role; }
}
