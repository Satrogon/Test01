import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

public class Chat {
    public static void main(String[] args) {
        String ipServidor = "127.0.0.1";
        int puerto = 5555;

        try (Socket socket = new Socket(ipServidor, puerto)) {
            System.out.println("Conectado al servidor con éxito.");

            BufferedReader entrada = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            PrintWriter salida = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader teclado = new BufferedReader(new InputStreamReader(System.in));

            System.out.println("los odio");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
