package rep01;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.Scanner;

public class PubSubLeader {
    static final String DEFAULT_API = "http://127.0.0.1:5001/api/v0";
    static final String TOPIC = "rep01-temperaturas";

    public static void main(String[] args) {
        String api = args.length > 0 ? args[0] : DEFAULT_API;
        IpfsPubSub pubsub = new IpfsPubSub(api);

        try (Scanner sc = new Scanner(System.in)) {
            RecordFile file = new RecordFile("lider.txt");
            long seq = file.lastSeq();
            Random rand = new Random();

            System.out.println("Líder PubSub pronto (API: " + api + ").");
            System.out.println("Formato: <sensor> <temperatura> | 'rajada <n>' | 'test-order' | 'sair'");

            while (true) {
                System.out.print("> ");
                if (!sc.hasNextLine()) break;
                String line = sc.nextLine().trim();
                if (line.equalsIgnoreCase("sair")) break;

                if (line.toLowerCase().startsWith("rajada ")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length == 2) {
                        try {
                            int n = Integer.parseInt(parts[1]);
                            long tInicio = System.currentTimeMillis();
                            long tFim = tInicio;

                            for (int i = 0; i < n; i++) {
                                double temp = 15.0 + (30.0 - 15.0) * rand.nextDouble();
                                temp = Math.round(temp * 10.0) / 10.0;
                                SensorRecord r = SensorRecord.now(++seq, "SIM", temp);
                                file.append(r);
                                
                                // Publica via IPFS PubSub em vez de DatagramPacket
                                pubsub.publish(TOPIC, r.toLine());
                                tFim = System.currentTimeMillis();
                            }

                            System.out.println("Rajada concluída.");
                            System.out.println("Tempo envio 1º registo: " + tInicio + " ms");
                            System.out.println("Tempo envio último registo: " + tFim + " ms");
                            System.out.println("Duração total envio: " + (tFim - tInicio) + " ms");
                        } catch (NumberFormatException e) {
                            System.out.println("Número de registos inválido.");
                        }
                    }
                    continue;
                }

                if (line.equalsIgnoreCase("test-order")) {
                    SensorRecord r1 = SensorRecord.now(++seq, "S01", 20.0);
                    SensorRecord r2 = SensorRecord.now(++seq, "S01", 21.0);
                    SensorRecord r3 = SensorRecord.now(++seq, "S01", 22.0);

                    file.append(r1);
                    file.append(r2);
                    file.append(r3);

                    // Envia r2, depois r3, e só no fim r1
                    for (SensorRecord r : new SensorRecord[]{r2, r3, r1}) {
                        pubsub.publish(TOPIC, r.toLine());
                        System.out.println("Enviado PubSub (fora de ordem): " + r.toLine());
                    }
                    continue;
                }

                String[] p = line.split("\\s+");
                if (p.length != 2) {
                    System.out.println("Formato inválido.");
                    continue;
                }
                double temp;
                try {
                    temp = Double.parseDouble(p[1]);
                } catch (NumberFormatException e) {
                    System.out.println("Temperatura inválida: " + p[1]);
                    continue;
                }
                SensorRecord r = SensorRecord.now(++seq, p[0], temp);
                file.append(r); // 1) escreve localmente
                pubsub.publish(TOPIC, r.toLine()); // 2) publica no tópico PubSub
                System.out.println("Registado e publicado: " + r.toLine());
            }
        } catch (Exception e) {
            System.out.println("Erro: " + e.getMessage());
        }
    }
}