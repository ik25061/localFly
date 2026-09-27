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
    val artist: String? = null,
    val album: String? = null,
    val genres: List<String> = emptyList(),
    val year: Int? = null,
    val mood: String? = null,
    val moods: List<String> = emptyList(),
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
    val dislikedAtMs: Long = 0L,
    /**
     * `true` = el administrador YA la revisó y decidió DEJARLA (no borrarla).
     * `false` = pendiente: en la lista de "No me gusta" se muestra con el
     * recuadro en rojo oscuro y entra en la cola de revisión de reproducción.
     */
    val kept: Boolean = false,
    val keptAtMs: Long = 0L
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
    private const val KEY_PENDING_SYNC = "pending_sync_ids"

    private var context: Context? = null

    private val gson = Gson()

    // Cachés en memoria para que applyTo() funcione sin depender del contexto
    // (los adaptadores se construyen antes y applyTo solo necesita el último
    // estado conocido). ensureContext() recarga desde disco.
    private var editsCache: Map<String, SongEdit> = emptyMap()
    private var dislikedCache: List<DislikedSong> = emptyList()
    private var genresCache: List<CustomGenre> = emptyList()

    // JSON del que venían las cachés anteriores (comparación por referencia
    // para saber si hace falta volver a analizarlo).
    private var editsJson: String? = null
    private var dislikedJson: String? = null
    private var genresJson: String? = null

    /** Ediciones locales aún no confirmadas por el servidor. */
    private var pendingSyncIds: Set<String> = emptySet()

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
        editsJson = store.getString(KEY_EDITS, null)
        dislikedJson = store.getString(KEY_DISLIKED, null)
        genresJson = store.getString(KEY_GENRES, null)
        editsCache = parseEdits(editsJson)
        dislikedCache = parseDisliked(dislikedJson)
        genresCache = parseGenres(genresJson)
        pendingSyncIds = parseIdSet(store.getString(KEY_PENDING_SYNC, null))
    }

    private fun parseIdSet(json: String?): Set<String> {
        if (json.isNullOrBlank()) return emptySet()
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            val list: List<String> = gson.fromJson(json, type) ?: emptyList()
            list.toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    /**
     * Rerecarga SOLO si el JSON persistido cambió desde la última lectura.
     *
     * `SharedPreferences` devuelve siempre la misma instancia del String
     * guardado, así que basta comparar por referencia (O(1)). Antes se
     * reanalizaba el JSON completo en cada llamada, y como `isPendingDislike()`
     * se ejecuta fila a fila al pintar la cola, eso obligaba a decodificar
     * toda la lista roja en cada repintado (tirones en la UI / Bluetooth).
     */
    private fun reloadIfChanged() {
        val store = prefs() ?: return
        val e = store.getString(KEY_EDITS, null)
        if (e !== editsJson) {
            editsJson = e
            editsCache = parseEdits(e)
        }
        val d = store.getString(KEY_DISLIKED, null)
        if (d !== dislikedJson) {
            dislikedJson = d
            dislikedCache = parseDisliked(d)
        }
        val g = store.getString(KEY_GENRES, null)
        if (g !== genresJson) {
            genresJson = g
            genresCache = parseGenres(g)
        }
    }

    private fun parseEdits(json: String?): Map<String, SongEdit> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val type = object : TypeToken<List<SongEdit>>() {}.type
            val list: List<SongEdit> = gson.fromJson(json, type) ?: emptyList()
            list.associate { it.songId to it }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun parseDisliked(json: String?): List<DislikedSong> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val type = object : TypeToken<List<DislikedSong>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseGenres(json: String?): List<CustomGenre> {
        if (json.isNullOrBlank()) return emptyList()
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
            artist = edit.artist ?: song.artist,
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

    /** Estados de ánimo editados para una canción (lista de nombres). */
    fun getEditedMoods(songId: String): List<String> = editsCache[songId]?.moods ?: emptyList()

    fun saveEdit(edit: SongEdit, markPendingSync: Boolean = true) {
        if (edit.title.isNullOrBlank() && edit.artist.isNullOrBlank() && edit.album.isNullOrBlank() &&
            edit.genres.isEmpty() && edit.year == null && edit.mood.isNullOrBlank() &&
            edit.moods.isEmpty()
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
        // Solo las ediciones hechas por el usuario esperan subirse al servidor.
        // La sincronización reescribe los "originales" con markPendingSync=false
        // para no crearse a sí misma más trabajo (bucle infinito de sync).
        if (markPendingSync) addToPendingSync(updated.songId)
    }

    fun removeEdit(songId: String) {
        if (!editsCache.containsKey(songId)) return
        val map = editsCache.toMutableMap()
        map.remove(songId)
        editsCache = map
        persistEdits()
        removeFromPendingSync(songId)
    }

    // ===== Ediciones pendientes de subir al servidor =====
    //
    // Antes se reenviaban TODAS las ediciones cada 30 s desde PlaybackService,
    // aunque ya estuvieran confirmadas: peticiones constantes que además
    // provocaban un bucle de sincronización interminable.

    /** Ids de ediciones que todavía no ha confirmado el servidor. */
    fun getPendingSyncIds(): Set<String> =
        pendingSyncIds.filter { editsCache.containsKey(it) }.toSet()

    /** El servidor ya aplicó estas ediciones: dejan de estar pendientes. */
    fun markEditsSynced(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val remaining = pendingSyncIds.filter { it !in ids }.toMutableSet()
        if (remaining == pendingSyncIds) return
        pendingSyncIds = remaining
        persistPendingSync()
    }

    private fun addToPendingSync(songId: String) {
        if (songId in pendingSyncIds) return
        pendingSyncIds = pendingSyncIds + songId
        persistPendingSync()
    }

    private fun removeFromPendingSync(songId: String) {
        if (songId !in pendingSyncIds) return
        pendingSyncIds = pendingSyncIds - songId
        persistPendingSync()
    }

    private fun persistPendingSync() {
        prefs()?.edit()?.putString(KEY_PENDING_SYNC, gson.toJson(pendingSyncIds.toList()))?.apply()
    }

    private fun persistEdits() {
        val store = prefs() ?: return
        val list = editsCache.values.toList().sortedByDescending { it.editedAtMs }
        store.edit().putString(KEY_EDITS, gson.toJson(list)).apply()
    }

    // ===================== CANCIONES "NO ME GUSTA" =====================

    fun getDislikedSongs(): List<DislikedSong> {
        if (context != null) reloadIfChanged() // Siempre recargar para asegurar datos frescos del disco
        return dislikedCache.sortedByDescending { it.dislikedAtMs }
    }

    /** True si la canción está marcada como "No me gusta" (revisada o no). */
    fun isDisliked(songId: String): Boolean {
        if (context != null) reloadIfChanged()
        return dislikedCache.any { it.songId == songId }
    }

    /** True si está marcada y AÚN NO se ha revisado (pendiente de borrar/dejar). */
    fun isPendingDislike(songId: String): Boolean {
        if (context != null) reloadIfChanged()
        return dislikedCache.any { it.songId == songId && !it.kept }
    }

    /**
     * Canciones marcadas PENDIENTES de revisión (el admin aún no decidió si las
     * borra o las deja). Es la fuente de la "cola de revisión": cuando suena una
     * de ellas, la lista de reproducción pasa a estar formada solo por estas,
     * para poder gestionarlas una detrás de otra.
     */
    fun getPendingDislikedSongs(): List<DislikedSong> = getDislikedSongs().filter { !it.kept }

    /** El admin decide DEJAR la canción: ya la revisó y no la borrará. */
    fun markDislikedSongKept(songId: String) = setDislikedKept(songId, kept = true)

    /** Devuelve la canción al estado pendiente (volver a decidir sobre ella). */
    fun markDislikedSongPending(songId: String) = setDislikedKept(songId, kept = false)

    private fun setDislikedKept(songId: String, kept: Boolean) {
        if (context != null) reloadIfChanged()
        val index = dislikedCache.indexOfFirst { it.songId == songId }
        if (index == -1) return
        val current = dislikedCache[index]
        if (current.kept == kept) return
        val updated = current.copy(
            kept = kept,
            keptAtMs = if (kept) System.currentTimeMillis() else 0L
        )
        dislikedCache = dislikedCache.toMutableList().also { it[index] = updated }
        persistDisliked()
    }

    /** Registra la canción marcada como "No me gusta" para revisión del admin. */
    fun recordDislikedSong(song: Song) {
        if (song.id.isBlank()) return
        if (context != null) reloadIfChanged()
        
        Log.d("SongAdminStore", "Recording dislike for: ${song.title} (${song.id})")

        // Si ya estaba marcada: un nuevo dislike la devuelve a PENDIENTE (si el
        // admin la había "dejado", vuelve a la lista roja y a la cola de revisión).
        val existingIndex = dislikedCache.indexOfFirst { it.songId == song.id }
        if (existingIndex != -1) {
            val existing = dislikedCache[existingIndex]
            if (existing.kept) {
                dislikedCache = dislikedCache.toMutableList().also {
                    it[existingIndex] = existing.copy(
                        kept = false,
                        keptAtMs = 0L,
                        dislikedAtMs = System.currentTimeMillis()
                    )
                }
                persistDisliked()
            }
            return
        }
        
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

    fun removeSongsFromGenre(genreId: String, songIdsToRemove: Set<String>) {
        val genre = getGenre(genreId) ?: return
        saveGenre(genre.copy(songIds = genre.songIds.filter { it !in songIdsToRemove }))
    }

    private fun persistGenres() {
        val store = prefs() ?: return
        store.edit().putString(KEY_GENRES, gson.toJson(genresCache)).apply()
    }
}
