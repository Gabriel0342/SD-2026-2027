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
 * No Raft de uma particao. A eleicao usa um topico dedicado; os lotes
 * continuam a ser a unidade de replicacao e de heartbeat.
 */
public final class PartitionNode {
    // ---------------- Identidade e comunicacao ----------------
    private final int partition;
    private final int self;
    private final int n;
    private final String api;
    private final Partitioner partitioner;
    private final RecordFile file;
    private final Path eventFile;
    // Este lock protege apenas o estado dos dados. O estado eleitoral e
    // protegido separadamente pelos metodos synchronized de RaftState.
    private final Object lock = new Object();
    private final RaftState state;
    private long seq;
    private long hb;
    private long lamport;
    private VectorClock vector;
    private List<SensorRecord> pending = new ArrayList<>();
    private ScheduledExecutorService heartbeats;

    // ---------------- Construcao e recuperacao ----------------
    private PartitionNode(int partition, int self, int n, String api) throws Exception {
        this.partition = partition; this.self = self; this.n = n; this.api = api;
        partitioner = new Partitioner(n);
        file = new RecordFile("no-p" + partition + "-n" + self + ".txt");
        eventFile = Paths.get("eventos-p" + partition + "-n" + self + ".txt");
        // O no retoma seq, Lamport e V a partir do ultimo registo duravel.
        SensorRecord last = file.last(n);
        seq = last == null ? 0 : last.getSeq();
        lamport = last == null ? 0 : last.lamport;
        vector = last == null ? new VectorClock(n) : last.vector.copy();
        state = new RaftState(partition, self,
                Paths.get("estado-p" + partition + "-n" + self + ".txt"), this::event);
    }

    public static void main(String[] args) throws Exception {
        // Cada processo representa um no de uma particao: <particao> <no> [N].
        if (args.length < 2) throw new IllegalArgumentException(
                "Uso: PartitionNode <particao> <no> [N]");
        Properties config = RuntimeConfig.load();
        int n = args.length > 2 ? Integer.parseInt(args[2])
                : Integer.parseInt(RuntimeConfig.value(config, "partitions", "3"));
        String api = RuntimeConfig.value(config, "leader.api", "http://127.0.0.1:5002/api/v0");
        PartitionNode node = new PartitionNode(Integer.parseInt(args[0]),
                Integer.parseInt(args[1]), n, api);
        node.start();
    }

