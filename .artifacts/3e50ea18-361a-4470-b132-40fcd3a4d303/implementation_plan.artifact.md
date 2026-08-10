# Plan de Mejora: Iconos, Descargas Masivas y Paginación

Este plan detalla las correcciones para los iconos de "Me gusta", la implementación de descargas masivas en la biblioteca, el sistema de auto-eliminación en descargas y la paginación de la biblioteca.

## User Review Required

> [!IMPORTANT]
> El sistema de auto-eliminación de descargas se activará globalmente mediante un toggle en la pantalla de descargas. Se guardará en preferencias para que el `PlaybackService` sepa cuándo borrar el archivo al finalizar la reproducción.

## Proposed Changes

### 1. Unificación de Iconos de Like
Se reemplazarán las estrellas por defecto de Android por los iconos `ic_like_on` e `ic_like_off` en toda la aplicación.

#### [MODIFY] [MainActivity.kt](file:///C:/Users/wolf/StudioProjects/localFly/app/src/main/java/com/example/localfly/MainActivity.kt)
* Actualizar `refreshMiniPlayer` para usar `ic_like_on` e `ic_like_off`.

#### [MODIFY] [PlaybackService.kt](file:///C:/Users/wolf/StudioProjects/localFly/app/src/main/java/com/example/localfly/PlaybackService.kt)
* Actualizar `buildNotification` para usar los iconos personalizados si es posible (dependiendo de la compatibilidad de vectores en notificaciones).

---

### 2. Biblioteca: Descarga Masiva y Paginación

#### [MODIFY] [fragment_library.xml](file:///C:/Users/wolf/StudioProjects/localFly/app/src/main/res/layout/fragment_library.xml)
* Asegurar que el botón `btnDownloadAll` sea funcional y estéticamente correcto.

#### [MODIFY] [LibraryFragment.kt](file:///C:/Users/wolf/StudioProjects/localFly/app/src/main/java/com/example/localfly/fragments/LibraryFragment.kt)
* Implementar `loadMoreLibrary()` al llegar al final del RecyclerView (offset de 100 en 100).
* Implementar la lógica de `btnDownloadAll`: recorrer la lista actual y descargar las que falten.
* Actualizar dinámicamente el contador del botón verde.

---

### 3. Pantalla de Descargas y Auto-eliminación

#### [MODIFY] [fragment_downloads.xml](file:///C:/Users/wolf/StudioProjects/localFly/app/src/main/res/layout/fragment_downloads.xml)
* Rediseñar el header para incluir:
    * Botón de retroceso.
    * Contador de canciones bajo el título.
    * Barra de herramientas con el **Toggle de Auto-eliminación**.
    * Información de espacio ocupado (estimado).

#### [MODIFY] [DownloadsFragment.kt](file:///C:/Users/wolf/StudioProjects/localFly/app/src/main/java/com/example/localfly/fragments/DownloadsFragment.kt)
* Gestionar el estado del Toggle y guardarlo en `SharedPreferences`.

#### [MODIFY] [SessionManager.kt](file:///C:/Users/wolf/StudioProjects/localFly/app/src/main/java/com/example/localfly/network/SessionManager.kt)
* Añadir métodos para guardar/leer la preferencia `AUTO_DELETE_ON_FINISH`.

#### [MODIFY] [PlaybackService.kt](file:///C:/Users/wolf/StudioProjects/localFly/app/src/main/java/com/example/localfly/PlaybackService.kt)
* En el listener `onPlaybackStateChanged`, cuando el estado sea `STATE_ENDED`, verificar la preferencia. Si es true, llamar a `DownloadManagerHelper.removeDownload(currentSongId)`.

## Verification Plan

### Automated Tests
* N/A (Cambios principalmente de UI y lógica de flujo).

### Manual Verification
1. **Likes:** Comprobar que en la biblioteca y mini player se vea el corazón (`ic_like_on`) al dar like.
2. **Descarga Masiva:** Pulsar el botón verde de la biblioteca y verificar que se inician las descargas de todas las canciones visibles.
3. **Paginación:** Hacer scroll hasta abajo en la biblioteca y verificar que cargan más canciones después de la 100.
4. **Auto-eliminación:** Activar el toggle en Descargas, escuchar una canción descargada hasta el final, y verificar que desaparece de la lista de descargas y del almacenamiento.
