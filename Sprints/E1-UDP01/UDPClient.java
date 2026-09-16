import java.net.*;
import java.io.*;
import java.util.Scanner;


public class UDPClient {

    public static void main(String args[]) {
        DatagramSocket aSocket = null;
        Scanner input = new Scanner(System.in);
        String mensagem = null;
        int opcao,idMensagem = 0;

        System.out.println("Quer que a introdução do ID seja :\n 1 - Automática\n 2 - Manual\n:");
        opcao = input.nextInt();
        input.nextLine(); // Limpar Buffer

        switch (opcao){
            case 1 : System.out.println("<Mensagem>"); break;
            case 2 : System.out.println("<ID>,<Mensagem>");break;
        }

        try {
            while (true) {
                idMensagem ++;

                byte[] m;
                InetAddress aHost = InetAddress.getByName("localhost");
                int serverPort = 6789;
                DatagramPacket request = null;


                switch (opcao){
                    case 1 :
                        aSocket = new DatagramSocket();

                        System.out.print("Mensagem : ");
                        mensagem = input.nextLine().toLowerCase();

                        if (mensagem.equals("sair")) {
                            return;
                        }

                        m = mensagem.getBytes();

                        byte[] MensagemComID = new byte[1 + mensagem.length()];
                        MensagemComID[0] = (byte) idMensagem; // converte o ID da mensagem para bytes para ser enviada para o servidor
                        //System.arraycopy(m, 0, MensagemComID, 1, m.length); // associa o id à mensagem

                        String mensagemEnviar = (MensagemComID[0] & 0xFF) + "," + mensagem;

                        request = new DatagramPacket(mensagemEnviar.getBytes(),mensagemEnviar.length(),aHost,serverPort);
                        break;
                    case 2 :
                        aSocket = new DatagramSocket();

                        System.out.print("Mensagem : ");
                        mensagem = input.nextLine().toLowerCase();

                        m = mensagem.getBytes();
                        request = new DatagramPacket(m, m.length, aHost, serverPort);


                        if (mensagem.equals("sair")) {
                            return;
                        }
                        break;
                }

                aSocket.send(request);

                byte[] buffer = new byte[1000];

                DatagramPacket reply = new DatagramPacket(buffer, buffer.length);

                aSocket.receive(reply);
            }

        } catch (SocketException e) { System.out.println("Socket: " + e.getMessage());
        } catch (IOException e)     { System.out.println("IO: " + e.getMessage());
        } finally { if (aSocket != null) aSocket.close(); }
    }
}