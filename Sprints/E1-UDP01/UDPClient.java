import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Scanner;

public class UDPClient {
    public static void main(String[] args) {
        DatagramSocket aSocket = null;
        Scanner input = new Scanner(System.in);

        try {
            aSocket = new DatagramSocket();
            aSocket.setSoTimeout(3000);

            InetAddress aHost = InetAddress.getByName("localhost");
            int serverPort = 6789;

            System.out.println("Escolha o modo de numeração: ");
            System.out.println("1 - Automático");
            System.out.println("2 - Manual");
            System.out.println("Opção: ");
            int modo = Integer.parseInt(input.nextLine().trim());

            int autoSeq = 1;

            while (true) {
                System.out.println("Mensagem (ou 'sair'): ");
                String msg = input.nextLine();

                if (msg.equalsIgnoreCase("sair")) {
                    break;
                }

                int seqNum;
                if (modo == 2) {
                    System.out.println("Indique o número de sequência (N): ");
                    seqNum = Integer.parseInt(input.nextLine().trim());
                } else {
                    seqNum = autoSeq++;
                }

                String payload = seqNum + ',' + msg;
                byte[] sendBuffer = payload.getBytes();

                DatagramPacket request = new DatagramPacket(
                        sendBuffer,
                        sendBuffer.length,
                        aHost,
                        serverPort);
                aSocket.send(request);

                // Receção

                byte[] recvBuffer = new byte[1000];
                DatagramPacket reply = new DatagramPacket(recvBuffer, recvBuffer.length);

                try {
                    aSocket.receive(reply);

                    String resposta = new String(reply.getData(), 0, reply.getLength());

                    if (resposta.startsWith("waitingfor,")) {
                        String[] partes = resposta.split(",");
                        String esperado = partes.length > 1 ? partes[1] : "?";
                        System.err.println("[AVISO] Mensagem fora de ordem! Servidor à espera do ID: " + esperado);
                    } else {
                        System.out.println("[ECHO OK] " + resposta);
                    }
                } catch (SocketTimeoutException e) {
                    System.err.println("[ERRO] Timeout: Nenhuma resposta recebida do servidor.");
                }
            }
        } catch (SocketException e) {
            System.err.println("Socket: " + e.getMessage());
        } catch (IOException e) {
            System.err.println("IO: " + e.getMessage());
        } finally {
            if (aSocket != null) {
                aSocket.close();
            }
            input.close();
        }
    }
}