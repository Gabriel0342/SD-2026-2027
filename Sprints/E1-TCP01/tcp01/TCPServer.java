package tcp01;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

public class TCPServer {
    public static void main(String[] args) {
        int serverPort = 7896;

        try (ServerSocket listenSocket = new ServerSocket(serverPort)) {
            System.out.println("Server started on port 7896.");

            while (true) {
                Socket clientSocket = listenSocket.accept(); // Blocks until TCP connection is established
                new Connection(clientSocket); // Dispatch the client to a new connection (thread)
            }
        } catch (IOException e) {
            System.out.println("Listen: " + e.getMessage());
        }
    }
}