package tpf;

import io.github.cdimascio.dotenv.Dotenv;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.guild.GuildReadyEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.session.ShutdownEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.requests.GatewayIntent;

/**
 * The leader: a Discord bot that archives records.
 *
 * <h2>Local write path (sprint E2-02)</h2>
 * <pre>
 * /registar, #arquivo message, /rajada
 *   -> registar(autor, canal, texto)       next LSN, append to registos.log + fsync, REGISTO event
 *   -> open chunk                          sealed by size (CHUNK_MAX_BYTES) or by time (SELAGEM_MS)
 *   -> serialize + pad, encrypt            DEK derived from master.key (ChunkCrypto)
 *   -> selados/&lt;chunkId&gt;.bin + .hdr       SELADO event
 * </pre>
 * Replicas, placement, catalog, manifest and recovery come in later sprints, so
 * {@code /estado}, {@code /config}, {@code /recuperar}, {@code /replica-token} and
 * {@code /replica-remover} still give the provisional answer of sprint E2-01.
 *
 * <h2>Threads and locking</h2>
 * <ul>
 *   <li>JDA event thread: slash commands and messages from {@code #arquivo};</li>
 *   <li>burst thread: the synthetic records of {@code /rajada};</li>
 *   <li>sealer thread: the {@code SELAGEM_MS} timers and the encryption and storage of sealed chunks.</li>
 * </ul>
 * A single lock (this object's monitor) protects {@code nextLsn}, the append to
 * {@code registos.log} and the open chunk, so LSN order, log order and chunk order are
 * always the same. Encrypting and writing a sealed chunk happens on the sealer thread,
 * outside the lock, so sealing never delays the {@code fsync} of the next records.
 */
public class LeaderBot extends ListenerAdapter implements Closeable {

    /** The only channel whose messages are archived and where commands are accepted. */
    static final String ARCHIVE_CHANNEL = "arquivo";
    /** Discord role required by the administration commands. */
    static final String ADMIN_ROLE = "admin-arquivo";

    // Files and folders inside the leader's data folder (-Dtpf.pasta, current directory by default).
    static final String MASTER_KEY_FILE = "master.key";
    static final String LOG_FILE = "registos.log";
    static final String EVENTS_FILE = "eventos-lider.txt";
    static final String SEALED_DIR = "selados";

    /**
     * Length of each synthetic {@code /rajada} text. With about 440 bytes per serialized
     * record, a 64 KB chunk holds roughly 148 records, so {@code /rajada 500 0} fills
     * several chunks by size, as the acceptance criteria require.
     */
    static final int BURST_TEXT_CHARS = 400;
    /** Upper bound for {@code /rajada <n>}, to protect the leader from a typo. */
    static final int MAX_BURST_RECORDS = 100_000;
    /** Upper bound for {@code /rajada <intervaloMs>}. */
    static final int MAX_BURST_INTERVAL_MS = 60_000;

    private final ChunkCrypto crypto;
    private final EventLog events;
    private final SealedStore sealedStore;
    private final RegistoLog registoLog;
    /** Single thread: seal timers + encryption/storage of sealed chunks, in sealing order. */
    private final ScheduledThreadPoolExecutor sealer;
    /** Single thread: runs one /rajada at a time, so bursts never interleave. */
    private final ExecutorService burstRunner;

    // ---- State guarded by 'this' --------------------------------------------------
    /** LSN that the next accepted record will get. */
    private long nextLsn;
    /** Records waiting to be sealed, in LSN order (the "chunk aberto"). */
    private final List<Registo> chunkAberto = new ArrayList<>();
    /** Serialized size of the open chunk (header + records, without padding). */
    private int chunkAbertoBytes = Chunk.HEADER_BYTES;
    /** Incremented every time a chunk is sealed; lets a late timer notice its chunk is gone. */
    private long chunkGeneration;
    /** The SELAGEM_MS timer of the open chunk, if it has any record. */
    private ScheduledFuture<?> sealTimer;
    private boolean closed;

    // =================================================================== startup

    public static void main(String[] args) throws IOException {
        String token = discordToken();
        Path dataDir = Path.of(System.getProperty("tpf.pasta", "."));

        // Open the local state before connecting to Discord: if master.key or registos.log
        // are invalid, the leader refuses to start without accepting a single command.
        LeaderBot bot;
        try {
            bot = new LeaderBot(dataDir);
        } catch (IllegalStateException e) {
            System.err.println("[leader] " + e.getMessage());
            throw e;
        }

        JDABuilder.createDefault(token)
                .enableIntents(GatewayIntent.MESSAGE_CONTENT)   // needed to read the messages of #arquivo
                .addEventListeners(bot)
                .build();
    }

