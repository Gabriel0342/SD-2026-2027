package rep04;

import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Observa todas as partições e transforma leituras quentes em novas IN.
 *
 * O processador é o elo causal entre partições: funde L/V dos LOTEs observados,
 * tira um snapshot e publica ALARM- no tópico da partição do sensor sintético.
 */
public final class AlarmProcessor {
    private AlarmProcessor() {
    }

    /** Cria um processador com a configuração indicada e inicia as subscrições. */
    public static void main(String[] args) throws Exception {
        // Load the shared runtime configuration, allowing command-line arguments
        // to override the number of partitions and the IPFS API endpoint.
        Properties config = RuntimeConfig.load();
        int n = args.length > 0 ? Integer.parseInt(args[0])
                : Integer.parseInt(RuntimeConfig.value(config, "partitions", "3"));
        String api = args.length > 1 ? args[1]
                : RuntimeConfig.value(config, "leader.api", "http://127.0.0.1:5002/api/v0");
        Partitioner partitioner = new Partitioner(n);
        Processor processor = new Processor(n, api, partitioner);
        processor.start();
    }

    private static final class Processor {
        // Number of partitions monitored by this processor.
        private final int n;
        // Kubo/IPFS API endpoint used for subscriptions and alarm publication.
        private final String api;
        // Deterministic mapping from a sensor name to its destination partition.
        private final Partitioner partitioner;
        // Monitor protecting the shared causal state and duplicate set.
        private final Object lock = new Object();
        // Highest Lamport timestamp observed in any accepted data record.
        private long lp;
        // Component-wise maximum of the vector clocks observed so far.
        private final VectorClock vp;
        // Keys in the form "<partition>-<sequence>" for already processed records.
        private final Set<String> seen;
        private final HeartbeatMonitor[] monitors;
        // A single publisher preserves the order in which alarms leave the
        // synchronized causal-state update, without blocking subscription threads.
        private final ExecutorService alarmPublisher = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "processador-alarmes");
            thread.setDaemon(true);
            return thread;
        });

        /**
         * Creates the shared state used by all partition subscription threads.
         *
         * @param n number of partitions to subscribe to
         * @param api Kubo/IPFS API endpoint
         * @param partitioner sensor-to-partition mapping
         */
        Processor(int n, String api, Partitioner partitioner) {
            this.n = n;
            this.api = api;
            this.partitioner = partitioner;
            this.lp = 0;
            this.vp = new VectorClock(n);
            this.seen = new HashSet<>();
            this.monitors = new HeartbeatMonitor[n];
            for (int k = 0; k < n; k++) {
                final int partition = k;
                monitors[k] = new HeartbeatMonitor(k, Wire.PERIODO_HB_MS, Wire.FATOR_TIMEOUT,
                        event -> monitorLog(event));
            }
        }

        void start() throws InterruptedException {
            // Each partition has an independent subscription thread because
            // subscribing to a topic blocks while waiting for new messages.
            Thread[] threads = new Thread[n];
            for (int k = 0; k < n; k++) {
                final int partition = k;
                threads[k] = new Thread(() -> subscribe(partition), "processador-p" + k);
                threads[k].start();
            }
            ScheduledExecutorService checks = Executors.newSingleThreadScheduledExecutor();
            checks.scheduleAtFixedRate(() -> {
                for (HeartbeatMonitor monitor : monitors) monitor.check();
            }, Wire.VERIFICACAO_MS, Wire.VERIFICACAO_MS, TimeUnit.MILLISECONDS);
            for (Thread thread : threads) {
                thread.join();
            }
        }

        private void subscribe(int k) {
            // Subscribe to the data topic of one partition and process each batch.
            try {
                new IpfsPubSub(api).subscribe(Wire.topicData(k), msg -> process(k, msg));
            } catch (Exception e) {
                System.out.println("Subscricao do processador p" + k + " terminada: " + e.getMessage());
            }
        }

        /** Valida um LOTE, elimina duplicados e agenda eventual alarme. */
        private void process(int k, String msg) {
            try {
                Batch batch = Batch.decode(msg, n);
                monitors[k].onHeartbeat(batch.inc, batch.hb);
                long firstSeq = batch.records.isEmpty() ? -1 : batch.records.get(0).getSeq();
                long lastBatchSeq = batch.records.isEmpty() ? -1
                        : batch.records.get(batch.records.size() - 1).getSeq();
                monitorLog("RECEBIDO;p" + k + ";inc=" + batch.inc + ";hb=" + batch.hb
                        + ";ultimoSeq=" + batch.lastSeq + ";n=" + batch.records.size()
                        + ";de=" + firstSeq + ";ate=" + lastBatchSeq);
                for (SensorRecord record : batch.records) {
                    if (record.getSensorId().startsWith("ALARM-")) continue;
                    String alarm = null;
                    int target = -1;
                    synchronized (lock) {
                    // A PubSub message may be delivered more than once. The
                    // partition is part of the key because sequence numbers are
                    // local to each partition.
                        if (!seen.add(k + "-" + record.getSeq())) continue;

                    // Keep the maximum causal context observed across all
                    // partitions. This state is shared by the subscription
                    // threads and must only be read or changed while locked.
                    lp = Math.max(lp, record.lamport);
                    vp.merge(record.vector);
                        if (record.getTemperature() >= Wire.LIMIAR_ALARME) {
                        // The alarm is a new input event for the partition that
                        // owns the synthetic ALARM- sensor.
                        String alarmSensor = "ALARM-" + record.getSensorId();
                        target = partitioner.of(alarmSensor);
                        // Copy the vector before leaving the critical section:
                        // later records may mutate the processor's shared vector.
                        alarm = Wire.in(alarmSensor, record.getTemperature(), lp, vp.copy());
                        final String finalAlarm = alarm;
                        final int finalTarget = target;
                        // Queue while holding the state lock so publication
                        // follows the order in which causal snapshots are made.
                            alarmPublisher.execute(() -> {
                            try {
                                if (monitors[finalTarget].state() == HeartbeatMonitor.State.SUSPEITO) {
                                    System.out.println("AVISO: líder p" + finalTarget
                                            + " suspeito; alarme " + record.getSensorId() + " pode perder-se");
                                }
                                new IpfsPubSub(api).publish(Wire.topicIn(finalTarget), finalAlarm);
                                System.out.println("ALARME: " + record.getSensorId() + " p" + k
                                        + "#" + record.getSeq() + " -> p" + finalTarget);
                            } catch (Exception e) {
                                System.out.println("Erro ao publicar o alarme: " + e.getMessage());
                            }
                            });
                        }
                    }
                }
            } catch (Exception e) {
                System.out.println("REJEITADO pelo processador: " + msg.trim() + " (" + e.getMessage() + ")");
            }
        }

        private static synchronized void monitorLog(String event) {
            try {
                Files.writeString(Paths.get("monitor-processador.txt"),
                        System.currentTimeMillis() + ";" + event + System.lineSeparator(),
                        StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
            } catch (Exception e) {
                System.out.println("Falha no monitor-processador: " + e.getMessage());
            }
            System.out.println("MONITOR: " + event);
        }

    }
}
