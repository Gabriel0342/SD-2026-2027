package rep01;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.Scanner;

public class MulticastLeader {
    static final String GROUP = "230.0.0.1";
    static final int PORT = 6789;

    public static void main(String[] args) {
        try (MulticastSocket socket = new MulticastSocket();
             Scanner sc = new Scanner(System.in)) {
            InetAddress group = InetAddress.getByName(GROUP);
            socket.setTimeToLive(1);
            RecordFile file = new RecordFile("lider.txt");
            long seq = file.lastSeq();
            Random rand = new Random();

            System.out.println("Líder pronto. Formato: <sensor> <temperatura> | 'rajada <n>' | 'test-order' | 'sair'");

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
                                temp = Math.round(temp * 10.0) / 10.0; // Arredonda a 1 casa decimal
                                SensorRecord r = SensorRecord.now(++seq, "SIM", temp);
                                file.append(r);
                                byte[] m = r.toLine().getBytes(StandardCharsets.UTF_8);
                                socket.send(new DatagramPacket(m, m.length, group, PORT));
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

                    for (SensorRecord r : new SensorRecord[]{r2, r3, r1}) {
                        byte[] m = r.toLine().getBytes(StandardCharsets.UTF_8);
                        socket.send(new DatagramPacket(m, m.length, group, PORT));
                        System.out.println("Enviado (fora de ordem): " + r.toLine());
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
                file.append(r);
                byte[] m = r.toLine().getBytes(StandardCharsets.UTF_8);
                socket.send(new DatagramPacket(m, m.length, group, PORT));
                System.out.println("Registado e enviado: " + r.toLine());
            }
        } catch (IOException e) {
            System.out.println("IO: " + e.getMessage());
        }
    }
}