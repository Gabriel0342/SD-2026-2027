package raft04;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/** Estado da eleicao Raft de um no dentro de uma particao. */
public final class RaftState {
    public enum Role { SEGUIDOR, CANDIDATO, LIDER }
    private final int partition;
    private final int self;
    private final int groupSize;
    private final long minNanos;
    private final long maxNanos;
    private final Path file;
    private final Consumer<String> log;
    // Guarda os identificadores dos nos que votaram no candidato neste termo.
    // O Set evita contar duas vezes uma resposta do mesmo no.
    private final Set<Integer> votes = new HashSet<>();
    // Termo atual do no; e persistido para nao ser perdido num reinicio.
    private long term;
    // Identificador do candidato a quem este no votou no termo atual.
    private int votedFor;
    private long deadline;
    private Role role = Role.SEGUIDOR;

    /** Carrega o termo/voto persistidos e inicia um novo prazo de eleicao. */
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

    /**
     * Define um novo prazo aleatorio para iniciar uma eleicao.
     * O prazo e escolhido entre os limites configurados em Wire.
     */
    private void resetDeadline() {
        long delay = maxNanos == minNanos ? minNanos
                : ThreadLocalRandom.current().nextLong(minNanos, maxNanos + 1);
        deadline = System.nanoTime() + delay;
    }

    /**
     * Grava atomicamente o termo e o voto atual num ficheiro.
     * A gravacao acontece antes de publicar um voto para que um reinicio nao
     * permita ao no votar novamente no mesmo termo.
     */
    private void persist() {
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, term + ";" + votedFor + "\n", StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Nao foi possivel persistir o estado Raft", e);
        }
    }

    /**
     * Adota um termo superior, demite o lider ou candidato atual, apaga os
     * votos temporarios e volta a colocar o no no papel de seguidor.
     */
    private void adopt(long newTerm) {
        if (newTerm <= term) return;
        if (role == Role.LIDER) log.accept("DEMISSAO;p" + partition + ";termo=" + term);
        term = newTerm; votedFor = 0; role = Role.SEGUIDOR; votes.clear();
        persist(); resetDeadline();
    }

    /**
     * Verifica o prazo de eleicao. Se expirou, incrementa o termo, transforma
     * o no em candidato, conta o voto proprio, persiste o estado e devolve o
     * novo termo para o PartitionNode publicar um PEDIDO_VOTO.
     */
    public synchronized long tick() {
        if (role == Role.LIDER || System.nanoTime() < deadline) return -1;
        term++; votedFor = self; role = Role.CANDIDATO; votes.clear(); votes.add(self);
        persist(); resetDeadline();
        log.accept("CANDIDATO;p" + partition + ";termo=" + term + ";no=" + self);
        return term;
    }

    /**
     * Avalia um PEDIDO_VOTO. Concede o voto apenas se o pedido pertence ao
     * termo atual, se este no ainda nao votou noutro candidato e se o historico
     * do candidato nao esta atrasado. Persiste o voto antes de responder.
     */
    public synchronized VoteReply vote(VoteRequest request, long mySeq, long myTerm) {
        if (request.term() > term) adopt(request.term());
        boolean current = request.term() == term;
        boolean upToDate = request.lastTerm() > myTerm
                || request.lastTerm() == myTerm && request.lastSeq() >= mySeq;
        boolean grant = current && (votedFor == 0 || votedFor == request.candidate()) && upToDate;
        if (grant) { votedFor = request.candidate(); persist(); resetDeadline(); }
        log.accept("VOTO;p" + partition + ";termo=" + term + ";candidato=n"
                + request.candidate() + ";" + (grant ? "concedido" : "recusado"));
        return new VoteReply(term, request.candidate(), self, grant);
    }

    /**
     * Recebe uma resposta VOTO destinada a este candidato. Ignora respostas
     * de outros termos/candidatos e torna o no lider quando a contagem passa
     * de metade do grupo: com tres nos, sao necessarios dois votos.
     */
    public synchronized boolean receive(VoteReply reply) {
        if (reply.term() > term) { adopt(reply.term()); return false; }
        if (role != Role.CANDIDATO || reply.term() != term
                || reply.candidate() != self || !reply.granted()) return false;
        votes.add(reply.voter());
        if (votes.size() <= groupSize / 2) return false;
        role = Role.LIDER;
        log.accept("LIDER;p" + partition + ";termo=" + term + ";no=" + self + ";votos=" + votes);
        return true;
    }

    /**
     * Aceita um lote/heartbeat do lider indicado. Um lote de termo inferior
     * e rejeitado; um termo superior faz o no adotar esse termo e seguir o
     * novo lider.
     */
    public synchronized boolean acceptBatch(long batchTerm, int leader) {
        if (batchTerm < term) return false;
        if (batchTerm > term) adopt(batchTerm);
        if (role == Role.LIDER && leader != self) return false;
        role = Role.SEGUIDOR; resetDeadline();
        return true;
    }

    /** Devolve o termo persistido e atual do no. */
    public synchronized long term() { return term; }

    /** Devolve o termo se o no for lider; caso contrario devolve -1. */
    public synchronized long leaderTerm() { return role == Role.LIDER ? term : -1; }

    /** Devolve o papel eleitoral atual do no. */
    public synchronized Role role() { return role; }
}
