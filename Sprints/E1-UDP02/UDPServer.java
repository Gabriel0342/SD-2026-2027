import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

public class UDPServer {

    public static void main(String args[]) throws IOException {
        DatagramSocket aSocket = null;
        DatagramPacket request = null;
        DatagramPacket reply;

        try {
            aSocket = new DatagramSocket(6789);
            byte[] buffer = new byte[1000];
            int i = 0;

            while (true) {
                try {
                    i++;
                    request = new DatagramPacket(buffer, buffer.length); // Garante que a informação que chega não seja
                                                                         // maior que 1000 bytes
                    aSocket.receive(request);
                    String mensagem = new String(request.getData(), 0, request.getLength(), StandardCharsets.UTF_8); // offeset
                                                                                                                     // garante
                                                                                                                     // que
                                                                                                                     // começamos
                                                                                                                     // a
                                                                                                                     // ler
                                                                                                                     // a
                                                                                                                     // mensagem
                                                                                                                     // no
                                                                                                                     // seu
                                                                                                                     // inicio
                    String[] id = mensagem.split(",");

                    if (id.length < 2) {
                        String MensagemErro = "Mensagem inválida, verique o formato que foi introduzido.";
                        reply = new DatagramPacket(MensagemErro.getBytes(), MensagemErro.getBytes().length,
                                request.getAddress(), request.getPort());
                        aSocket.send(reply);
                        i--;
                    } else {

                        if (Integer.parseInt(id[0]) == i) {
                            System.out.println("ID : " + id[0] + " | Mensagem Recebida : " + id[1]);
                            reply = new DatagramPacket(request.getData(), request.getLength(), request.getAddress(),
                                    request.getPort());
                        } else {
                            String MensagemErro = "waitingfor," + i;
                            reply = new DatagramPacket(MensagemErro.getBytes(), MensagemErro.getBytes().length,
                                    request.getAddress(), request.getPort());
                            i--;
                        }

                        aSocket.send(reply);
                    }
                } catch (NumberFormatException e) {
                    i--;
                    String erro = "Erro: ID da mensagem não é um número válido.";
                    reply = new DatagramPacket(erro.getBytes(), erro.getBytes().length, request.getAddress(),
                            request.getPort());
                    aSocket.send(reply);
                    continue;
                } catch (ArrayIndexOutOfBoundsException e) {
                    i--;
                    String MensagemErro = "Mensagem inválida, verifique o formato.";
                    reply = new DatagramPacket(MensagemErro.getBytes(), MensagemErro.getBytes().length,
                            request.getAddress(), request.getPort());
                    aSocket.send(reply);
                    continue;
                }
            }
        } catch (SocketException e) {
            System.out.println("Socket: " + e.getMessage());
        } catch (IOException e) {
            System.out.println("IO: " + e.getMessage());
        } finally {
            if (aSocket != null)
                aSocket.close();
        }
    }
}