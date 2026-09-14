import java.net.*;
import java.io.*;
import java.util.Scanner;

public class UDPClient {

    public static void main(String args[]) {
        DatagramSocket aSocket = null;

        try {
            aSocket = new DatagramSocket();
            Scanner input = new Scanner(System.in);
            String mensagem = null;
            int id = 0;

            while(true){
                System.out.print("Sua Mensagem :");
                mensagem = input.nextLine().toLowerCase();
                if(mensagem.equals("sair")){
                    return;
                }
                byte[] m = mensagem.getBytes();
                id ++;
                byte[] MensagemComID = new byte[1 + mensagem.length()]; // Armazenar o ID
                InetAddress aHost = InetAddress.getByName("localhost");
                int serverPort = 6789;

                MensagemComID[0] = (byte)id; // converte o ID da mensagem para bytes para ser enviada para o servidor

                System.arraycopy(m, 0, MensagemComID, 1, m.length); // associa o id à mensagem


                DatagramPacket request = new DatagramPacket(MensagemComID,MensagemComID.length,aHost,serverPort);

                aSocket.send(request);

                byte[] buffer = new byte[1000];

                DatagramPacket reply = new DatagramPacket(buffer, buffer.length);

                aSocket.receive(reply);

                System.out.println("Reply: " + new String(reply.getData()));
            }
        } catch (SocketException e) { System.out.println("Socket: " + e.getMessage());
        } catch (IOException e)     { System.out.println("IO: " + e.getMessage());
        } finally { if (aSocket != null) aSocket.close(); }
    }
}