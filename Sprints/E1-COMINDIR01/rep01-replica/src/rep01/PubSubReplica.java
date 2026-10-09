package rep01;

import java.io.IOException;
import java.util.TreeMap;

public class PubSubReplica {
    static final String TOPIC = "rep01-temperaturas";

    public static void main(String[] args) {
        String id = args.length > 0 ? args[0] : "1";
        String port = args.length > 1 ? args[1] : "5001";
        String api = "http://127.0.0.1:" + port + "/api/v0";

        RecordFile file = new RecordFile("replica-" + id + ".txt");
        TreeMap<Long, SensorRecord> pending = new TreeMap<>();
        IpfsPubSub pubsub = new IpfsPubSub(api);

        long initialSeq = 0;
        try {
            initialSeq = file.lastSeq();
        } catch (IOException e) {
            System.out.println("Erro ao ler ficheiro: " + e.getMessage());
        }

        long[] lastSeq = new long[]{ initialSeq };
        long[] totalRecebidos = new long[]{ 0 };
        long[] totalEscritos = new long[]{ 0 };
        long[] tPrimeiraRececao = new long[]{ 0 };
        long[] tUltimaRececao = new long[]{ 0 };

        System.out.println("Réplica PubSub " + id + " ligada a " + api + " (último seq em ficheiro: " + lastSeq[0] + ")");

        try {
            // Subscreve o tópico; bloqueia a escuta de mensagens
            pubsub.subscribe(TOPIC, line -> {
                long agora = System.currentTimeMillis();
                if (totalRecebidos[0] == 0) {
                    tPrimeiraRececao[0] = agora;
                }
                tUltimaRececao[0] = agora;
                totalRecebidos[0]++;

                SensorRecord r;
                try {
                    r = SensorRecord.fromLine(line);
                } catch (IllegalArgumentException e) {
                    System.out.println("REJEITADO: " + line.trim());
                    return;
                }

                long seq = r.getSeq();

                try {
                    if (seq <= lastSeq[0] || pending.containsKey(seq)) {
                        System.out.println("DUPLICADO: " + seq);
                    } else if (seq == lastSeq[0] + 1) {
                        file.append(r);
                        lastSeq[0] = seq;
                        totalEscritos[0]++;
                        System.out.println("Aplicado: " + r.toLine());

                        while (pending.containsKey(lastSeq[0] + 1)) {
                            SensorRecord next = pending.remove(lastSeq[0] + 1);
                            file.append(next);
                            lastSeq[0] = next.getSeq();
                            totalEscritos[0]++;
                            System.out.println("Aplicado (que estava retido): " + next.toLine());
                        }
                    } else {
                        pending.put(seq, r);
                        System.out.println("EM ESPERA: " + seq + " (falta o registo " + (lastSeq[0] + 1) + ")");
                    }
                } catch (IOException e) {
                    System.out.println("Erro ao escrever no ficheiro: " + e.getMessage());
                }

                System.out.println("--> [Stats R" + id + "] Rec: " + totalRecebidos[0] 
                        + " | Escritos: " + totalEscritos[0] 
                        + " | Retidos: " + pending.size() 
                        + " | T_1º: " + tPrimeiraRececao[0] + " ms | T_Último: " + tUltimaRececao[0] + " ms");
            });
        } catch (Exception e) {
            System.out.println("Erro na réplica PubSub: " + e.getMessage());
        }
    }
}