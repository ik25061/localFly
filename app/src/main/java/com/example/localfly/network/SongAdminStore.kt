package com.example.localfly.network

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Almacén local del Administrador.
 *
 * Guarda en preferencias de la app (SharedPreferences "localfly_admin_data"):
 *  1. Ediciones manuales de metadatos de canciones (título mostrado, álbum,
 *     géneros, año y estado de ánimo). Las ediciones están ligadas al id
 *     estable de la canción, de modo que tras un REESCANEO el servidor puede
 *     volver a entregar el metadato original, pero la app sigue reconociendo
 *     la misma canción y muestra los datos nuevos editados por el admin.
 *  2. Canciones marcadas como "No me gusta" (dislike), para que el admin
 *     pueda revisarlas y, si lo desea, eliminarlas por completo del disco.
 *  3. Géneros creados por el administrador, con la selección de canciones
 *     que les pertenecen (persistidos localmente).
 *
 * No toca nada del servidor: es un complemento local 100 % compatible con
 * el sistema existente.
 */
data class SongEdit(
    val songId: String,
    val title: String? = null,
    val album: String? = null,
    val genres: List<String> = emptyList(),
    val year: Int? = null,
    val mood: String? = null,
    val originalTitle: String? = null,
    val originalArtist: String? = null,
    val originalAlbum: String? = null,
    val originalYear: Int? = null,
    val editedAtMs: Long = 0L
)

data class DislikedSong(
    val songId: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val year: Int? = null,
    val dislikedAtMs: Long = 0L
)

data class CustomGenre(
    val id: String,
    val name: String,
    val description: String? = null,
    val songIds: List<String> = emptyList(),
    val createdAtMs: Long = 0L
)

/**
 * Guarda las ediciones del admin junto con el valor que devolvía el servidor
 * en el momento de editar, para poder mostrar la comparación "servidor vs
 * editado" en la pestaña de conflictos de reescaneo.
 */
object SongAdminStore {

    private const val PREFS_NAME = "localfly_admin_data"
    private const val KEY_EDITS = "edits"
    private const val KEY_DISLIKED = "disliked"
    private const val KEY_GENRES = "genres"

    private var context: Context? = null

    private val gson = Gson()

    // Cachés en memoria para que applyTo() funcione sin depender del contexto
    // (los adaptadores se construyen antes y applyTo solo necesita el último
    // estado conocido). ensureContext() recarga desde disco.
    private var editsCache: Map<String, SongEdit> = emptyMap()
    private var dislikedCache: List<DislikedSong> = emptyList()
    private var genresCache: List<CustomGenre> = emptyList()

    /** Se llama desde SessionManager (que se crea en cada fragmento). */
    fun ensureContext(appContext: Context) {
        context = appContext
        reload()
    }