    /** Reads the bot token from {@code .env} (current directory) or from the environment. */
    private static String discordToken() {
        String token = Dotenv.configure().ignoreIfMissing().load().get("DISCORD_BOT_TOKEN");
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("DISCORD_BOT_TOKEN is not defined (.env file or environment variable)");
        }
        return token;
    }

    /**
     * Opens (or creates) the leader's local state in {@code dataDir}: the master key,
     * the event log, the sealed chunks and {@code registos.log}. Package-private so that
     * the tests can run the whole write path without Discord.
     */
    LeaderBot(Path dataDir) throws IOException {
        Files.createDirectories(dataDir);
        this.crypto = new ChunkCrypto(MasterKey.loadOrCreate(dataDir.resolve(MASTER_KEY_FILE)), Wire.KEY_VERSION);
        this.events = new EventLog(dataDir.resolve(EVENTS_FILE));
        this.sealedStore = new SealedStore(dataDir.resolve(SEALED_DIR));
        sealedStore.deleteLeftoverTempFiles();

        this.sealer = new ScheduledThreadPoolExecutor(1, daemonThreads("tpf-sealer"));
        sealer.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);   // close() drops pending timers
        this.burstRunner = Executors.newSingleThreadExecutor(daemonThreads("tpf-rajada"));

        // Records that are in registos.log but in no sealed chunk (the leader stopped before
        // their chunk was sealed) go back to the open chunk, so they are not left unsealed forever.
        NavigableMap<Long, Long> sealed;
        List<Registo> unsealed = new ArrayList<>();
        try {
            sealed = sealedLsnRanges();
            this.registoLog = RegistoLog.open(dataDir.resolve(LOG_FILE), registo -> {
                if (!isCovered(sealed, registo.lsn)) {
                    unsealed.add(registo);
                }
            });
        } catch (IOException | RuntimeException e) {
            // Startup refused (e.g. corrupted registos.log): release what was already opened.
            sealer.shutdownNow();
            burstRunner.shutdownNow();
            events.close();
            throw e;
        }

        synchronized (this) {
            nextLsn = registoLog.lastLsn() + 1;   // continue the numbering: no repeated or skipped LSNs
            for (Registo registo : unsealed) {
                addToOpenChunk(registo);
            }
        }

        System.out.printf("[leader] data folder %s | next LSN %d | %d sealed chunk(s) | %d unsealed record(s) re-queued%n",
                dataDir.toAbsolutePath().normalize(), registoLog.lastLsn() + 1, sealed.size(), unsealed.size());
        System.out.printf("[leader] CHUNK_MAX_BYTES=%d PAD_BYTES=%d SELAGEM_MS=%d%n",
                Wire.CHUNK_MAX_BYTES, Wire.PAD_BYTES, Wire.SELAGEM_MS);
    }

    /**
     * LSN ranges (first -> last) of the chunks already in {@code selados/}, found by
     * opening each of them. Until the catalog exists (later sprint), {@code selados/} is
     * the leader's only record of what has already been sealed.
     */
    private NavigableMap<Long, Long> sealedLsnRanges() throws IOException {
        NavigableMap<Long, Long> ranges = new TreeMap<>();
        for (String chunkId : sealedStore.ids()) {
            try {
                Chunk chunk = Chunk.ler(crypto.open(sealedStore.read(chunkId)));
                ranges.put(chunk.firstLsn(), chunk.lastLsn());
            } catch (IOException | GeneralSecurityException | IllegalArgumentException e) {
                events.log(EventLog.ERRO, "chunk=" + chunkId + " cannot be opened at startup: " + e.getMessage());
            }
        }
        return ranges;
    }

    private static boolean isCovered(NavigableMap<Long, Long> ranges, long lsn) {
        Map.Entry<Long, Long> range = ranges.floorEntry(lsn);
        return range != null && lsn <= range.getValue();
    }

    // =================================================================== write path

    /**
     * The single entry point of the write path, shared by {@code /registar}, the
     * messages of {@code #arquivo} and {@code /rajada}.
     *
     * <p>The record gets the next LSN and is appended to {@code registos.log}; the method
     * only returns after {@code fsync}, so the caller may tell the user the LSN knowing
     * the record is durable. If the write fails, nothing changes (the LSN is not consumed).
     */
    synchronized long registar(String autor, String canal, String texto) throws IOException {
        if (closed) {
            throw new IOException("the leader is shutting down");
        }
        Registo registo = new Registo(nextLsn, System.currentTimeMillis(),
                Registo.sanitizeField(autor), Registo.sanitizeField(canal), texto);
        registoLog.append(registo);   // write + fsync (throws without side effects)
        nextLsn++;                    // consumed only once the record is on disk
        events.log(EventLog.REGISTO, "lsn=" + registo.lsn + " autor=" + registo.autor + " canal=" + registo.canal);
        addToOpenChunk(registo);
        return registo.lsn;
    }

    /** Adds a record to the open chunk, starting its timer or sealing it by size. Caller holds the lock. */
    private void addToOpenChunk(Registo registo) {
        if (chunkAberto.isEmpty()) {
            // First record of a new chunk: it must be sealed at most SELAGEM_MS from now.
            long generation = chunkGeneration;
            sealTimer = sealer.schedule(() -> sealOnTimeout(generation), Wire.SELAGEM_MS, TimeUnit.MILLISECONDS);
        }
        chunkAberto.add(registo);
        chunkAbertoBytes += Chunk.recordBytes(registo);
        if (chunkAbertoBytes >= Wire.CHUNK_MAX_BYTES) {
            selar();   // the record that makes the chunk reach the limit seals it right away
        }
    }

    /** Runs on the sealer thread when the SELAGEM_MS timer of chunk {@code generation} expires. */
    private synchronized void sealOnTimeout(long generation) {
        // If that chunk was already sealed by size, the open chunk is a newer one with its own timer.
        if (generation == chunkGeneration && !chunkAberto.isEmpty()) {
            selar();
        }
    }

    /**
     * Closes the open chunk, which becomes an immutable {@link Chunk}, and hands it to the
     * sealer thread. The next record opens a new chunk. Caller holds the lock.
     */
    private void selar() {
        Chunk chunk = new Chunk(chunkAberto);
        chunkAberto.clear();
        chunkAbertoBytes = Chunk.HEADER_BYTES;
        chunkGeneration++;
        if (sealTimer != null) {
            sealTimer.cancel(false);
            sealTimer = null;
        }
        try {
            sealer.execute(() -> sealAndStore(chunk));
        } catch (RejectedExecutionException e) {
            // Shutting down: the records are safe in registos.log and are re-queued at the next start.
        }
    }

    /**
     * Serializes with padding, encrypts and stores a chunk. Runs on the sealer thread,
     * outside the lock. On failure the chunk is retried after SELAGEM_MS (it is
     * immutable, so retrying is safe; it just gets a new salt, nonce and id).
     */
    private void sealAndStore(Chunk chunk) {
        String range = chunk.firstLsn() + "-" + chunk.lastLsn();
        try {
            byte[] plaintext = chunk.serializar(Wire.PAD_BYTES);
            ChunkCrypto.Sealed sealed = crypto.seal(plaintext);
            sealedStore.write(sealed);
            events.log(EventLog.SELADO, "chunk=" + sealed.id + " lsn=" + range
                    + " registos=" + chunk.registos.size()
                    + " tamanho=" + plaintext.length            // padded plaintext: a multiple of PAD_BYTES
                    + " cifrado=" + sealed.ciphertext.length);  // .bin size = tamanho + 16-byte GCM tag
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            events.log(EventLog.ERRO, "sealing lsn=" + range + " failed (" + e + "); retrying in " + Wire.SELAGEM_MS + " ms");
            try {
                sealer.schedule(() -> sealAndStore(chunk), Wire.SELAGEM_MS, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException shuttingDown) {
                // The records stay in registos.log and are re-queued at the next start.
            }
        }
    }

    /**
     * Injects {@code n} synthetic records through {@link #registar}, one every
     * {@code intervalMs}, in the calling thread. Returns {@code {firstLsn, lastLsn}}.
     */
    long[] rajada(String autor, String canal, int n, long intervalMs) throws IOException, InterruptedException {
        String burstId = randomAlphanumeric(6);
        long firstLsn = -1;
        long lastLsn = -1;
        for (int i = 1; i <= n; i++) {
            lastLsn = registar(autor, canal, syntheticText(burstId, i, n));
            if (firstLsn < 0) {
                firstLsn = lastLsn;
            }
            if (intervalMs > 0 && i < n) {
                Thread.sleep(intervalMs);
            }
        }
        return new long[] {firstLsn, lastLsn};
    }

    /** "rajada &lt;id&gt; &lt;i&gt;/&lt;n&gt; " followed by random letters and digits, BURST_TEXT_CHARS long. */
    private static String syntheticText(String burstId, int i, int n) {
        String prefix = "rajada " + burstId + " " + i + "/" + n + " ";
        return prefix + randomAlphanumeric(Math.max(0, BURST_TEXT_CHARS - prefix.length()));
    }

    private static String randomAlphanumeric(int length) {
        final String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789";
        ThreadLocalRandom random = ThreadLocalRandom.current();
        StringBuilder text = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            text.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return text.toString();
    }

    /**
     * Stops the leader: no more records are accepted, chunks already sealed finish being
     * written and pending timers are dropped. The records of the open chunk are safe in
     * {@code registos.log} and are re-queued at the next start.
     */
    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            if (sealTimer != null) {
                sealTimer.cancel(false);
            }
        }
        burstRunner.shutdownNow();   // interrupts a running /rajada
        sealer.shutdown();
        try {
            burstRunner.awaitTermination(10, TimeUnit.SECONDS);
            sealer.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            registoLog.close();
            events.close();
        } catch (IOException e) {
            System.err.println("[leader] error while closing files: " + e);
        }
    }

    // =================================================================== Discord

    @Override
    public void onGuildReady(GuildReadyEvent event) {
        // The guild commands are already registered in Discord; registering them again
        // is only needed when they change: run once with -Dtpf.registarComandos=true.
        if (!Boolean.getBoolean("tpf.registarComandos")) {
            return;
        }
        event.getGuild().updateCommands().addCommands(
                Commands.slash("registar", "Regista um texto no arquivo").addOption(OptionType.STRING, "texto", "Texto a registar", true),
                Commands.slash("estado", "Ver o estado do sistema"),
                Commands.slash("config", "Configura parâmetros do sistema").addOption(OptionType.INTEGER, "r", "Fator de replicação", true),
                Commands.slash("recuperar", "Recupera os dados entre dois IDs").addOption(OptionType.INTEGER, "de", "Valor inicial", true).addOption(OptionType.INTEGER, "ate", "Valor final", true),
                Commands.slash("replica-token", "Gera um token de réplica"),
                Commands.slash("replica-remover", "Remove a réplica com um dado id").addOption(OptionType.STRING, "id", "ID da réplica", true),
                Commands.slash("rajada", "Faz uma rajada de n mensagens com intervaloMs").addOption(OptionType.INTEGER, "n", "Número de mensagens", true).addOption(OptionType.INTEGER, "intervaloms", "Intervalo em milissegundos", true)
        ).queue();
    }

    @Override
    public void onShutdown(ShutdownEvent event) {
        close();
    }

    /** Every human message written in #arquivo is archived through the same path as /registar. */
    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot() || event.isWebhookMessage()) return;           // bots, including this one
        if (!event.isFromGuild() || !ARCHIVE_CHANNEL.equals(event.getChannel().getName())) return;
        Message message = event.getMessage();
        if (message.getType().isSystem()) return;                                     // joins, pins, ...

        String texto = messageText(message);
        if (texto.isBlank()) return;

        try {
            long lsn = registar(event.getAuthor().getName(), event.getChannel().getName(), texto);
            String answer = "Registado com LSN " + lsn + ".";
            // Replying needs the "read message history" permission; fall back to a plain message.
            message.reply(answer).mentionRepliedUser(false)
                    .queue(null, failure -> event.getChannel().sendMessage(answer).queue());
        } catch (IOException e) {
            events.log(EventLog.ERRO, "registar (#" + ARCHIVE_CHANNEL + ") failed: " + e.getMessage());
            event.getChannel().sendMessage("Erro: não foi possível registar a mensagem (" + e.getMessage() + ").").queue();
        }
    }

    /** The message text plus the URL of each attachment, one per line. */
    private static String messageText(Message message) {
        StringBuilder text = new StringBuilder(message.getContentRaw());
        for (Message.Attachment attachment : message.getAttachments()) {
            if (text.length() > 0) text.append('\n');
            text.append(attachment.getUrl());
        }
        return text.toString();
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!ARCHIVE_CHANNEL.equals(event.getChannel().getName())) {
            event.reply("Este comando só funciona no canal #arquivo.").setEphemeral(true).queue();
            return;
        }
        switch (event.getName()) {
            case "registar" -> handleRegistar(event);
            case "rajada" -> handleRajada(event);

            // ---- Still provisional (later sprints) ----
            case "estado" -> event.reply("`/estado` recebido — ainda não implementado").queue();

            case "config" -> {
                if (!requireAdmin(event)) return;
                int r = event.getOption("r").getAsInt();
                if (r <= 0) {
                    event.reply("Erro: r tem de ser um inteiro positivo.").setEphemeral(true).queue();
                    return;
                }
                event.replyFormat("`/config` recebido: r=%d — ainda não implementado", r).queue();
            }

            case "recuperar" -> {
                int de = event.getOption("de").getAsInt();
                int ate = event.getOption("ate").getAsInt();
                if (de > ate) {
                    event.reply("O primeiro valor tem de ser menor que o segundo").setEphemeral(true).queue();
                    return;
                }
                event.replyFormat("`/recuperar` recebido: de=%s, ate=%s — ainda não implementado", de, ate).queue();
            }

            case "replica-token" -> {
                if (!requireAdmin(event)) return;
                event.reply("`/replica-token` recebido — ainda não implementado").queue();
            }

            case "replica-remover" -> {
                if (!requireAdmin(event)) return;
                event.replyFormat("`/replica-remover` recebido: id=%s — ainda não implementado", event.getOption("id").getAsString()).queue();
            }

            default -> event.reply("Comando desconhecido.").queue();
        }
    }

    /** /registar &lt;texto&gt;: answers with the LSN only after the record is in registos.log. */
    private void handleRegistar(SlashCommandInteractionEvent event) {
        String texto = event.getOption("texto").getAsString();
        if (texto.isBlank()) {
            event.reply("Erro: o texto não pode estar vazio.").setEphemeral(true).queue();
            return;
        }
        try {
            long lsn = registar(event.getUser().getName(), event.getChannel().getName(), texto);
            event.reply("Registado com LSN " + lsn + ".").queue();
        } catch (IOException e) {
            events.log(EventLog.ERRO, "/registar failed: " + e.getMessage());
            event.reply("Erro: não foi possível registar (" + e.getMessage() + ").").setEphemeral(true).queue();
        }
    }

    /** /rajada &lt;n&gt; &lt;intervaloMs&gt; (admin only): synthetic records through the same path as /registar. */
    private void handleRajada(SlashCommandInteractionEvent event) {
        if (!requireAdmin(event)) return;
        int n = event.getOption("n").getAsInt();
        int intervalMs = event.getOption("intervaloms").getAsInt();
        if (n <= 0 || n > MAX_BURST_RECORDS || intervalMs < 0 || intervalMs > MAX_BURST_INTERVAL_MS) {
            event.reply("Erro: n tem de estar entre 1 e " + MAX_BURST_RECORDS + " e intervaloMs entre 0 e "
                    + MAX_BURST_INTERVAL_MS + ".").setEphemeral(true).queue();
            return;
        }

        String autor = event.getUser().getName();
        MessageChannel channel = event.getChannel();

        // The burst runs on its own thread: the JDA thread must stay free for other events.
        Runnable startBurst = () -> {
            try {
                burstRunner.execute(() -> {
                    try {
                        long[] lsns = rajada(autor, channel.getName(), n, intervalMs);
                        channel.sendMessage("Rajada concluída: " + n + " registos, LSN " + lsns[0] + "–" + lsns[1] + ".").queue();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();   // leader shutting down
                    } catch (IOException e) {
                        events.log(EventLog.ERRO, "/rajada interrupted: " + e.getMessage());
                        channel.sendMessage("Rajada interrompida: " + e.getMessage()).queue();
                    }
                });
            } catch (RejectedExecutionException e) {
                channel.sendMessage("Erro: o líder está a terminar.").queue();
            }
        };
        // Start only once the reply is sent, so "concluída" never shows up before "a injetar".
        event.replyFormat("A injetar %d registos sintéticos com intervalo de %d ms…", n, intervalMs)
                .queue(sent -> startBurst.run(), failure -> startBurst.run());
    }

    /** Replies with an error and returns false if the member lacks the admin-arquivo role. */
    private static boolean requireAdmin(SlashCommandInteractionEvent event) {
        Member member = event.getMember();
        boolean admin = member != null && member.getRoles().stream().anyMatch(role -> role.getName().equals(ADMIN_ROLE));
        if (!admin) {
            event.reply("Comando exclusivo a membros com o papel " + ADMIN_ROLE + ".").setEphemeral(true).queue();
        }
        return admin;
    }

    // =================================================================== helpers

    private static ThreadFactory daemonThreads(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);   // the process lives as long as JDA does
            return thread;
        };
    }
}
