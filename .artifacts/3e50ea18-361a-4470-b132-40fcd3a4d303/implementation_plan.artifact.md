# Plan de Mejora Visual, Funcional y de Metadatos

Este plan aborda la actualización de la tipografía, la mejora de la lógica de descargas, la habilitación de la búsqueda en la biblioteca y la renovación visual de la pantalla "Reproduciendo ahora" según los nuevos requerimientos.

## User Review Required

> [!IMPORTANT]
> Se implementará una nueva lógica de nombres de archivos para portadas y letras basada en el formato solicitado (`artist - [Nombre].jpg`, `album - [Nombre].jpg`, `[Titulo]_[Indice].lrc`). Esto asume que el servidor soporta estos recursos bajo la URL base.

## Proposed Changes

### 1. Tipografía y Estilo Global
*   Actualizar el tema principal para usar una fuente más delgada (`sans-serif-light`) y moderna.

#### [MODIFY] [themes.xml](file:///C:/Users/wolf/StudioProjects/localFly/app/src/main/res/values/themes.xml)
*   Añadir `android:fontFamily="sans-serif-light"` al estilo base.

---

### 2. Biblioteca: Búsqueda y Descargas Mejoradas
*   Añadir un campo de texto funcional para filtrar canciones.
*   Corregir el contador de `btnDownloadAll` para mostrar "canciones pendientes" y asegurar que la descarga continúe en segundo plano.

#### [MODIFY] [fragment_library.xml](file:///C:/Users/wolf/StudioProjects/localFly/app/src/main/res/layout/fragment_library.xml)
*   Integrar un `EditText` dentro de la barra de búsqueda.

#### [MODIFY] [LibraryFragment.kt](file:///C:/Users/wolf/StudioProjects/localFly/app/src/main/java/com/example/localfly/fragments/LibraryFragment.kt)
*   Implementar filtrado en tiempo real con `TextWatcher`.
*   Cambiar el alcance de la corrutina de descarga a `activity?.lifecycleScope` para persistencia entre pestañas.
*   Actualizar contador de descargas pendientes.

---

### 3. Descargas: Información y Utilidades
*   Habilitar el icono de información para explicar el funcionamiento del toggle.

#### [MODIFY] [DownloadsFragment.kt](file:///C:/Users/wolf/StudioProjects/localFly/app/src/main/java/com/example/localfly/fragments/DownloadsFragment.kt)
*   Añadir listener a `ivInfo` para mostrar el mensaje: "Si el toggle está activo, la canción se eliminará después de reproducir".

---

### 4. Reproductor: Nueva Experiencia Visual y Metadatos
*   Rediseñar la vista "Reproduciendo ahora" con fondo difuminado y círculo de artista.
*   Ajustar la carga de recursos según la nueva convención de nombres.

#### [MODIFY] [activity_now_playing.xml](file:///C:/Users/wolf/StudioProjects/localFly/app/src/main/res/layout/activity_now_playing.xml)
*   Cambiar a `ConstraintLayout`.
*   Añadir `ivBackground` (fondo difuminado).
*   Añadir `ivArtistCircle` (imagen circular del artista).

#### [MODIFY] [NowPlayingActivity.kt](file:///C:/Users/wolf/StudioProjects/localFly/app/src/main/java/com/example/localfly/NowPlayingActivity.kt)
*   Lógica para cargar imagen de artista (`artist - [Name].jpg`) en el círculo.
*   Lógica para cargar imagen de álbum (`album - [Name].jpg`) en el fondo con efecto blur.
*   Soporte para letras `.lrc` (preparar UI para mostrar texto si es posible).

#### [MODIFY] [SongAdapter.kt](file:///C:/Users/wolf/StudioProjects/localFly/app/src/main/java/com/example/localfly/SongAdapter.kt)
*   Actualizar la carga de portadas para seguir el formato `album - [Nombre].jpg` si se requiere.

## Verification Plan

### Automated Tests
* N/A

### Manual Verification
1.  **Tipografía:** Verificar que el texto en toda la app se vea más delgado y limpio.
2.  **Búsqueda:** Escribir en la barra de búsqueda de la biblioteca y comprobar que la lista se filtra instantáneamente.
3.  **Descargas:** Iniciar una descarga masiva, cambiar a la pestaña de "Inicio" y volver a "Biblioteca" para comprobar que el contador sigue avanzando.
4.  **Información:** Pulsar el icono `(i)` en descargas y ver el mensaje explicativo.
5.  **Reproductor:** Abrir una canción y verificar el fondo difuminado y la foto del artista en el círculo central.
