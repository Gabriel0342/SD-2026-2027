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
                String mensagem = new String(request.getData(), 0, request.getLength(), StandardCharsets.UTF_8);
                String[] id = mensagem.split(",");

                if(Integer.parseInt(id[0]) == i){
                    System.out.println("ID : " + id[0] +" | Mensagem Recebida : " + id[1]);
                }else{
                    System.out.println("Falta a mensagem com id : " + i);
                    i--;
                }

                DatagramPacket reply = new DatagramPacket(request.getData(), request.getLength(), request.getAddress(), request.getPort());

                aSocket.send(reply);
            }
        } catch (SocketException e) { System.out.println("Socket: " + e.getMessage());
        } catch (IOException e)     { System.out.println("IO: " + e.getMessage());
        } finally { if (aSocket != null) aSocket.close(); }
    }
}