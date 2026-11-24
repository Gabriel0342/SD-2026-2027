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
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import java.net.InetSocketAddress;

/** Authenticated WebSocket pub/sub hub hosted in the leader process. */
public final class PubSubHub extends WebSocketServer {
    public record Inbound(String replicaId, String message) { }
    private enum Phase { NOVA, DESAFIADA, AUTENTICADA }
    private static final class Session {
        Phase phase = Phase.NOVA;
        String id, challenge, token;
        PublicKey proposedKey, key;
        final Set<String> subscriptions = new HashSet<>();
    }
    private record Token(long createdAt) { }

    private final Path replicasFile;
    private final Consumer<Inbound> leaderReceiver;
    private final Consumer<String> events;
    private final SecureRandom random = new SecureRandom();
    private final Map<WebSocket, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, PublicKey> keys = new LinkedHashMap<>();
    private final Map<String, Token> tokens = new HashMap<>();
    private final Map<String, Set<WebSocket>> subscricoes = new HashMap<>();

    public PubSubHub(int port, Path leaderDir, Consumer<Inbound> leaderReceiver, Consumer<String> events) throws IOException {
        super(new InetSocketAddress(port));
        this.replicasFile = leaderDir.resolve("replicas.txt");
        this.leaderReceiver = leaderReceiver == null ? ignored -> { } : leaderReceiver;
        this.events = events == null ? ignored -> { } : events;
        loadKeys();
    }

    public synchronized String gerarToken() {
        byte[] bytes = new byte[24];
        String value;
        do { random.nextBytes(bytes); value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
        while (tokens.containsKey(value));
        tokens.put(value, new Token(System.currentTimeMillis()));
        return value;
    }

    public synchronized int publish(String topic, String message) {
        Set<WebSocket> subscribers = subscricoes.get(topic);
        if (subscribers == null) return 0;
        int sent = 0;
        for (WebSocket socket : List.copyOf(subscribers)) {
            if (socket.isOpen()) { socket.send("PUB;" + topic + ";" + message); sent++; }
        }
        return sent;
    }

    public synchronized void removeReplica(String id) throws IOException {
        PublicKey removed = keys.remove(id);
        if (removed == null) return;
        try { saveKeys(); }
        catch (IOException e) { keys.put(id, removed); throw e; }
        for (Map.Entry<WebSocket, Session> entry : sessions.entrySet()) {
            if (id.equals(entry.getValue().id)) entry.getKey().close(1008, "replica revoked");
        }
    }

    public synchronized Map<String, String> registeredIds() {
        Map<String, String> result = new LinkedHashMap<>();
        keys.keySet().forEach(id -> result.put(id, id));
        return Map.copyOf(result);
    }

    @Override public void onOpen(WebSocket conn, ClientHandshake handshake) { sessions.put(conn, new Session()); }

    @Override public void onMessage(WebSocket conn, String raw) {
        Session s = sessions.get(conn);
        if (s == null) { reject(conn, "connection has no session"); return; }
        try {
            String[] f = raw.startsWith("PUB;") ? raw.split(";", 3) : Wire.fields(raw);
            if (s.phase == Phase.NOVA) {
                if (f.length < 2 || !f[0].equals("OLA")) { reject(conn, "expected OLA"); return; }
                handleHello(conn, s, f);
                return;
            }
            if (s.phase == Phase.DESAFIADA) {
                if (f.length != 3 || !f[0].equals("PROVA")) { reject(conn, "expected PROVA"); return; }
                handleProof(conn, s, f);
                return;
            }
            if (f[0].equals("SUB") && f.length == 2) { subscribe(conn, s, f[1]); return; }
            if (f[0].equals("PUB") && f.length == 3) { receivePublish(conn, s, f[1], f[2]); return; }
            reject(conn, "unsupported message or wrong order");
        } catch (RuntimeException | GeneralSecurityException | IOException e) {
            reject(conn, "invalid message: " + e.getMessage());
        }
    }

    private synchronized void handleHello(WebSocket conn, Session s, String[] f) throws GeneralSecurityException {
        if (!f[1].matches("[A-Za-z0-9._-]{1,80}") || f.length != 2 && f.length != 4) {
            reject(conn, "malformed OLA"); return;
        }
        s.id = f[1];
        PublicKey registered = keys.get(s.id);
        if (registered != null) {
            if (f.length != 2) { // Existing ids never need or accept a new public key/token.
                PublicKey supplied = decodePublicKey(f[2]);
                if (!java.util.Arrays.equals(supplied.getEncoded(), registered.getEncoded())) {
                    reject(conn, "registered id used with a different key"); return;
                }
                s.token = f[3];
            }
            s.key = registered;
        } else {
            if (f.length != 4) { reject(conn, "first registration requires public key and token"); return; }
            Token token = tokens.get(f[3]);
            if (token == null || System.currentTimeMillis() - token.createdAt() >= Wire.TOKEN_VALIDADE_MS) {
                tokens.remove(f[3]); reject(conn, "unknown, used, or expired registration token"); return;
            }
            s.proposedKey = decodePublicKey(f[2]);
            s.token = f[3];
        }
        byte[] nonce = new byte[32];
        random.nextBytes(nonce);
        s.challenge = Base64.getEncoder().encodeToString(nonce);
        s.phase = Phase.DESAFIADA;
        conn.send("DESAFIO;" + s.challenge);
    }

    private synchronized void handleProof(WebSocket conn, Session s, String[] f) throws GeneralSecurityException, IOException {
        if (!s.id.equals(f[1])) { reject(conn, "proof id differs from OLA"); return; }
        PublicKey key = s.key != null ? s.key : s.proposedKey;
        if (key == null || !verificarProva(key, s.id, s.challenge, Base64.getDecoder().decode(f[2]))) {
            reject(conn, "invalid Ed25519 proof"); return;
        }
        if (s.key == null) {
            Token token = tokens.get(s.token);
            if (token == null || System.currentTimeMillis() - token.createdAt() >= Wire.TOKEN_VALIDADE_MS) {
                reject(conn, "registration token expired before proof"); return;
            }
            keys.put(s.id, key);
            try { saveKeys(); }
            catch (IOException e) { keys.remove(s.id); throw e; }
            tokens.remove(s.token); // A token is consumed only after a valid proof and durable registration.
            events.accept("REGISTO_REPLICA " + s.id);
        }
        s.key = key;
        s.proposedKey = null;
        s.challenge = null;
        s.token = null;
        s.phase = Phase.AUTENTICADA;
        events.accept("LIGADO " + s.id);
    }

    private synchronized void subscribe(WebSocket conn, Session s, String topic) {
        if (!topic.equals(Wire.TOPICO_CTL) && !topic.equals(Wire.topicReplica(s.id))) {
            reject(conn, "subscription to another replica topic"); return;
        }
        s.subscriptions.add(topic);
        subscricoes.computeIfAbsent(topic, ignored -> new HashSet<>()).add(conn);
    }

    private void receivePublish(WebSocket conn, Session s, String topic, String message) {
        if (!topic.equals(Wire.TOPICO_LIDER)) { reject(conn, "replicas may only publish to tpf-lider"); return; }
        leaderReceiver.accept(new Inbound(s.id, message));
    }

    @Override public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        Session s = sessions.remove(conn);
        if (s == null) return;
        synchronized (this) {
            for (String topic : s.subscriptions) {
                Set<WebSocket> subscribers = subscricoes.get(topic);
                if (subscribers != null) {
                    subscribers.remove(conn);
                    if (subscribers.isEmpty()) subscricoes.remove(topic);
                }
            }
        }
        if (s.phase == Phase.AUTENTICADA) events.accept("DESLIGADO " + s.id);
    }

