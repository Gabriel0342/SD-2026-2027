package tcp01;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.net.ConnectException;
import java.net.Socket;
import java.net.UnknownHostException;

public class TCPClient {
    public static void main(String[] args) {
        Socket socket = null;
        try {
            int serverPort = 7896;
            socket = new Socket("localhost", serverPort); // Blocks until TCP handshake with the server is finished

            // Setup in/out
            ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());

            Place place = new Place("3500-000", "Viseu");
            Person person = new Person("Mateus", place, 2000);

            System.out.println("Sending person:");
            System.out.println("  Name: " + person.getName());
            System.out.println("  Year: " + person.getYear());
            System.out.println("  Postal code: " + person.getPlace().getPostalCode());
            System.out.println("  Locality: " + person.getPlace().getLocality());

            out.writeObject(person); // Stores object, serialization metadata and class identifiers such as serialVersionUID in the stream
            out.flush(); // Send

            // Blocks waiting for the full string
            String data = in.readUTF();
            System.out.println("Received: " + data);
        } catch (ConnectException e) {
            // Happens if Server is not running
            System.out.println("Couldn't connect: " + e.getMessage());
        } catch (UnknownHostException e) {
            System.out.println("Sock: " + e.getMessage());
        } catch (EOFException e) {
            System.out.println("EOF: " + e.getMessage());
        } catch (IOException e) {
            System.out.println("IO: " + e.getMessage());
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException e) {
                    System.out.println("close: " + e.getMessage());
                }
            }
        }
    }
}