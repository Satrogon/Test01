import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class Servidor {
    private static final int PUERTO = 5555;
    private static final List<PrintWriter> participantes = new ArrayList<>();

    public static void main(String[] args) {
        System.out.println("Iniciando servidor en el puerto " + PUERTO + "...");

        try (ServerSocket servidor = new ServerSocket(PUERTO)) {
            System.out.println("Servidor listo. Esperando conexiones...");

            while (true) {
                Socket socket = servidor.accept();
                PrintWriter salida = new PrintWriter(
                        new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8),
                        true);

                synchronized (participantes) {
                    participantes.add(salida);
                    enviarATodos("SYS:Una persona se ha conectado.");
                }

                Thread hilo = new Thread(() -> atender(socket, salida));
                hilo.start();
            }
        } catch (IOException e) {
            System.out.println("Error en el servidor: " + e.getMessage());
        }
    }

    private static void atender(Socket socket, PrintWriter salida) {
        try (Socket conexion = socket;
             BufferedReader entrada = new BufferedReader(
                     new InputStreamReader(conexion.getInputStream(), StandardCharsets.UTF_8))) {
            String linea;
            while ((linea = entrada.readLine()) != null) {
                // El servidor solo reenvía la línea cifrada; no conoce la clave del chat.
                if (linea.startsWith("MSG:")) {
                    enviarATodos(linea);
                }
            }
        } catch (IOException e) {
            // La persona puede cerrar el programa o perder la conexión.
        } finally {
            synchronized (participantes) {
                participantes.remove(salida);
                enviarATodos("SYS:Una persona se ha desconectado.");
            }
        }
    }

    private static void enviarATodos(String linea) {
        synchronized (participantes) {
            for (PrintWriter participante : participantes) {
                participante.println(linea);
            }
        }
    }
}
