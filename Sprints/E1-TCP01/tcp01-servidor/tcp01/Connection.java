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
        this.clientSocket = clientSocket;

        start();
    }

    @Override
    public void run() {
        try {
            in = new ObjectInputStream(clientSocket.getInputStream());
            out = new DataOutputStream(clientSocket.getOutputStream());

            // Read person
            Person person = (Person) in.readObject(); // this also compares UID
            // Reply with person's info
            out.writeUTF(person.getPlace().getLocality(), person.getPlace().getPostalCode());
            out.flush();
        } catch (ClassNotFoundException e) {
            // May happen if classes packages are different
            System.out.println("Class not found: " + e.getMessage());
        } catch (InvalidClassException e) {
            System.out.println("Class found but Version UID is incompatible: " + e.getMessage());
        } catch (EOFException e) {
            // May happen if Serializable is removed from the client side
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