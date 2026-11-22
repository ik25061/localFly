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

## Publicar el receptor desde GitHub Pages

El repositorio incluye el workflow `.github/workflows/cast-receiver-pages.yml`, que publica automáticamente la carpeta `receiver/` en GitHub Pages al cambiar esos archivos en la rama `audit-fix-2026-11`.

1. Abre **Settings → Pages** del repositorio y comprueba que la fuente de publicación sea **GitHub Actions**. Si GitHub pide habilitar Pages, actívalo.
2. Cuando termine el workflow, la dirección prevista del receptor será:
   `https://ik25061.github.io/localFly/`
3. Abre esa dirección desde un navegador para comprobar que aparece “LOCALFLY · KARAOKE”.
4. Entra en Google Cast Developer Console, crea una aplicación **Custom Receiver** y registra exactamente esa URL HTTPS.
5. Google generará un **Application ID** propio. Ese ID no es el nombre del proyecto ni el ID `CC1AD845`.
6. En Android Studio, añade a `gradle.properties`:
   `CAST_RECEIVER_APP_ID=TU_APPLICATION_ID`
7. Sincroniza Gradle, compila e instala la aplicación de nuevo.

**Importante:** el ID de Google Cast lo debe generar el propietario desde su cuenta de Google Cast Developer Console. Hasta que se registre el receptor y se introduzca ese ID, LocalFly seguirá usando el receptor estándar y no podrá mostrar el diseño karaoke personalizado. El workflow de Pages solo publica la página; no crea ni registra automáticamente la aplicación en Google.

## Comportamiento de las salidas de audio

- **Televisor:** el Chromecast reproduce el audio real y muestra la letra sincronizada.
- **Teléfono:** el Chromecast reproduce una pista silenciosa para mantener el reloj de vídeo/letra; el audio real continúa en el móvil.
- **Bluetooth:** se mantiene el modo de audio local y Android permite seleccionar un dispositivo Bluetooth conectado desde el selector de salida. El Chromecast continúa mostrando el karaoke.

El teléfono y el Chromecast deben estar en la misma red local. La app debe permanecer activa como servicio de reproducción durante la sesión Cast para servir la letra y, cuando corresponda, el audio.
