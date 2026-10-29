import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

public class Servidor {
    private static final int PUERTO = 5555;
    private static final int PUERTO_WEB = 8080;
    private static final List<PrintWriter> participantes = new ArrayList<>();
    private static final List<MensajeWeb> mensajesWeb = new ArrayList<>();
    private static long siguienteId = 1;

    public static void main(String[] args) {
        System.out.println("Iniciando servidor en el puerto " + PUERTO + "...");

        iniciarServidorWeb();

        try (ServerSocket servidor = new ServerSocket(PUERTO)) {
            System.out.println("Servidor listo. Esperando conexiones...");

            while (true) {
                Socket socket = servidor.accept();
                PrintWriter salida = new PrintWriter(
                        new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8),
                        true);

                synchronized (participantes) {
                    participantes.add(salida);
                    publicar("SYS:Una persona se ha conectado.");
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
                    publicar(linea);
                }
            }
        } catch (IOException e) {
            // La persona puede cerrar el programa o perder la conexión.
        } finally {
            synchronized (participantes) {
                participantes.remove(salida);
                publicar("SYS:Una persona se ha desconectado.");
            }
        }
    }

    private static void publicar(String linea) {
        synchronized (participantes) {
            for (PrintWriter participante : participantes) {
                participante.println(linea);
            }
        }
        synchronized (mensajesWeb) {
            mensajesWeb.add(new MensajeWeb(siguienteId++, linea));
            // Guardamos solo una ventana pequeña en memoria; nunca se escribe a disco.
            if (mensajesWeb.size() > 1000) {
                mensajesWeb.remove(0);
            }
        }
    }

    private static void iniciarServidorWeb() {
        try {
            HttpServer web = HttpServer.create(new InetSocketAddress("127.0.0.1", PUERTO_WEB), 0);
            web.createContext("/api/cursor", Servidor::responderCursor);
            web.createContext("/api/messages", Servidor::responderMensajes);
            web.createContext("/api/send", Servidor::recibirMensajeWeb);
            web.createContext("/styles.css", intercambio -> servirArchivoWeb(
                    intercambio, "/styles.css", "text/css; charset=utf-8"));
            web.createContext("/app.js", intercambio -> servirArchivoWeb(
                    intercambio, "/app.js", "text/javascript; charset=utf-8"));
            web.createContext("/", Servidor::servirPagina);
            web.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
            web.start();
            System.out.println("Web local lista en http://127.0.0.1:" + PUERTO_WEB);
            System.out.println("Para abrirla desde Tailscale, activa Tailscale Serve en este equipo.");
        } catch (IOException e) {
            System.out.println("No se pudo iniciar la parte web: " + e.getMessage());
        }
    }

    private static void servirPagina(HttpExchange intercambio) throws IOException {
        if (!intercambio.getRequestURI().getPath().equals("/")) {
            responder(intercambio, 404, "Recurso no encontrado");
            return;
        }
        servirArchivoWeb(intercambio, "/index.html", "text/html; charset=utf-8");
    }

    private static void servirArchivoWeb(HttpExchange intercambio, String ruta, String tipoContenido)
            throws IOException {
        if (!intercambio.getRequestMethod().equals("GET")) {
            responder(intercambio, 405, "Método no permitido");
            return;
        }
        Path archivo = buscarArchivoWeb(ruta.substring(1));
        if (archivo == null) {
            responder(intercambio, 404, "No se encuentra web/" + ruta.substring(1)
                    + ". Ejecuta Servidor desde la carpeta del proyecto.");
            return;
        }
        byte[] contenido = Files.readAllBytes(archivo);
        intercambio.getResponseHeaders().set("Content-Type", tipoContenido);
        intercambio.getResponseHeaders().set("Cache-Control", "no-store");
        intercambio.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        intercambio.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        intercambio.getResponseHeaders().set("Content-Security-Policy",
                "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; "
                        + "img-src 'self' data:; base-uri 'none'; frame-ancestors 'none'");
        intercambio.sendResponseHeaders(200, contenido.length);
        try (var salida = intercambio.getResponseBody()) {
            salida.write(contenido);
        }
    }

    private static Path buscarArchivoWeb(String nombre) {
        Path archivo = Path.of("web", nombre);
        if (Files.isRegularFile(archivo)) {
            return archivo;
        }
        archivo = Path.of("src", "web", nombre);
        return Files.isRegularFile(archivo) ? archivo : null;
    }

    private static void responderCursor(HttpExchange intercambio) throws IOException {
        if (!intercambio.getRequestMethod().equals("GET")) {
            responder(intercambio, 405, "Método no permitido");
            return;
        }
        synchronized (mensajesWeb) {
            responder(intercambio, 200, Long.toString(siguienteId - 1));
        }
    }

    private static void responderMensajes(HttpExchange intercambio) throws IOException {
        if (!intercambio.getRequestMethod().equals("GET")) {
            responder(intercambio, 405, "Método no permitido");
            return;
        }
        long desde;
        try {
            String consulta = intercambio.getRequestURI().getRawQuery();
            desde = Long.parseLong(consulta == null ? "0" : consulta.replaceFirst("^after=", ""));
        } catch (NumberFormatException e) {
            responder(intercambio, 400, "Cursor no válido");
            return;
        }
        StringBuilder cuerpo = new StringBuilder();
        synchronized (mensajesWeb) {
            for (MensajeWeb mensaje : mensajesWeb) {
                if (mensaje.id > desde) {
                    cuerpo.append(mensaje.id).append('\t')
                            .append(Base64.getEncoder().encodeToString(
                                    mensaje.linea.getBytes(StandardCharsets.UTF_8)))
                            .append('\n');
                }
            }
        }
        responder(intercambio, 200, cuerpo.toString());
    }

    private static void recibirMensajeWeb(HttpExchange intercambio) throws IOException {
        if (!intercambio.getRequestMethod().equals("POST")) {
            responder(intercambio, 405, "Método no permitido");
            return;
        }
        byte[] cuerpo = intercambio.getRequestBody().readNBytes(16_385);
        if (cuerpo.length > 16_384) {
            responder(intercambio, 413, "Mensaje demasiado largo");
            return;
        }
        String linea = new String(cuerpo, StandardCharsets.UTF_8);
        if (!linea.startsWith("MSG:") || linea.length() <= 4) {
            responder(intercambio, 400, "Mensaje no válido");
            return;
        }
        publicar(linea);
        responder(intercambio, 204, "");
    }

    private static void responder(HttpExchange intercambio, int estado, String texto) throws IOException {
        byte[] cuerpo = texto.getBytes(StandardCharsets.UTF_8);
        intercambio.getResponseHeaders().set("Cache-Control", "no-store");
        if (estado == 204) {
            intercambio.sendResponseHeaders(estado, -1);
            intercambio.close();
            return;
        }
        intercambio.sendResponseHeaders(estado, cuerpo.length);
        try (var salida = intercambio.getResponseBody()) {
            salida.write(cuerpo);
        }
    }

    private static class MensajeWeb {
        private final long id;
        private final String linea;

        private MensajeWeb(long id, String linea) {
            this.id = id;
            this.linea = linea;
        }
    }
}
