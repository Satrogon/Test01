import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

public class Chat {
    private static final int PUERTO = 5555;
    private static final byte[] SAL = "ChatPrivado-v1".getBytes(StandardCharsets.UTF_8);
    private static final int ITERACIONES = 200_000;
    private static final int TAMANO_CLAVE = 128;
    private static final int TAMANO_NONCE = 12;
    private static final SecureRandom ALEATORIO = new SecureRandom();

    public static void main(String[] args) {
        String ipServidor = args.length > 0 ? args[0] : "127.0.0.1";
        BufferedReader teclado = new BufferedReader(new InputStreamReader(System.in));

        try {
            System.out.print("Tu nombre: ");
            String nombre = teclado.readLine();
            System.out.print("Clave secreta del chat (compártela en privado): ");
            String claveCompartida = teclado.readLine();

            if (nombre == null || nombre.trim().isEmpty() || claveCompartida == null
                    || claveCompartida.isEmpty()) {
                System.out.println("Escribe un nombre y una clave secreta.");
                return;
            }

            SecretKey clave = crearClave(claveCompartida);

            try (Socket socket = new Socket(ipServidor, PUERTO);
                 BufferedReader entrada = new BufferedReader(
                         new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                 PrintWriter salida = new PrintWriter(
                         new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8),
                         true)) {

                System.out.println("Conectado. Escribe /salir para cerrar el chat.");
                System.out.println("Los mensajes se cifran antes de enviarse al servidor.");

                Thread lector = new Thread(() -> {
                    try {
                        String linea;
                        while ((linea = entrada.readLine()) != null) {
                            if (linea.startsWith("MSG:")) {
                                try {
                                    System.out.println("\n" + descifrar(
                                            linea.substring(4), clave));
                                } catch (GeneralSecurityException | IllegalArgumentException e) {
                                    System.out.println("\nMensaje recibido ilegible (clave distinta o datos dañados). ");
                                }
                                System.out.print("> ");
                            } else if (linea.startsWith("SYS:")) {
                                System.out.println("\n" + linea.substring(4));
                                System.out.print("> ");
                            }
                        }
                        System.out.println("\nEl servidor cerró la conexión.");
                    } catch (IOException e) {
                        System.out.println("\nSe cerró la conexión.");
                    }
                });
                lector.start();

                System.out.print("> ");
                String mensaje;
                while ((mensaje = teclado.readLine()) != null) {
                    if (mensaje.equalsIgnoreCase("/salir")) {
                        break;
                    }
                    if (!mensaje.trim().isEmpty()) {
                        salida.println("MSG:" + cifrar(nombre + ": " + mensaje, clave));
                    }
                    System.out.print("> ");
                }
            }
        } catch (IOException e) {
            System.out.println("No se pudo conectar a " + ipServidor + ":" + PUERTO
                    + ". Comprueba que el servidor está activo y que la dirección es correcta.");
        } catch (GeneralSecurityException e) {
            System.out.println("No se pudo preparar el cifrado: " + e.getMessage());
        }
    }

    private static SecretKey crearClave(String claveCompartida)
            throws GeneralSecurityException {
        PBEKeySpec especificacion = new PBEKeySpec(
                claveCompartida.toCharArray(), SAL, ITERACIONES, TAMANO_CLAVE);
        try {
            byte[] bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(especificacion).getEncoded();
            return new SecretKeySpec(bytes, "AES");
        } finally {
            especificacion.clearPassword();
        }
    }

    private static String cifrar(String texto, SecretKey clave)
            throws GeneralSecurityException {
        byte[] nonce = new byte[TAMANO_NONCE];
        ALEATORIO.nextBytes(nonce);

        Cipher cifrador = Cipher.getInstance("AES/GCM/NoPadding");
        cifrador.init(Cipher.ENCRYPT_MODE, clave, new GCMParameterSpec(128, nonce));
        byte[] textoCifrado = cifrador.doFinal(texto.getBytes(StandardCharsets.UTF_8));

        ByteBuffer paquete = ByteBuffer.allocate(nonce.length + textoCifrado.length);
        paquete.put(nonce);
        paquete.put(textoCifrado);
        return Base64.getEncoder().encodeToString(paquete.array());
    }

    private static String descifrar(String paqueteBase64, SecretKey clave)
            throws GeneralSecurityException {
        byte[] paquete = Base64.getDecoder().decode(paqueteBase64);
        if (paquete.length <= TAMANO_NONCE) {
            throw new IllegalArgumentException("Paquete incompleto");
        }

        ByteBuffer datos = ByteBuffer.wrap(paquete);
        byte[] nonce = new byte[TAMANO_NONCE];
        datos.get(nonce);
        byte[] textoCifrado = new byte[datos.remaining()];
        datos.get(textoCifrado);

        Cipher descifrador = Cipher.getInstance("AES/GCM/NoPadding");
        descifrador.init(Cipher.DECRYPT_MODE, clave, new GCMParameterSpec(128, nonce));
        byte[] texto = descifrador.doFinal(textoCifrado);
        return new String(texto, StandardCharsets.UTF_8);
    }
}