    @Override public void onError(WebSocket conn, Exception ex) { events.accept("ERRO WebSocket: " + ex.getMessage()); }
    @Override public void onStart() { events.accept("HUB_LIGADO " + getAddress()); }

    private void reject(WebSocket conn, String reason) {
        events.accept("ERRO " + reason);
        conn.close(1008, reason);
    }

    private boolean verificarProva(PublicKey key, String id, String nonceB64, byte[] signature) throws GeneralSecurityException {
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(key);
        verifier.update(("tpf-registo|" + id + "|" + nonceB64).getBytes(StandardCharsets.UTF_8));
        return verifier.verify(signature);
    }

    private static PublicKey decodePublicKey(String encoded) throws GeneralSecurityException {
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(encoded)));
    }

    private synchronized void loadKeys() throws IOException {
        if (!Files.exists(replicasFile)) return;
        for (String line : Files.readAllLines(replicasFile, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            String[] f = line.split(";", -1);
            if (f.length != 2 || f[0].isBlank()) throw new IOException("malformed replicas.txt line");
            try { keys.put(f[0], decodePublicKey(f[1])); }
            catch (GeneralSecurityException | IllegalArgumentException e) { throw new IOException("invalid public key for replica " + f[0], e); }
        }
    }

    private synchronized void saveKeys() throws IOException {
        StringBuilder content = new StringBuilder();
        for (Map.Entry<String, PublicKey> entry : keys.entrySet())
            content.append(entry.getKey()).append(';').append(Base64.getEncoder().encodeToString(entry.getValue().getEncoded())).append('\n');
        Path tmp = Files.createTempFile(replicasFile.toAbsolutePath().getParent(), "replicas-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer b = ByteBuffer.wrap(content.toString().getBytes(StandardCharsets.UTF_8));
                while (b.hasRemaining()) channel.write(b);
                channel.force(true);
            }
            Files.move(tmp, replicasFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) { throw new IOException("atomic move unavailable for replicas.txt", e); }
        finally { Files.deleteIfExists(tmp); }
    }
}