    private fun prefs(): SharedPreferences? {
        val appContext = context ?: return null
        return appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun reload() {
        val store = prefs() ?: return
        editsCache = read(store, KEY_EDITS) { it.songId }
        dislikedCache = readDisliked(store)
        genresCache = readGenres(store)
    }

    private fun read(store: SharedPreferences, key: String, keyOf: (SongEdit) -> String): Map<String, SongEdit> {
        val json = store.getString(key, null) ?: return emptyMap()
        return try {
            val type = object : TypeToken<List<SongEdit>>() {}.type
            val list: List<SongEdit> = gson.fromJson(json, type) ?: emptyList()
            list.associate { keyOf(it) to it }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun readDisliked(store: SharedPreferences): List<DislikedSong> {
        val json = store.getString(KEY_DISLIKED, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<DislikedSong>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun readGenres(store: SharedPreferences): List<CustomGenre> {
        val json = store.getString(KEY_GENRES, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<CustomGenre>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Aplica la edición local a una canción del servidor. Devuelve una copia
     *  con los campos editados (título, álbum y año). Los géneros y el estado
     *  de ánimo no forman parte del modelo Song del servidor, por lo que se
     *  consultan por separado con getEditedGenres()/getEditedMood(). */
    fun applyTo(song: Song): Song {
        val edit = editsCache[song.id] ?: return song
        return song.copy(
            title = edit.title ?: song.title,
            album = edit.album ?: song.album,
            year = edit.year ?: song.year
        )
    }

    fun applyTo(songs: List<Song>): List<Song> = songs.map { applyTo(it) }

    // ===================== EDICIONES DE METADATOS =====================

    fun getEdits(): Map<String, SongEdit> = editsCache

    fun getEdit(songId: String): SongEdit? = editsCache[songId]

    fun hasEdit(songId: String): Boolean = editsCache.containsKey(songId)

    /** Géneros editados para una canción (lista de nombres). */
    fun getEditedGenres(songId: String): List<String> = editsCache[songId]?.genres ?: emptyList()

    /** Estado de ánimo editado para una canción. */
    fun getEditedMood(songId: String): String? = editsCache[songId]?.mood

    fun saveEdit(edit: SongEdit) {
        if (edit.title.isNullOrBlank() && edit.album.isNullOrBlank() &&
            edit.genres.isEmpty() && edit.year == null && edit.mood.isNullOrBlank()
        ) {
            // Edición vacía = sin cambios; quitar cualquier edición previa.
            removeEdit(edit.songId)
            return
        }
        val updated = edit.copy(editedAtMs = System.currentTimeMillis())
        val map = editsCache.toMutableMap()
        map[updated.songId] = updated
        editsCache = map
        persistEdits()
    }

    fun removeEdit(songId: String) {
        if (!editsCache.containsKey(songId)) return
        val map = editsCache.toMutableMap()
        map.remove(songId)
        editsCache = map
        persistEdits()
    }

    private fun persistEdits() {
        val store = prefs() ?: return
        val list = editsCache.values.toList().sortedByDescending { it.editedAtMs }
        store.edit().putString(KEY_EDITS, gson.toJson(list)).apply()
    }

    // ===================== CANCIONES "NO ME GUSTA" =====================

    fun getDislikedSongs(): List<DislikedSong> {
        if (context != null) reload() // Siempre recargar para asegurar datos frescos del disco
        return dislikedCache.sortedByDescending { it.dislikedAtMs }
    }

    /** Registra la canción marcada como "No me gusta" para revisión del admin. */
    fun recordDislikedSong(song: Song) {
        if (song.id.isBlank()) return
        if (context != null) reload()
        
        Log.d("SongAdminStore", "Recording dislike for: ${song.title} (${song.id})")
        
        // Evitar duplicados
        if (dislikedCache.any { it.songId == song.id }) return
        
        dislikedCache = dislikedCache + DislikedSong(
            songId = song.id,
            title = song.title,
            artist = song.artist,
            album = song.album,
            year = song.year,
            dislikedAtMs = System.currentTimeMillis()
        )
        persistDisliked()
    }

    fun removeDislikedSong(songId: String) {
        if (dislikedCache.none { it.songId == songId }) return
        dislikedCache = dislikedCache.filter { it.songId != songId }
        persistDisliked()
    }

    fun clearDislikedSongs() {
        if (dislikedCache.isEmpty()) return
        dislikedCache = emptyList()
        persistDisliked()
    }

    private fun persistDisliked() {
        val store = prefs() ?: return
        store.edit().putString(KEY_DISLIKED, gson.toJson(dislikedCache)).apply()
    }

    // ===================== GÉNEROS DEL ADMINISTRADOR =====================

    fun getGenres(): List<CustomGenre> = genresCache.sortedBy { it.name.lowercase() }

    fun getGenre(genreId: String): CustomGenre? = genresCache.firstOrNull { it.id == genreId }

    fun saveGenre(genre: CustomGenre) {
        genresCache = genresCache.filter { it.id != genre.id } + genre
        persistGenres()
    }

    fun deleteGenre(genreId: String) {
        genresCache = genresCache.filter { it.id != genreId }
        persistGenres()
    }

    fun setGenreSongIds(genreId: String, songIds: List<String>) {
        val genre = getGenre(genreId) ?: return
        saveGenre(genre.copy(songIds = songIds.distinct()))
    }

    fun addSongsToGenre(genreId: String, songIds: List<String>) {
        val genre = getGenre(genreId) ?: return
        saveGenre(genre.copy(songIds = (genre.songIds + songIds).distinct()))
    }

    fun removeSongFromGenre(genreId: String, songId: String) {
        val genre = getGenre(genreId) ?: return
        saveGenre(genre.copy(songIds = genre.songIds.filter { it != songId }))
    }

    private fun persistGenres() {
        val store = prefs() ?: return
        store.edit().putString(KEY_GENRES, gson.toJson(genresCache)).apply()
    }
}