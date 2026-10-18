package rep02;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class PartitionLeader {

    private static final Object LOCK = new Object();

    public static void main(String[] args) throws Exception {

        int n = args.length > 1 ? Integer.parseInt(args[1]) : 3; // PartitionLeader <k> <N>; sem N: tr???s parti??????es (Parte B)
        Partitioner partitioner = new Partitioner(n);
        String endereco = args.length > 2 ? args[2] : "127.0.0.1";
        String api = args.length > 3 ? args[3] : "http://127.0.0.1:5001/api/v0";

        ServerSocket server;
        final int k;
        if (args.length > 0) {
            k = Integer.parseInt(args[0]);
            server = new ServerSocket(Wire.PORTO_BASE + k); //permite ter 3 parti??????es lideres em 3 portas dispares
        } else {
            int livre = -1;
            ServerSocket s = null;
            for (int i = 0; i < n && s == null; i++) {
                try {
                    s = new ServerSocket(Wire.PORTO_BASE + i); // porto livre: esta parti??????o n???o tem l???der
                    livre = i;
                } catch (IOException e) { // porto ocupado: j??? h??? l???der desta parti??????o
                    // tenta a parti??????o seguinte
                }
            }
            if (s == null) {
                System.out.println("J??? h??? l???der nas " + n + " parti??????es (portos "
                        + Wire.PORTO_BASE + ".." + (Wire.PORTO_BASE + n - 1) + ").");
                return;
            }
            server = s;
            k = livre;
            System.out.println("Parti??????o atribu???da automaticamente: p" + k);
        }
        final int porto = Wire.PORTO_BASE + k; // porto de recupera??????o da parti??????o k: 7000 + k

        IpfsPubSub ps = new IpfsPubSub(api);
        RecordFile file = new RecordFile("lider-p" + k + ".txt");

        AtomicLong seq = new AtomicLong(file.lastSeq());


        // ENTRADA (PubSub, thread secund???ria)
        Thread entrada = new Thread(() -> {
            try {
                ps.subscribe(Wire.topicIn(k), msg -> { // ps.subscribe n???o termina fica bloqueado ??? espera de cada mensagem do t???pico
                    try {
                        String[] f = msg.trim().split(";");
                        if (f.length != 3 || !f[0].equals("IN") || f[1].isBlank()) {
                            throw new IllegalArgumentException("IN mal formado");
                        }
                        int pj = partitioner.of(f[1]);
                        if (pj != k) { // leitura de outra parti??????o: n???o ??? registada
                            System.out.println("PARTI???fO ERRADA: " + f[1] + " pertence a p" + pj);
                            return;
                        }
                        double temp = Double.parseDouble(f[2]);

                        SensorRecord r;
                        synchronized (LOCK) {
                            r = SensorRecord.now(seq.incrementAndGet(), f[1], temp);
                            file.append(r);
                        }
                        ps.publish(Wire.topicData(k), Wire.reg(endereco, porto, r));
                    } catch (Exception e) {
                        System.out.println("REJEITADO: " + msg);
                    }
                });
            } catch (Exception e) {
                System.out.println("Entrada terminada: " + e.getMessage());
            }
        });
        entrada.setDaemon(true); // se a main falhar, o processo morre por completo
        entrada.start();

        // RECUPERA???fO (TCP, thread principal)
        System.out.println("L???der da parti??????o " + k + " pronto (recupera??????o em " + endereco + ":" + porto + ")");

        try (server) { // ServerSocket j??? aberto (com o k dado ou atribu???do automaticamente)
            while (true) {
                try (Socket s = server.accept(); // bloqueia at??? uma r???plica se ligar
                     BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                     PrintWriter out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true)) {
                    s.setSoTimeout(Wire.T_TCP_MS); // uma r???plica que abra a liga??????o e

                    String pedido = in.readLine(); // bloqueia at??? chegar o pedido
                    long de = 0, ate = 0;

                    try {
                        if (pedido == null) { // EOF: a r???plica desligou sem enviar nada
                            throw new IllegalArgumentException("sem pedido");
                        }
                        String[] f = pedido.trim().split("\\s+");
                        if (f.length != 3 || !f[0].equals("PEDIDO")) {
                            throw new IllegalArgumentException("pedido mal formado: " + pedido);
                        }
                        try {
                            de = Long.parseLong(f[1]);
                            ate = Long.parseLong(f[2]);
                        } catch (NumberFormatException e) { // parseLong falhou
                            throw new IllegalArgumentException("n???meros inv???lidos: " + pedido);
                        }
                        if (de > ate) { // intervalo vazio ??? incoerente, n???o "vazio"
                            throw new IllegalArgumentException("intervalo vazio: " + de + " > " + ate);
                        }
                    } catch (IllegalArgumentException e) { // cobre NumberFormatException
                        out.println("ERRO " + e.getMessage());
                        continue; // fecha esta liga??????o (try-with-resources) e volta ao accept
                    }

                    long ultimo;
                    synchronized (LOCK) {
                        ultimo = seq.get();
                    }
                    long limite = Math.min(ate, ultimo);
                    List<SensorRecord> registos = file.range(de, limite); // pode ser uma lista vazia

                    for (SensorRecord r : registos) {
                        out.println(r.toLine());
                    }
                    out.println("FIM");
                    System.out.println("RECUPERA???fO: " + de + ".." + limite
                            + " para " + s.getRemoteSocketAddress() + " (" + registos.size() + " registos)");
                } catch (IOException e) { // timeout, reset de liga??????o, r???plica que desligou a meio...
                    System.out.println("Recupera??????o interrompida: " + e.getMessage());
                }
            }
        }
    }
}