    private void start() throws Exception {
        // As tres subscricoes bloqueantes correm em paralelo: entradas,
        // replicacao/heartbeats e mensagens de eleicao.
        Thread input = new Thread(this::subscribeInput, "raft-input-p" + partition + "-n" + self);
        Thread data = new Thread(this::subscribeData, "raft-data-p" + partition + "-n" + self);
        Thread election = new Thread(this::subscribeElection,
                "raft-election-p" + partition + "-n" + self);
        input.start(); data.start(); election.start();
        Thread timer = new Thread(() -> {
            // O temporizador verifica se o prazo expirou e inicia eleicoes.
            while (true) {
                try {
                    long candidateTerm = state.tick();
                    // O estado e persistido por tick() antes deste pedido ser
                    // publicado no topico de eleicao.
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
        // Escuta leituras publicadas pelo produtor na particao deste no.
        try {
            new IpfsPubSub(api).subscribe(Wire.topicIn(partition), this::onInput);
        } catch (Exception e) { event("ERRO;p" + partition + ";entrada=" + e.getMessage()); }
    }

    private void subscribeData() {
        // Escuta lotes do lider; cada lote tambem funciona como heartbeat.
        try {
            new IpfsPubSub(api).subscribe(Wire.topicData(partition), this::onBatch);
        } catch (Exception e) { event("ERRO;p" + partition + ";dados=" + e.getMessage()); }
    }

    private void subscribeElection() {
        // Trata pedidos de voto e respostas no canal exclusivo da eleicao.
        try {
            new IpfsPubSub(api).subscribe(Wire.topicElection(partition), message -> {
                try {
                    if (message.startsWith("PEDIDO_VOTO;")) {
                        // Um pedido de outro candidato e respondido apenas se
                        // este no nao estiver isolado.
                        VoteRequest request = VoteRequest.decode(message);
                        if (request.candidate() != self && !Wire.isolated(partition, self)) {
                            publish(Wire.topicElection(partition),
                                    state.vote(request, seq, lastTerm()).encode());
                        }
                    } else if (message.startsWith("VOTO;")) {
                        // Apenas o candidato identificado na resposta pode
                        // contabilizar esse voto. Uma resposta de outro
                        // candidato nao ajuda esta eleicao.
                        VoteReply reply = VoteReply.decode(message);
                        // So o candidato destinatario pode contar esta resposta.
                        if (reply.candidate() == self && state.receive(reply)) becomeLeader();
                    }
                } catch (RuntimeException e) { event("ERRO;p" + partition + ";voto=" + e.getMessage()); }
            });
        } catch (Exception e) { event("ERRO;p" + partition + ";eleicao=" + e.getMessage()); }
    }

    private void onInput(String message) {
        // Uma leitura so e aceite pelo no que se considera lider.
        if (Wire.isolated(partition, self)) return;
        try {
            String[] f = message.trim().split(";", -1);
            // Validar o formato e a particao evita guardar mensagens alheias.
            if (f.length != 5 || !"IN".equals(f[0]) || partitioner.of(f[1]) != partition) return;
            synchronized (lock) {
                // A sequencia, o relogio de Lamport e o vetor causal sao
                // atualizados juntos para evitar estados parcialmente visiveis.
                long currentTerm = state.leaderTerm();
                if (currentTerm < 0) { event("IGNORADO;p" + partition + ";motivo=sem-lider"); return; }
                VectorClock next = vector.copy(); next.merge(VectorClock.parse(f[4], n));
                next.tick(partition);
                long nextSeq = seq + 1;
                // A leitura recebe o termo atual, uma nova sequencia, L e V.
                SensorRecord record = SensorRecord.now(nextSeq, currentTerm, f[1],
                        Double.parseDouble(f[2]), Math.max(lamport, Long.parseLong(f[3])) + 1, next);
                file.append(record); seq = nextSeq; lamport = record.lamport; vector = next;
                pending.add(record);
            }
        } catch (Exception e) { event("REJEITADO;p" + partition + ";" + e.getMessage()); }
    }

    private void onBatch(String message) {
        // Seguidores aplicam os registos novos recebidos do lider atual.
        try {
            Batch batch = Batch.decode(message, n);
            if (batch.leader == self || !state.acceptBatch(batch.term, batch.leader)) return;
            synchronized (lock) {
                // O seguidor so acrescenta registos posteriores ao seu ultimo
                // registo conhecido; a recuperacao completa trata lacunas.
                for (SensorRecord record : batch.records) {
                    if (record.getSeq() > seq) { file.append(record); seq = record.getSeq(); }
                }
            }
            event("RECEBIDO;p" + partition + ";termo=" + batch.term + ";lider=n" + batch.leader);
        } catch (Exception e) { event("ERRO;p" + partition + ";lote=" + e.getMessage()); }
    }

    private void becomeLeader() {
        // A eleicao ja foi decidida em RaftState; este metodo inicia a
        // atividade periodica especifica do papel de lider.
        synchronized (lock) {
            if (heartbeats != null) return;
            // Ha apenas um produtor de heartbeats por no lider.
            pending = new ArrayList<>(); hb = 0;
            heartbeats = Executors.newSingleThreadScheduledExecutor();
            heartbeats.scheduleAtFixedRate(this::sendBatch, 0, Wire.PERIODO_HB_MS,
                    TimeUnit.MILLISECONDS);
        }
    }

    private void sendBatch() {
        // Cada lote transporta registos pendentes e o progresso conhecido,
        // mesmo quando esta vazio e serve apenas como heartbeat.
        Batch batch;
        synchronized (lock) {
            // A lista pendente e trocada dentro do lock para nao perder
            // leituras que cheguem enquanto o lote esta a ser construido.
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
        // O marcador de isolamento simula uma falha de comunicacao do no.
        if (Wire.isolated(partition, self)
                && !topic.equals(Wire.topicIn(partition))) return;
        try { new IpfsPubSub(api).publish(topic, message); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private long lastTerm() {
        // O ultimo termo dos dados e usado para comparar historicos em eleicoes.
        try { SensorRecord last = file.last(n); return last == null ? 0 : last.term; }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }

    private void serveRecovery() {
        // Servidor TCP usado para fornecer intervalos de registos a nos atrasados.
        try (ServerSocket server = new ServerSocket(Wire.nodePort(partition, self))) {
            while (true) {
                try (Socket socket = server.accept();
                     BufferedReader in = new BufferedReader(new InputStreamReader(
                             socket.getInputStream(), StandardCharsets.UTF_8));
                     PrintWriter out = new PrintWriter(new OutputStreamWriter(
                             socket.getOutputStream(), StandardCharsets.UTF_8), true)) {
                    String request = in.readLine();
                    if (request != null && request.startsWith("PEDIDO ")) {
                        // O pedido contem os limites inclusivos do intervalo.
                        String[] f = request.split("\\s+");
                        for (SensorRecord record : file.range(Long.parseLong(f[1]), Long.parseLong(f[2])))
                            out.println(record.toLine());
                    }
                } catch (Exception e) { event("ERRO;p" + partition + ";tcp=" + e.getMessage()); }
            }
        } catch (IOException e) { event("ERRO;p" + partition + ";servidor=" + e.getMessage()); }
    }

    private void event(String message) {
        // Cada evento e escrito na consola e no ficheiro para verificacao pos-ensaio.
        String line = System.currentTimeMillis() + ";" + message;
        System.out.println(line);
        try { Files.writeString(eventFile, line + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
        catch (IOException e) { throw new UncheckedIOException("Nao foi possivel escrever eventos", e); }
    }
}
