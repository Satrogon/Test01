# Abrir el chat desde el móvil

El proyecto conserva el cliente de consola y añade una página web en `web/`. `index.html` contiene la estructura, `styles.css` los estilos y `app.js` el comportamiento y cifrado del navegador. El servidor Java escucha en el puerto `5555` para el cliente de consola y en `8080` para la página web. La página cifra los mensajes en el navegador con el mismo formato que usa `Chat.java`.

## En el ordenador que hace de servidor

1. Asegúrate de que el ordenador está conectado a la red de Tailscale.
2. Abre el proyecto actualizado en IntelliJ y ejecuta la clase `Servidor`.
3. Comprueba que la consola muestra `Web local lista en http://127.0.0.1:8080` y `Servidor listo`.
4. Abre Terminal en ese mismo ordenador y ejecuta:

   ```text
   tailscale serve --bg 8080
   ```

5. Tailscale mostrará una dirección HTTPS parecida a `https://nombre-dispositivo.nombre-red.ts.net`. Esa es la URL que se abre en el móvil. La primera vez puede pedir habilitar HTTPS para la red de Tailscale.

## En el móvil

1. Instala y abre Tailscale, inicia sesión en la misma red que el servidor y confirma que aparece conectado.
2. Abre en el navegador del móvil la URL HTTPS que mostró el comando.
3. Escribe tu nombre y la misma clave secreta que usan los demás participantes.

No uses la dirección `http://100.x.x.x:8080` desde el móvil: la página necesita HTTPS para usar el cifrado seguro del navegador. Tailscale Serve permite acceder a la URL solo desde la red Tailscale; el móvil necesita Tailscale conectado.

## Cerrar

- Para apagar el chat, detén `Servidor` en IntelliJ.
- Para desactivar también la URL de Tailscale, ejecuta `tailscale serve off` en el ordenador servidor.

Los mensajes y sus cursores se mantienen solo en memoria mientras `Servidor` está ejecutándose; no se guardan en archivos ni en una base de datos. El servidor retransmite los mensajes cifrados y no tiene la clave para leerlos. Usa una clave secreta larga y difícil de adivinar, y compártela solo con las personas del chat.
