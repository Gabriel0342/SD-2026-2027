package rep01;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.TreeMap;

public class MulticastReplica {
    static final String GROUP = "230.0.0.1";
    static final int PORT = 6789;

    public static void main(String[] args) {
        String id = args.length > 0 ? args[0] : "1";
        RecordFile file = new RecordFile("replica-" + id + ".txt");
        TreeMap<Long, SensorRecord> pending = new TreeMap<>();

        long totalRecebidos = 0;
        long totalEscritos = 0;
        long tPrimeiraRececao = 0;
        long tUltimaRececao = 0;

        try (MulticastSocket socket = new MulticastSocket(PORT)) {
            InetAddress group = InetAddress.getByName(GROUP);
            socket.joinGroup(group);

            long lastSeq = file.lastSeq();
            System.out.println("Réplica " + id + " à escuta em " + GROUP + ":" + PORT + " (último seq em ficheiro: " + lastSeq + ")");

            byte[] buffer = new byte[1000];
            while (true) {
                DatagramPacket p = new DatagramPacket(buffer, buffer.length);
                socket.receive(p);

                long agora = System.currentTimeMillis();
                if (totalRecebidos == 0) {
                    tPrimeiraRececao = agora;
                }
                tUltimaRececao = agora;
                totalRecebidos++;

                String line = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                
                SensorRecord r;
                try {
                    r = SensorRecord.fromLine(line);
                } catch (IllegalArgumentException e) {
                    System.out.println("REJEITADO: " + line.trim());
                    continue;
                }

                long seq = r.getSeq();

                if (seq <= lastSeq || pending.containsKey(seq)) {
                    System.out.println("DUPLICADO: " + seq);
                } else if (seq == lastSeq + 1) {
                    file.append(r);
                    lastSeq = seq;
                    totalEscritos++;

                    while (pending.containsKey(lastSeq + 1)) {
                        SensorRecord next = pending.remove(lastSeq + 1);
                        file.append(next);
                        lastSeq = next.getSeq();
                        totalEscritos++;
                    }
                } else {
                    pending.put(seq, r);
                }

                // Imprime resumo das estatísticas em tempo real
                System.out.println("--> [Stats R" + id + "] Rec: " + totalRecebidos + " | Escritos: " + totalEscritos 
                        + " | Retidos: " + pending.size() 
                        + " | T_1º: " + tPrimeiraRececao + " ms | T_Último: " + tUltimaRececao + " ms");
            }
        } catch (IOException e) {
            System.out.println("IO: " + e.getMessage());
        }
    }
}