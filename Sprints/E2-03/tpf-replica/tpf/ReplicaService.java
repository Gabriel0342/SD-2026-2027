package tpf;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Opaque storage service for one volunteer replica. */
public final class ReplicaService {
    private final String id;
    private final URI url;
    private final long quota;
    private final String token;
    private final Path dir;
    private final Path eventsFile;
    private final KeyPair keys;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "tpf-replica-timer"); t.setDaemon(true); return t;
    });
    private final AtomicLong used = new AtomicLong();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final Object storeLock = new Object();
    private volatile WebSocket socket;
    private volatile boolean authenticated;
    private volatile ScheduledFuture<?> heartbeatTask;

    private ReplicaService(String id, URI url, long quota, String token) throws IOException, GeneralSecurityException {
        if (!id.matches("[A-Za-z0-9._-]{1,80}")) throw new IllegalArgumentException("invalid replica id");
        if (quota < 0) throw new IllegalArgumentException("quotaBytes must be non-negative");
        this.id = id; this.url = url; this.quota = quota; this.token = token;
        this.dir = Path.of(System.getProperty("tpf.pasta", "dados-" + id));
        Files.createDirectories(dir);
        this.eventsFile = dir.resolve("eventos-" + id + ".txt");
        this.keys = loadOrCreateKeys(dir);
        try (var files = Files.list(dir)) {
            used.set(files.filter(p -> p.getFileName().toString().endsWith(".bin"))
                    .mapToLong(p -> { try { return Files.size(p); } catch (IOException e) { throw new IllegalStateException(e); } }).sum());
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3 || args.length > 4) {
            System.err.println("Usage: ReplicaService <id> <url> <quotaBytes> [token]");
            System.exit(2);
        }
        long quota = Long.parseLong(args[2]);
        new ReplicaService(args[0], URI.create(args[1]), quota, args.length == 4 ? args[3] : null).run();
    }

    private void run() throws InterruptedException {
        long backoff = Wire.BACKOFF_MIN_MS;
        while (!stopping.get()) {
            CompletableFuture<Void> closed = new CompletableFuture<>();
            authenticated = false;
            boolean connected = false;
            try {
                HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(url, new Listener(closed)).join();
                connected = true;
                log("LIGADO");
                closed.get();
            } catch (Exception e) {
                if (!stopping.get()) log("ERRO " + rootMessage(e));
            }
            socket = null;
            authenticated = false;
            if (stopping.get()) break;
            if (connected) backoff = Wire.BACKOFF_MIN_MS;
            log("DESLIGADO");
            log("RELIGAR " + backoff);
            Thread.sleep(backoff);
            backoff = Math.min(Wire.BACKOFF_MAX_MS, Math.max(Wire.BACKOFF_MIN_MS, backoff * 2));
        }
        timer.shutdownNow();
    }

    private final class Listener implements WebSocket.Listener {
        private final CompletableFuture<Void> closed;
        private final StringBuilder fragments = new StringBuilder();
        Listener(CompletableFuture<Void> closed) { this.closed = closed; }

        @Override public void onOpen(WebSocket webSocket) {
            socket = webSocket;
            sendHello(webSocket);
            WebSocket.Listener.super.onOpen(webSocket);
        }
        @Override public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            synchronized (fragments) {
                fragments.append(data);
                if (last) {
                    String message = fragments.toString(); fragments.setLength(0);
                    handleMessage(webSocket, message);
                }
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            if (heartbeatTask != null) heartbeatTask.cancel(false);
            closed.complete(null); return CompletableFuture.completedFuture(null);
        }
        @Override public void onError(WebSocket ws, Throwable error) {
            if (heartbeatTask != null) heartbeatTask.cancel(false);
            closed.completeExceptionally(error);
        }
    }

    private void sendHello(WebSocket ws) {
        String hello = token == null ? "OLA;" + id
                : "OLA;" + id + ";" + Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()) + ";" + token;
        send(ws, hello);
    }

    private void handleMessage(WebSocket ws, String raw) {
        try {
            String[] f = raw.startsWith("PUB;") ? raw.split(";", 3) : Wire.fields(raw);
            switch (f[0]) {
                case "DESAFIO" -> {
                    if (f.length != 2) throw new IOException("malformed DESAFIO");
                    String nonce = f[1];
                    Signature signature = Signature.getInstance("Ed25519");
                    signature.initSign(keys.getPrivate());
                    signature.update(("tpf-registo|" + id + "|" + nonce).getBytes(StandardCharsets.UTF_8));
                    send(ws, "PROVA;" + id + ";" + Base64.getEncoder().encodeToString(signature.sign()));
                    authenticated = true;
                    send(ws, "SUB;" + Wire.topicReplica(id));
                    send(ws, "SUB;" + Wire.TOPICO_CTL);
                    sendInventory(ws);
                    heartbeatTask = timer.scheduleAtFixedRate(() -> sendHeartbeat(ws), Wire.PERIODO_HB_MS,
                            Wire.PERIODO_HB_MS, TimeUnit.MILLISECONDS);
                }
                case "PUB" -> {
                    if (f.length < 3 || !f[1].equals(Wire.topicReplica(id))) throw new IOException("message on invalid topic");
                    handleCommand(ws, f[2]);
                }
                default -> throw new IOException("unsupported hub message " + f[0]);
            }
        } catch (Exception e) {
            log("ERRO " + e.getMessage());
            ws.abort();
        }
    }

    private void handleCommand(WebSocket ws, String command) throws IOException, GeneralSecurityException {
        String[] f = Wire.fields(command);
        switch (f[0]) {
            case "STORE" -> {
                if (f.length != 4) throw new IOException("malformed STORE");
                store(ws, f[1], f[2], f[3]);
            }
            default -> { /* Commands such as FETCH and DELETE belong to a later sprint. */ }
        }
    }

    private void store(WebSocket ws, String chunkId, String headerB64, String binB64) throws IOException, GeneralSecurityException {
        if (!chunkId.matches("[0-9a-f]{64}")) throw new IOException("invalid chunk id");
        byte[] bin = Base64.getDecoder().decode(binB64);
        byte[] header = Base64.getDecoder().decode(headerB64);
        String hash = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bin));
        if (!hash.equals(chunkId)) { log("ERRO STORE hash mismatch " + chunkId); return; }
        synchronized (storeLock) {
            Path binFile = dir.resolve(chunkId + ".bin"), hdrFile = dir.resolve(chunkId + ".hdr");
            if (Files.exists(binFile)) {
                byte[] existing = Files.readAllBytes(binFile);
                String existingHash = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(existing));
                if (existingHash.equals(chunkId)) { sendAck(ws, chunkId, hash); return; }
                log("ERRO stored chunk hash mismatch " + chunkId);
                return;
            }
            if (used.get() + bin.length > quota) { log("QUOTA " + chunkId); return; }
            writeAtomic(hdrFile, header);
            writeAtomic(binFile, bin); // commit point; ACK is sent only after both atomic moves
            used.addAndGet(bin.length);
        }
        log("STORE " + chunkId);
        sendAck(ws, chunkId, hash);
    }

    private void sendAck(WebSocket ws, String chunkId, String hash) {
        send(ws, "PUB;" + Wire.TOPICO_LIDER + ";ACK;" + id + ";" + chunkId + ";" + hash);
    }

    private void sendHeartbeat(WebSocket ws) {
        if (!authenticated || ws != socket || ws.isOutputClosed()) return;
        send(ws, "PUB;" + Wire.TOPICO_LIDER + ";HB;" + id + ";" + used.get() + ";" + quota);
    }

    private void sendInventory(WebSocket ws) {
        try (var files = Files.list(dir)) {
            String ids = String.join(",", files.filter(p -> p.getFileName().toString().endsWith(".bin"))
                    .map(p -> p.getFileName().toString().replaceFirst("\\.bin$", "")).sorted().toList());
            send(ws, "PUB;" + Wire.TOPICO_LIDER + ";INVENTARIO;" + id + ";" + ids);
        } catch (IOException e) { log("ERRO inventory: " + e.getMessage()); }
    }

    private static void writeAtomic(Path target, byte[] data) throws IOException {
        Path tmp = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(data);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try { Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { throw new IOException("atomic move unavailable for " + target, e); }
        } finally { Files.deleteIfExists(tmp); }
    }

    private static KeyPair loadOrCreateKeys(Path dir) throws IOException, GeneralSecurityException {
        Path privateFile = dir.resolve("chave-privada.der"), publicFile = dir.resolve("chave-publica.der");
        KeyFactory factory = KeyFactory.getInstance("Ed25519");
        if (Files.exists(privateFile) && Files.exists(publicFile)) {
            return new KeyPair(factory.generatePublic(new X509EncodedKeySpec(Files.readAllBytes(publicFile))),
                    factory.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(privateFile))));
        }
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        writeAtomic(privateFile, pair.getPrivate().getEncoded());
        restrictOwnerOnly(privateFile, false);
        writeAtomic(publicFile, pair.getPublic().getEncoded());
        restrictOwnerOnly(publicFile, true);
        return pair;
    }

    private static void restrictOwnerOnly(Path file, boolean publicKey) {
        try {
            if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(publicKey ? "r--------" : "r--------"));
                return;
            }
            AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
            if (acl != null) {
                UserPrincipal owner = acl.getOwner();
                AclEntry entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                        .setPermissions(AclEntryPermission.READ_DATA, AclEntryPermission.READ_ATTRIBUTES,
                                AclEntryPermission.READ_NAMED_ATTRS, AclEntryPermission.READ_ACL,
                                AclEntryPermission.SYNCHRONIZE, AclEntryPermission.DELETE).build();
                acl.setAcl(List.of(entry));
            }
        } catch (IOException e) { throw new IllegalStateException("could not restrict key permissions", e); }
    }

    private void send(WebSocket ws, String text) {
        if (ws != null && !ws.isOutputClosed()) ws.sendText(text, true);
    }
    private void log(String event) {
        String line = System.currentTimeMillis() + ";" + event.replace('\n', ' ').replace('\r', ' ') + System.lineSeparator();
        synchronized (this) {
            try { Files.writeString(eventsFile, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
            catch (IOException e) { System.err.println("[replica] cannot write event: " + e); }
        }
    }
    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return String.valueOf(t.getMessage());
    }
}
