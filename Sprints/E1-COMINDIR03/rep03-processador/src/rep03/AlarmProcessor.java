package rep03;

import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

public final class AlarmProcessor {
    private AlarmProcessor() {
    }

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
            for (Thread thread : threads) {
                thread.join();
            }
        }

        private void subscribe(int k) {
            // Subscribe to the data topic of one partition and process each
            // REG message received from its leader.
            try {
                new IpfsPubSub(api).subscribe(Wire.topicData(k), msg -> process(k, msg));
            } catch (Exception e) {
                System.out.println("Subscricao do processador p" + k + " terminada: " + e.getMessage());
            }
        }

        private void process(int k, String msg) {
            try {
                // REG contains the leader metadata followed by the serialized
                // SensorRecord carrying the Lamport and vector timestamps.
                String[] parts = Wire.regParts(msg);
                SensorRecord record = SensorRecord.fromLine(parts[2], n);
                // Alarm records are terminal events for this processor. Ignoring
                // them prevents an alarm from generating another alarm.
                if (record.getSensorId().startsWith("ALARM-")) {
                    return;
                }
                String alarm = null;
                int target = -1;
                synchronized (lock) {
                    // A PubSub message may be delivered more than once. The
                    // partition is part of the key because sequence numbers are
                    // local to each partition.
                    if (!seen.add(k + "-" + record.getSeq())) {
                        return;
                    }

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
                    }
                }
                if (alarm != null) {
                    final String finalAlarm = alarm;
                    final int finalTarget = target;
                    // Publishing uses a synchronous HTTP request, so do it in a
                    // separate thread and keep the subscription callback responsive.
                    new Thread(() -> {
                        try {
                            new IpfsPubSub(api).publish(Wire.topicIn(finalTarget), finalAlarm);
                            System.out.println("ALARME: " + record.getSensorId() + " p" + k
                                    + "#" + record.getSeq() + " -> p" + finalTarget);
                        } catch (Exception e) {
                            System.out.println("Erro ao publicar o alarme: " + e.getMessage());
                        }
                    }).start();
                }
            } catch (Exception e) {
                System.out.println("REJEITADO pelo processador: " + msg.trim() + " (" + e.getMessage() + ")");
            }
        }

    }
}
