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

    // propriedades de sistema da simulaÃ§Ã£o de perdas (-Dperda, -Ddescartar),
    // aplicadas exclusivamente Ã s mensagens recebidas pelo PubSub
    private static double perda;
    private static long descartar;
    private static final AtomicBoolean JA_DESCARTADO = new AtomicBoolean(false);

    public static void main(String[] args) throws Exception {
        try {
            perda = Double.parseDouble(System.getProperty("perda", "0"));
            descartar = Long.parseLong(System.getProperty("descartar", "-1"));
        } catch (NumberFormatException e) {
            System.out.println("Propriedade de simulaÃ§Ã£o invÃ¡lida: " + e.getMessage());
            return;
        }

        String api = "http://127.0.0.1:" + (args.length > 2 ? args[2] : "5001") + "/api/v0";

        String id;
        String partes;
        int[] ks;
        if (args.length == 0) {
            // sem argumentos: id e partiÃ§Ãµes por atribuiÃ§Ã£o automÃ¡tica â€” a rÃ©plica j guarda
            // as partiÃ§Ãµes j e j+1 (mÃ³dulo 3); trÃªs arranques dÃ£o (0,1), (1,2) e (2,0),
            // com cada partiÃ§Ã£o em exatamente duas rÃ©plicas (R = 2, Parte B)
            Atribuicao a = autoAtribuicao();
            id = a.id();
            ks = a.ks();
            partes = a.partes();
            System.out.println("AtribuiÃ§Ã£o automÃ¡tica: rÃ©plica " + id + " com as partiÃ§Ãµes " + partes);
        } else {
            id = args[0];
            partes = args.length > 1 ? args[1] : "0"; // sem lista: partiÃ§Ã£o 0
            try {
                String[] campos = partes.split(",");
                ks = new int[campos.length];
                for (int i = 0; i < campos.length; i++) {
                    ks[i] = Integer.parseInt(campos[i].trim());
                }
            } catch (NumberFormatException e) {
                System.out.println("Uso: PartitionReplica [id] [partiÃ§Ãµes] [portoApi]");
                System.out.println("PartiÃ§Ãµes mal formadas: " + partes);
                return;
            }
        }

        System.out.println("RÃ©plica " + id + " ligada a " + api + " com as partiÃ§Ãµes " + partes
                + " (perda=" + perda + ", descartar=" + (descartar < 0 ? "nenhum" : descartar) + ")");

        // uma thread por partiÃ§Ã£o: a subscriÃ§Ã£o bloqueia e nÃ£o termina
        Thread[] threads = new Thread[ks.length];
        for (int i = 0; i < ks.length; i++) {
            Particao p = new Particao(api, id, ks[i]);
            threads[i] = new Thread(p, "particao-" + ks[i]);
            threads[i].start();
        }
        for (Thread t : threads) { // a thread principal aguarda a conclusÃ£o das subscriÃ§Ãµes
            t.join();
        }
    }

    // canais das trancas de id automÃ¡tico: mantÃªm-se abertos atÃ© o processo terminar
    private static final List<FileChannel> TRANCAS = new ArrayList<>();

    // resultado da atribuiÃ§Ã£o automÃ¡tica: id da rÃ©plica e as partiÃ§Ãµes que lhe ficam
    private record Atribuicao(String id, int[] ks, String partes) { }

    // atribuiÃ§Ã£o automÃ¡tica de id e partiÃ§Ãµes: a rÃ©plica j guarda as partiÃ§Ãµes j e
    // j+1, mÃ³dulo 3; o primeiro id (0, 1, 2, ...) cujos ficheiros replica-<id>-p<k>.lock
    // nÃ£o estejam trancados por outra rÃ©plica ainda viva; a tranca Ã© libertada pelo SO
    // quando o processo morre
    static Atribuicao autoAtribuicao() throws IOException {
        for (int n = 0; n < 100; n++) { // limite de seguranÃ§a: 100 rÃ©plicas vivas chega
            int[] ks = { n % 3, (n + 1) % 3 }; // trÃªs partiÃ§Ãµes, fator de replicaÃ§Ã£o 2
            List<FileChannel> canais = new ArrayList<>();
            try {
                for (int k : ks) {
                    FileChannel ch = FileChannel.open(Paths.get("replica-" + n + "-p" + k + ".lock"),
                            StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    if (ch.tryLock() == null) { // outra rÃ©plica viva tem este id
                        ch.close();
                        throw new IllegalStateException("id ocupado");
                    }
                    canais.add(ch);
                }
                TRANCAS.addAll(canais); // sÃ³ aqui Ã© que o id fica tomado
                return new Atribuicao(Integer.toString(n), ks, ks[0] + "," + ks[1]);
            } catch (IOException | IllegalStateException e) {
                for (FileChannel ch : canais) { // liberta as trancas obtidas e tenta o id seguinte
                    ch.close();
                }
            }
        }
        throw new IOException("sem ids livres (0..99)");
    }

    // estado e lÃ³gica de uma partiÃ§Ã£o subscrita (uma instÃ¢ncia por partiÃ§Ã£o)
    private static class Particao implements Runnable {
        private final String api;
        private final int k;
        private final RecordFile file;
        private long ultimo;                                       // Ãºltimo seq escrito
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
            System.out.println("PartiÃ§Ã£o " + k + ": Ãºltimo seq em ficheiro: " + ultimo);
            try {
                new IpfsPubSub(api).subscribe(Wire.topicData(k), this::onReg);
            } catch (Exception e) { // daemon indisponÃ­vel, interrupÃ§Ã£o...
                System.out.println("SubscriÃ§Ã£o da partiÃ§Ã£o " + k + " terminada: " + e.getMessage());
            }
        }

        // receÃ§Ã£o de uma mensagem REG pelo PubSub
        private void onReg(String msg) {
            try {
                String[] f = Wire.regParts(msg); // { endereÃ§o, porto, linha do registo }
                SensorRecord r = SensorRecord.fromLine(f[2]);

                if (r.getSeq() == descartar && JA_DESCARTADO.compareAndSet(false, true)) {
                    System.out.println("DESCARTADO (simulaÃ§Ã£o): " + r.getSeq()); // apenas a primeira receÃ§Ã£o
                    return;
                }
                if (ThreadLocalRandom.current().nextDouble() < perda) {
                    System.out.println("DESCARTADO (simulaÃ§Ã£o): " + r.getSeq());
                    return;
                }

                hostLider = f[0];
                portoLider = Integer.parseInt(f[1]);
                aplicar(r, false);
                if (retidos.size() > Wire.LIMITE_RETIDOS) { // a condiÃ§Ã£o persiste se a recuperaÃ§Ã£o falhar
                    recuperar(); // na prÃ³pria thread de subscriÃ§Ã£o
                }
            } catch (IllegalArgumentException e) { // REG, registo ou porto mal formado
                System.out.println("REJEITADO: " + msg.trim());
            } catch (IOException e) {
                System.out.println("Erro ao escrever no ficheiro: " + e.getMessage());
            }
        }

        // regra de aplicaÃ§Ã£o da COMINDIR01, comum Ã  receÃ§Ã£o por PubSub e Ã  recuperaÃ§Ã£o
        private void aplicar(SensorRecord r, boolean recuperado) throws IOException {
            long seq = r.getSeq();
            if (seq <= ultimo || retidos.containsKey(seq)) { // jÃ¡ escrito ou retido
                System.out.println("DUPLICADO: " + seq);
            } else if (seq == ultimo + 1) {
                file.append(r);
                ultimo = seq;
                System.out.println(recuperado ? "RECUPERADO: " + seq : "Aplicado: " + r.toLine());
                escreverEmSequencia();
            } else { // registo antecipado: fica retido em memÃ³ria
                retidos.put(seq, r);
                System.out.println("EM ESPERA: " + seq + " (falta o registo " + (ultimo + 1) + ")");
            }
        }

        // escreve os retidos que passaram a estar em sequÃªncia
        private void escreverEmSequencia() throws IOException {
            while (!retidos.isEmpty() && retidos.firstKey() == ultimo + 1) {
                SensorRecord r = retidos.remove(retidos.firstKey());
                file.append(r);
                ultimo = r.getSeq();
                System.out.println("Aplicado (que estava retido): " + r.toLine());
            }
        }

        // ligaÃ§Ã£o TCP ao lÃ­der: PEDIDO <de> <atÃ©> e leitura dos registos atÃ© FIM
        private void recuperar() {
            long de = ultimo + 1;
            long ate = retidos.isEmpty() ? de - 1 : retidos.firstKey() - 1;
            if (de > ate || hostLider == null) { // nada a pedir ou lÃ­der ainda desconhecido
                return;
            }
            System.out.println("RECUPERAÃ‡ÃƒO: " + de + ".." + ate + " pedida a " + hostLider + ":" + portoLider);
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(hostLider, portoLider), Wire.T_TCP_MS); // bloqueia atÃ© a ligaÃ§Ã£o ser aceite
                s.setSoTimeout(Wire.T_TCP_MS); // readLine deixa de bloquear indefinidamente
                PrintWriter out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true);
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));

                out.println("PEDIDO " + de + " " + ate);

                String linha;
                while ((linha = in.readLine()) != null && !linha.equals("FIM")) {
                    if (linha.startsWith("ERRO")) { // pedido recusado pelo lÃ­der
                        throw new IOException(linha.substring("ERRO".length()).trim());
                    }
                    aplicar(SensorRecord.fromLine(linha), true);
                }
                if (linha == null) { // a ligaÃ§Ã£o fechou antes de FIM
                    throw new IOException("ligaÃ§Ã£o fechada antes de FIM");
                }
                escreverEmSequencia();
            } catch (IOException | RuntimeException e) { // recusa, tempo esgotado, registo mal formado...
                System.out.println("RECUPERAÃ‡ÃƒO FALHOU: " + e.getMessage());
            }
        }
    }
}


