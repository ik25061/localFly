# Plan de Implementación: Correcciones UI, Ajustes de Fondo y Sistema de Podcasts

Este plan aborda los problemas reportados de navegación y visualización, añade funciones de edición de metadatos y gestión de listas, e integra un sistema completo de Podcasts con soporte para reanudación, subtítulos y organización lógica.

## User Review Required

> [!IMPORTANT]
> - **Acceso a E:/podcast/**: La aplicación Android requerirá permisos de acceso a archivos si el servidor no los sirve como streams. Asumiremos que el servidor se actualizará para indexar esta carpeta y proporcionará nuevos endpoints (`/api/podcasts`).
> - **Selectores iOS-style**: Se implementará un selector de color personalizado con sliders de RGB y Opacidad para cumplir con la estética solicitada.

## Proposed Changes

### 1. Correcciones de UI y Navegación

#### [MODIFY] [MainActivity.kt](file:///C:/Users/wolf/StudioProjects/localFly/app/src/main/java/com/example/localfly/MainActivity.kt)
- Corregir `applyBackgroundAppearance` para que se aplique al layout raíz (`rootMain`) en lugar de solo al `container`, evitando que el fondo negro por defecto tape los cambios.
- Asegurar que `bottomNavigation` no cambie su `visibility` o `alpha` accidentalmente al reproducir desde descargas.

#### [MODIFY] [activity_main.xml](file:///C:/Users/wolf/StudioProjects/localFly/app/src/main/res/layout/activity_main.xml)
- Asegurar que el `BottomNavigationView` esté por encima del fondo y sea visible en todo momento.

---

### 2. Gestión de "No me gusta" y Metadatos

#### [MODIFY] [SongAdminStore.kt](file:///C:/Users/wolf/StudioProjects/localFly/app/src/main/java/com/example/localfly/network/SongAdminStore.kt)
- Revisar el método `recordDislikedSong` para asegurar que el `dislikedCache` se persista correctamente y se recupere al iniciar.

#### [MODIFY] [NowPlayingActivity.kt](file:///C:/Users/wolf/StudioProjects/localFly/app/src/main/java/com/example/localfly/NowPlayingActivity.kt) y [mini_player.xml](file:///C:/Users/wolf/StudioProjects/localFly/app/src/main/res/layout/mini_player.xml)
- Añadir un botón de "Editar Metadatos" en la pantalla de reproducción actual.
- Vincularlo con el diálogo `EditSongMetadataDialog` existente.

---

### 3. Listas de Reproducción (Visibilidad)

#### [MODIFY] [PlaylistDetailFragment.kt](file:///C:/Users/wolf/StudioProjects/localFly/app/src/main/java/com/example/localfly/fragments/PlaylistDetailFragment.kt)
- Depurar el fallo en `updatePlayList`. Se revisará el envío del campo `isPublic` al servidor para asegurar compatibilidad.

---

### 4. Ajustes Visuales Avanzados (Selectores)

#### [NEW] [ColorPickerDialog.kt](file:///C:/Users/wolf/StudioProjects/localFly/app/src/main/java/com/example/localfly/dialogs/ColorPickerDialog.kt)
- Implementar selector de color tipo iOS (penúltima imagen de referencia) con sliders para Rojo, Verde, Azul y Opacidad.

#### [MODIFY] [SettingsFragment.kt](file:///C:/Users/wolf/StudioProjects/localFly/app/src/main/java/com/example/localfly/fragments/SettingsFragment.kt)
- Añadir vista de previsualización en tiempo real para el desenfoque y transparencia (última imagen de referencia).

---

### 5. Sistema de Podcasts [NUEVO]

#### [NEW] [PodcastModels.kt](file:///C:/Users/wolf/StudioProjects/localFly/app/src/main/java/com/example/localfly/network/PodcastModels.kt)
- Definir `Podcast` y `Episode`.
- El modelo `Episode` incluirá `lastPosition` y `subtitleUrl`.

#### [NEW] [PodcastsFragment.kt](file:///C:/Users/wolf/StudioProjects/localFly/app/src/main/java/com/example/localfly/fragments/PodcastsFragment.kt) y [PodcastDetailFragment.kt](file:///C:/Users/wolf/StudioProjects/localFly/app/src/main/java/com/example/localfly/fragments/PodcastDetailFragment.kt)
- Pantalla para listar todos los podcasts (orden natural por número de episodio).
- Pantalla de detalle de podcast con lista de episodios.

#### [MODIFY] [HomeFragment.kt](file:///C:/Users/wolf/StudioProjects/localFly/app/src/main/java/com/example/localfly/fragments/HomeFragment.kt)
- Añadir sección "Podcasts" con scroll horizontal.

#### [MODIFY] [PlaybackService.kt](file:///C:/Users/wolf/StudioProjects/localFly/app/src/main/java/com/example/localfly/PlaybackService.kt)
- Implementar lógica de "Continuar donde lo dejaste" para podcasts.
- Guardar el progreso cada 5 segundos de reproducción.

#### [MODIFY] [SettingsFragment.kt](file:///C:/Users/wolf/StudioProjects/localFly/app/src/main/java/com/example/localfly/fragments/SettingsFragment.kt)
- Añadir toggle en Administración: "Mezclar música con podcasts" o "Separar contextos".

## Verification Plan

### Automated Tests
- Verificar persistencia de `lastPosition` en SharedPreferences/DB local.
- Validar el ordenamiento natural de episodios (1, 2... 10, 41) mediante unit tests.

### Manual Verification
- Probar el cambio de fondo en `MainActivity` y verificar que el `BottomNavigationView` permanezca visible.
- Marcar una canción como "No me gusta" y comprobar su aparición en el Panel de Administrador.
- Cambiar la visibilidad de una playlist y verificar el mensaje de éxito.
- Reproducir un podcast, salir de la app, volver a entrar y verificar que reanuda en el mismo segundo.
