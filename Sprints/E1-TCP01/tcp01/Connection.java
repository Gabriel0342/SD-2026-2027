package tcp01;

import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InvalidClassException;
import java.io.ObjectInputStream;
import java.net.Socket;

public class Connection extends Thread {
    private ObjectInputStream in;
    private DataOutputStream out;
    private Socket clientSocket;

    public Connection(Socket clientSocket) {
        try {
            this.clientSocket = clientSocket;
            in = new ObjectInputStream(clientSocket.getInputStream());
            out = new DataOutputStream(clientSocket.getOutputStream());

            start();
        } catch (IOException e) {
            System.out.println("Connection: " + e.getMessage());
        }
    }

    @Override
    public void run() {
        try {
            // Read person
            Person person = (Person) in.readObject();
            // Reply with person's info
            out.writeUTF(person.getPlace().getLocality());
            out.flush();
        } catch (ClassNotFoundException e) {
            System.out.println("Class not found: " + e.getMessage());
        } catch (InvalidClassException e) {
            System.out.println("Class found but Version UID is incompatible: " + e.getMessage());
        } catch (EOFException e) {
            System.out.println("EOF: " + e.getMessage());
        } catch (IOException e) {
            System.out.println("IO: " + e.getMessage());
        } finally {
            try {
                clientSocket.close();
            } catch (IOException e) {
                System.out.println("close: " + e.getMessage());
            }
        }
    }
}