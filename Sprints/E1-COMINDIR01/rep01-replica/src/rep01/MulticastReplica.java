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
            socket.joinGroup(group); // entra no grupo; quem estava desligado nao recebe mensagens antigas

            long lastSeq = file.lastSeq();
            System.out.println("Réplica " + id + " à escuta em " + GROUP + ":" + PORT + " (último seq em ficheiro: "
                    + lastSeq + ")");

            byte[] buffer = new byte[1000];
            while (true) {
                DatagramPacket p = new DatagramPacket(buffer, buffer.length);
                socket.receive(p); // bloqueia ate chegar um datagrama UDP

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
                    // O erro de formato fica isolado nesta mensagem; a replica continua a escutar.
                    System.out.println("REJEITADO: " + line.trim());
                    continue;
                }

                long seq = r.getSeq();

                if (seq <= lastSeq || pending.containsKey(seq)) {
                    // Um seq antigo ou ja retido nao pode voltar a ser escrito.
                    System.out.println("DUPLICADO: " + seq);
                } else if (seq == lastSeq + 1) {
                    file.append(r);
                    lastSeq = seq;
                    totalEscritos++;

                    // Um registo esperado pode libertar varios registos que chegaram antes dele.
                    while (pending.containsKey(lastSeq + 1)) {
                        SensorRecord next = pending.remove(lastSeq + 1);
                        file.append(next);
                        lastSeq = next.getSeq();
                        totalEscritos++;
                    }
                } else {
                    // Nao ha recuperacao: guarda ate chegar o seq que falta, sem escrever fora de ordem.
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