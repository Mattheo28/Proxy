import java.io.*;
import java.net.*;

public class Proxy extends Thread {

    private int port;

    public static void main(String[] args) {
        try {
            (new Proxy(Integer.parseInt(args[0]))).run();
        } catch (ArrayIndexOutOfBoundsException e) {
            System.out.println("Invalid number of arguments. Please only supply 1 argument - the port number");
        } catch (NumberFormatException e) {
            System.out.println("Invalid port number.");
        } catch (IllegalArgumentException e) {
            System.out.println("Port number out of range.");
        }
    }

    public Proxy(int port) {
        super("Proxy Thread");
        this.port = port;
    }

    @Override
    public void run() {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Running proxy on port " + port);
            Socket socket;
            while ((socket = serverSocket.accept()) != null) {
                (new Handler(socket)).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}