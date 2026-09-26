import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class UDPServer {

    private static final List<String> receptionList = new ArrayList<>();
    private static final Map<Integer, String> temporaryMessages = new HashMap<>();

    /**
     * Processes delivered messages
     *
     * @return the last message processed in order
     */
    public static int processDeliveredMessages(int nLastMessageInOrder, int nCurrentMessage,
            String currentMessage) {
        if (nCurrentMessage <= nLastMessageInOrder) {
            return nLastMessageInOrder;
        }

        if (nCurrentMessage > nLastMessageInOrder + 1) {
            temporaryMessages.putIfAbsent(nCurrentMessage, currentMessage);
            return nLastMessageInOrder;
        }

        int lastMessageInOrder = nCurrentMessage;
        receptionList.add(currentMessage);

        while (temporaryMessages.containsKey(lastMessageInOrder + 1)) {
            lastMessageInOrder++;
            receptionList.add(temporaryMessages.remove(lastMessageInOrder));
        }

        return lastMessageInOrder;
    }

    public static void main(String args[]) throws IOException {
        DatagramSocket aSocket = null;
        DatagramPacket request = null;
        DatagramPacket reply;

        try {
            aSocket = new DatagramSocket(6789);
            byte[] buffer = new byte[1000];
            int lastMessageInOrder = 0; // L

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("Lista de receção final = " + receptionList);
                System.out.println("Temporárias finais = " + temporaryMessages);
            }));

            while (true) {
                try {
                    request = new DatagramPacket(buffer, buffer.length); // Garante que a informação que chega não seja
                                                                         // maior que 1000 bytes
                    aSocket.receive(request);
                    // offset garante que começamos a ler a mensagem no seu inicio
                    String mensagem = new String(request.getData(), 0, request.getLength(), StandardCharsets.UTF_8);
                    String[] id = mensagem.split(",", 2);

                    if (id.length < 2) {
                        String messageError = "Mensagem inválida, verifique o formato que foi introduzido.";
                        byte[] errorBytes = messageError.getBytes(StandardCharsets.UTF_8);
                        reply = new DatagramPacket(errorBytes, errorBytes.length,
                                request.getAddress(), request.getPort());
                        aSocket.send(reply);
                    } else {
                        int currentMessageNumber = Integer.parseInt(id[0]);
                        int previousLastMessageInOrder = lastMessageInOrder;
                        int previousDeliveredCount = receptionList.size();
                        lastMessageInOrder = processDeliveredMessages(lastMessageInOrder,
                                currentMessageNumber, id[1]);

                        System.out.println("Recebida = " + currentMessageNumber + "," + id[1]);
                        System.out.println("Mensagens entregues neste passo = "
                                + receptionList.subList(previousDeliveredCount, receptionList.size()));

                        if (lastMessageInOrder != previousLastMessageInOrder) {
                            reply = new DatagramPacket(request.getData(), request.getLength(), request.getAddress(),
                                    request.getPort());
                        } else {
                            String waitingMessage = "waitingfor," + (lastMessageInOrder + 1);
                            byte[] waitingBytes = waitingMessage.getBytes(StandardCharsets.UTF_8);
                            reply = new DatagramPacket(waitingBytes, waitingBytes.length,
                                    request.getAddress(), request.getPort());
                        }

                        System.out.println("L = " + lastMessageInOrder);
                        System.out.println("Temporárias = " + temporaryMessages);
                        aSocket.send(reply);
                    }
                } catch (NumberFormatException e) {
                    String errorMessage = "Erro: ID da mensagem não é um número válido.";
                    byte[] errorBytes = errorMessage.getBytes(StandardCharsets.UTF_8);
                    reply = new DatagramPacket(errorBytes, errorBytes.length, request.getAddress(),
                            request.getPort());
                    aSocket.send(reply);
                    continue;
                } catch (ArrayIndexOutOfBoundsException e) {
                    String errorMessage = "Mensagem inválida, verifique o formato.";
                    byte[] errorBytes = errorMessage.getBytes(StandardCharsets.UTF_8);
                    reply = new DatagramPacket(errorBytes, errorBytes.length,
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