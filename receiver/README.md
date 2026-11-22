# Receptor karaoke de LocalFly

El receptor personalizado muestra una interfaz de karaoke real en Chromecast: título/artista y la línea actual de la letra, sincronizada con la posición del audio. No depende de que el receptor multimedia estándar pinte subtítulos sobre una pista de audio.

## Despliegue obligatorio para activar el modo karaoke completo

Google Cast no permite cargar una página HTML arbitraria como interfaz de receptor con el ID estándar. El receptor personalizado debe estar publicado en una URL HTTPS y registrado en Google Cast Developer Console.

1. Publica **el contenido de esta carpeta** como sitio estático HTTPS (por ejemplo, Vercel, Netlify o Firebase Hosting). La página de entrada debe ser `index.html`.
2. En Google Cast SDK Developer Console, registra una aplicación de tipo **Custom Receiver** y configura la URL HTTPS del sitio publicado.
3. Copia el Application ID que te entrega la consola.
4. En el proyecto Android, define la propiedad Gradle `CAST_RECEIVER_APP_ID` con ese ID. Puedes añadirla a `gradle.properties` o pasarla como `-PCAST_RECEIVER_APP_ID=XXXXXXXX`.
5. Compila e instala de nuevo LocalFly.

Mientras no se configure un ID propio, la app mantiene el receptor estándar de Google (`CC1AD845`). Ese receptor puede recibir la pista WebVTT estándar, pero no puede mostrar la interfaz personalizada de karaoke de LocalFly.

## Comunicación

- Canal Cast: `urn:x-cast:com.example.localfly.karaoke`.
- El teléfono envía el título, artista y URL WebVTT después de que el receptor haya aceptado la carga del medio.
- El receptor descarga la letra desde el servidor local de LocalFly (puerto 8099) y usa el reloj del reproductor Cast para resaltar la línea activa.
- El modo «audio en el teléfono» envía una pista de silencio al televisor y conserva el audio real en el móvil.
- El modo «audio en el televisor» envía el audio real mediante el proxy local, con soporte CORS y Range.

El teléfono y el Chromecast deben permanecer en la misma red local para que el receptor pueda consultar el servidor de letras/audio del teléfono.

## Salidas de audio

- **Televisor:** se reproduce el audio en Chromecast.
- **Teléfono:** Chromecast muestra la letra y el audio continúa en el móvil.
- **Bluetooth:** el botón de salida de audio abre el selector de Android 14 para elegir altavoz del teléfono o un dispositivo Bluetooth. Si hay Cast activo, primero cambia al modo «Teléfono» y después permite seleccionar Bluetooth.

El receptor personalizado debe servirse por HTTPS. El servidor local del teléfono usa HTTP en la red privada y responde a las peticiones CORS/preflight del receptor.
