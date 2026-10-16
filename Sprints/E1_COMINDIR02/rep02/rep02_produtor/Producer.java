package E1_COMINDIR02.rep02.rep02_produtor;

import java.util.Random;
import java.util.Scanner;

public class Producer {
    static final String API = "http://127.0.0.1:5001/api/v0"; // API por omissão

    public static void main(String[] args) {
        // número de partições e endereço da API: Producer <N> [api];
        // sem argumentos: N = 3 (Parte B) e a API por omissão
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 3;
        String api = args.length > 1 ? args[1] : API;
        Partitioner partitioner = new Partitioner(n);
        IpfsPubSub pubsub = new IpfsPubSub(api);
        Random rand = new Random();              // sensores e temperaturas aleatórias
        int[] contadores = new int[n];           // leituras publicadas por partição

        System.out.println("Produtor pronto (API: " + api + ", " + n + " partições).");
        System.out.println("Formato: <sensor> <temperatura> | 'rajada <n> <s>' | 'rajada-quente <n> <s>' | 'sair'");

        try (Scanner sc = new Scanner(System.in)) {
            // ciclo de leitura de comandos: bloqueia à espera de uma linha
            while (true) {
                System.out.print("> ");
                if (!sc.hasNextLine()) {
                    break;
                }
                String line = sc.nextLine().trim();
                if (line.equalsIgnoreCase("sair")) {
                    break;
                }
                if (line.isEmpty()) {
                    continue;         // ignora linhas vazias
                }

                // rajada <n> <s>: publica n leituras, o mais depressa possível, com sensores aleatórios entre S01 e S<s>
                // rajada-quente <n> <s>: igual, mas 80 % das leituras vêm de S01 (chave quente)
                boolean quente = line.toLowerCase().startsWith("rajada-quente ");
                if (quente || line.toLowerCase().startsWith("rajada ")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length != 3) {
                        System.out.println("Uso: rajada <n> <s> | rajada-quente <n> <s>");
                        continue;
                    }
                    try {
                        int nLeituras = Integer.parseInt(parts[1]); // número de leituras
                        int s = Integer.parseInt(parts[2]); // sensores de S01 a S<s>
                        if (nLeituras < 1 || s < 1) {
                            System.out.println("Valores inválidos.");
                            continue;
                        }

                        long tInicio = System.currentTimeMillis(); // instante da 1ª publicação
                        long tFim = tInicio;
                        for (int i = 0; i < nLeituras; i++) {
                            String sensor = quente
                                    ? sensorQuente(rand, s)
                                    : String.format("S%02d", rand.nextInt(s) + 1); // sensor aleatório
                            double temp = Math.round((15.0 + 15.0 * rand.nextDouble()) * 10.0) / 10.0; // temperatura aleatória entre 15 e 30
                            publicar(pubsub, partitioner, contadores, sensor, temp);
                            tFim = System.currentTimeMillis(); // instante da última publicação
                        }
                        System.out.println("Rajada concluída: " + nLeituras + " leituras.");
                        for (int k = 0; k < n; k++) {
                            System.out.println("p" + k + ": " + contadores[k] + " leituras");
                        }
                        System.out.println("Primeira: " + tInicio + " ms | Última: " + tFim + " ms | Duração: " + (tFim - tInicio) + " ms");
                    } catch (NumberFormatException e) {
                        System.out.println("rajada: n e s têm de ser inteiros.");
                    }
                    continue;
                }

                // publica uma leitura
                String[] p = line.split("\\s+");
                if (p.length != 2) {
                    System.out.println("Formato inválido.");
                    continue;
                }
                double temp;
                try {
                    temp = Double.parseDouble(p[1]); // converte o texto em número
                } catch (NumberFormatException e) {
                    System.out.println("Temperatura inválida: " + p[1]);
                    continue;
                }
                publicar(pubsub, partitioner, contadores, p[0], temp); // publica IN no tópico de entrada da partição do sensor
            }
        } catch (Exception e) {
            System.out.println("Erro: " + e.getMessage());
        }
    }

    // publica a leitura no tópico de entrada da partição do sensor e assinala o destino
    static void publicar(IpfsPubSub ps, Partitioner pt, int[] contadores, String sensor, double temp) throws Exception {
        int k = pt.of(sensor);
        ps.publish(Wire.topicIn(k), Wire.in(sensor, temp));
        contadores[k]++;
        System.out.println("Publicado: " + Wire.in(sensor, temp) + " → p" + k);
    }

    // sensor de uma leitura de rajada-quente: 80 % vêm de S01, o resto de S02 a S<s>
    static String sensorQuente(Random rand, int s) {
        if (s >= 2 && rand.nextDouble() >= 0.8) {
            return String.format("S%02d", rand.nextInt(s - 1) + 2);
        }
        return "S01";
    }
}
