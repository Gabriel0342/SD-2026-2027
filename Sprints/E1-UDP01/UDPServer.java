import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

public class UDPServer {

    public static void main(String args[]) {
        DatagramSocket aSocket = null;

        try {
            aSocket = new DatagramSocket(6789);
            byte[] buffer = new byte[1000];
            int i = 0;

            while (true) {
                i++;
                DatagramPacket request = new DatagramPacket(buffer, buffer.length);
                aSocket.receive(request);

                byte[] mensagemRecebida = request.getData();
                int idRecebido = mensagemRecebida[0] & 0xFF;
                String mensagem = new String(mensagemRecebida, 1, request.getLength() - 1, StandardCharsets.UTF_8);

                if(i != idRecebido){
                    System.out.println("Falta a mensagem com o ID : " + i);
                    i--;
                }else {
                    System.out.println(idRecebido + " - " +mensagem);
                }

                DatagramPacket reply = new DatagramPacket(request.getData(),
                        request.getLength(), request.getAddress(), request.getPort());

                aSocket.send(reply);
            }
        } catch (SocketException e) { System.out.println("Socket: " + e.getMessage());
        } catch (IOException e)     { System.out.println("IO: " + e.getMessage());
        } finally { if (aSocket != null) aSocket.close(); }
    }
}