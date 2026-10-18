package rep02;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

public class PartitionReplica {

    // propriedades de sistema da simula??????o de perdas (-Dperda, -Ddescartar),
    // aplicadas exclusivamente ???s mensagens recebidas pelo PubSub
    private static double perda;
    private static long descartar;
    private static final AtomicBoolean JA_DESCARTADO = new AtomicBoolean(false);

    public static void main(String[] args) throws Exception {
        try {
            perda = Double.parseDouble(System.getProperty("perda", "0"));
            descartar = Long.parseLong(System.getProperty("descartar", "-1"));
        } catch (NumberFormatException e) {
            System.out.println("Propriedade de simula??????o inv???lida: " + e.getMessage());
            return;
        }

        String api = "http://127.0.0.1:" + (args.length > 2 ? args[2] : "5001") + "/api/v0";

        String id;
        String partes;
        int[] ks;
        if (args.length == 0) {
            // sem argumentos: id e parti??????es por atribui??????o autom???tica ??" a r???plica j guarda
            // as parti??????es j e j+1 (m???dulo 3); tr???s arranques d???o (0,1), (1,2) e (2,0),
            // com cada parti??????o em exatamente duas r???plicas (R = 2, Parte B)
            Atribuicao a = autoAtribuicao();
            id = a.id();
            ks = a.ks();
            partes = a.partes();
            System.out.println("Atribui??????o autom???tica: r???plica " + id + " com as parti??????es " + partes);
        } else {
            id = args[0];
            partes = args.length > 1 ? args[1] : "0"; // sem lista: parti??????o 0
            try {
                String[] campos = partes.split(",");
                ks = new int[campos.length];
                for (int i = 0; i < campos.length; i++) {
                    ks[i] = Integer.parseInt(campos[i].trim());
                }
            } catch (NumberFormatException e) {
                System.out.println("Uso: PartitionReplica [id] [parti??????es] [portoApi]");
                System.out.println("Parti??????es mal formadas: " + partes);
                return;
            }
        }

        System.out.println("R???plica " + id + " ligada a " + api + " com as parti??????es " + partes
                + " (perda=" + perda + ", descartar=" + (descartar < 0 ? "nenhum" : descartar) + ")");

        // uma thread por parti??????o: a subscri??????o bloqueia e n???o termina
        Thread[] threads = new Thread[ks.length];
        for (int i = 0; i < ks.length; i++) {
            Particao p = new Particao(api, id, ks[i]);
            threads[i] = new Thread(p, "particao-" + ks[i]);
            threads[i].start();
        }
        for (Thread t : threads) { // a thread principal aguarda a conclus???o das subscri??????es
            t.join();
        }
    }

    // canais das trancas de id autom???tico: mant???m-se abertos at??? o processo terminar
    private static final List<FileChannel> TRANCAS = new ArrayList<>();

    // resultado da atribui??????o autom???tica: id da r???plica e as parti??????es que lhe ficam
    private record Atribuicao(String id, int[] ks, String partes) { }

