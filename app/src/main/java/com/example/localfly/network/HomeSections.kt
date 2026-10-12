package com.example.localfly.network

/**
 * Definición de las secciones estándar reordenables de la pantalla de Inicio.
 *
 * El orden por defecto coincide con el orden declarado en `fragment_home.xml`.
 * Cada usuario puede guardar su propio orden (ver `SessionManager`), y el
 * panel de administrador ofrece una pantalla para cambiarlo.
 */
object HomeSections {

    const val KEY_PLAYLISTS = "playlists"
    const val KEY_PODCASTS = "podcasts"
    const val KEY_ALBUMS = "albums"
    const val KEY_ARTISTS = "artists"
    const val KEY_GENRES = "genres"
    const val KEY_YEARS = "years"
    const val KEY_RECOMMENDATIONS = "recommendations"
    const val KEY_LIBRARY = "library"

    /** Orden por defecto (idéntico al del layout). */
    val DEFAULT_ORDER: List<String> = listOf(
        KEY_PLAYLISTS,
        KEY_PODCASTS,
        KEY_ALBUMS,
        KEY_ARTISTS,
        KEY_GENRES,
        KEY_YEARS,
        KEY_RECOMMENDATIONS,
        KEY_LIBRARY
    )

    /** Título visible de cada sección. */
    fun title(key: String): String = when (key) {
        KEY_PLAYLISTS -> "Mis Playlists"
        KEY_PODCASTS -> "Podcasts"
        KEY_ALBUMS -> "Álbumes"
        KEY_ARTISTS -> "Artistas"
        KEY_GENRES -> "Géneros"
        KEY_YEARS -> "Por Año"
        KEY_RECOMMENDATIONS -> "Recomendaciones para ti"
        KEY_LIBRARY -> "Tu Biblioteca"
        else -> key
    }
}