    // atribui??????o autom???tica de id e parti??????es: a r???plica j guarda as parti??????es j e
    // j+1, m???dulo 3; o primeiro id (0, 1, 2, ...) cujos ficheiros replica-<id>-p<k>.lock
    // n???o estejam trancados por outra r???plica ainda viva; a tranca ??? libertada pelo SO
    // quando o processo morre
    static Atribuicao autoAtribuicao() throws IOException {
        for (int n = 0; n < 100; n++) { // limite de seguran???a: 100 r???plicas vivas chega
            int[] ks = { n % 3, (n + 1) % 3 }; // tr???s parti??????es, fator de replica??????o 2
            List<FileChannel> canais = new ArrayList<>();
            try {
                for (int k : ks) {
                    FileChannel ch = FileChannel.open(Paths.get("replica-" + n + "-p" + k + ".lock"),
                            StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    if (ch.tryLock() == null) { // outra r???plica viva tem este id
                        ch.close();
                        throw new IllegalStateException("id ocupado");
                    }
                    canais.add(ch);
                }
                TRANCAS.addAll(canais); // s??? aqui ??? que o id fica tomado
                return new Atribuicao(Integer.toString(n), ks, ks[0] + "," + ks[1]);
            } catch (IOException | IllegalStateException e) {
                for (FileChannel ch : canais) { // liberta as trancas obtidas e tenta o id seguinte
                    ch.close();
                }
            }
        }
        throw new IOException("sem ids livres (0..99)");
    }

    // estado e l???gica de uma parti??????o subscrita (uma inst???ncia por parti??????o)
    private static class Particao implements Runnable {
        private final String api;
        private final int k;
        private final RecordFile file;
        private long ultimo;                                       // ???ltimo seq escrito
        private final TreeMap<Long, SensorRecord> retidos = new TreeMap<>();
        private String hostLider;                                  // atualizados a cada REG recebida
        private int portoLider;

        Particao(String api, String id, int k) throws IOException {
            this.api = api;
            this.k = k;
            this.file = new RecordFile("replica-" + id + "-p" + k + ".txt");
            this.ultimo = file.lastSeq();
        }

        @Override
        public void run() {
            System.out.println("Parti??????o " + k + ": ???ltimo seq em ficheiro: " + ultimo);
            try {
                new IpfsPubSub(api).subscribe(Wire.topicData(k), this::onReg);
            } catch (Exception e) { // daemon indispon???vel, interrup??????o...
                System.out.println("Subscri??????o da parti??????o " + k + " terminada: " + e.getMessage());
            }
        }

        // rece??????o de uma mensagem REG pelo PubSub
        private void onReg(String msg) {
            try {
                String[] f = Wire.regParts(msg); // { endere???o, porto, linha do registo }
                SensorRecord r = SensorRecord.fromLine(f[2]);

                if (r.getSeq() == descartar && JA_DESCARTADO.compareAndSet(false, true)) {
                    System.out.println("DESCARTADO (simula??????o): " + r.getSeq()); // apenas a primeira rece??????o
                    return;
                }
                if (ThreadLocalRandom.current().nextDouble() < perda) {
                    System.out.println("DESCARTADO (simula??????o): " + r.getSeq());
                    return;
                }

                hostLider = f[0];
                portoLider = Integer.parseInt(f[1]);
                aplicar(r, false);
                if (retidos.size() > Wire.LIMITE_RETIDOS) { // a condi??????o persiste se a recupera??????o falhar
                    recuperar(); // na pr???pria thread de subscri??????o
                }
            } catch (IllegalArgumentException e) { // REG, registo ou porto mal formado
                System.out.println("REJEITADO: " + msg.trim());
            } catch (IOException e) {
                System.out.println("Erro ao escrever no ficheiro: " + e.getMessage());
            }
        }

        // regra de aplica??????o da COMINDIR01, comum ??? rece??????o por PubSub e ??? recupera??????o
        private void aplicar(SensorRecord r, boolean recuperado) throws IOException {
            long seq = r.getSeq();
            if (seq <= ultimo || retidos.containsKey(seq)) { // j??? escrito ou retido
                System.out.println("DUPLICADO: " + seq);
            } else if (seq == ultimo + 1) {
                file.append(r);
                ultimo = seq;
                System.out.println(recuperado ? "RECUPERADO: " + seq : "Aplicado: " + r.toLine());
                escreverEmSequencia();
            } else { // registo antecipado: fica retido em mem???ria
                retidos.put(seq, r);
                System.out.println("EM ESPERA: " + seq + " (falta o registo " + (ultimo + 1) + ")");
            }
        }

        // escreve os retidos que passaram a estar em sequ???ncia
        private void escreverEmSequencia() throws IOException {
            while (!retidos.isEmpty() && retidos.firstKey() == ultimo + 1) {
                SensorRecord r = retidos.remove(retidos.firstKey());
                file.append(r);
                ultimo = r.getSeq();
                System.out.println("Aplicado (que estava retido): " + r.toLine());
            }
        }

        // liga??????o TCP ao l???der: PEDIDO <de> <at???> e leitura dos registos at??? FIM
        private void recuperar() {
            long de = ultimo + 1;
            long ate = retidos.isEmpty() ? de - 1 : retidos.firstKey() - 1;
            if (de > ate || hostLider == null) { // nada a pedir ou l???der ainda desconhecido
                return;
            }
            System.out.println("RECUPERA???fO: " + de + ".." + ate + " pedida a " + hostLider + ":" + portoLider);
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(hostLider, portoLider), Wire.T_TCP_MS); // bloqueia at??? a liga??????o ser aceite
                s.setSoTimeout(Wire.T_TCP_MS); // readLine deixa de bloquear indefinidamente
                PrintWriter out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true);
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));

                out.println("PEDIDO " + de + " " + ate);

                String linha;
                while ((linha = in.readLine()) != null && !linha.equals("FIM")) {
                    if (linha.startsWith("ERRO")) { // pedido recusado pelo l???der
                        throw new IOException(linha.substring("ERRO".length()).trim());
                    }
                    aplicar(SensorRecord.fromLine(linha), true);
                }
                if (linha == null) { // a liga??????o fechou antes de FIM
                    throw new IOException("liga??????o fechada antes de FIM");
                }
                escreverEmSequencia();
            } catch (IOException | RuntimeException e) { // recusa, tempo esgotado, registo mal formado...
                System.out.println("RECUPERA???fO FALHOU: " + e.getMessage());
            }
        }
    }
}


