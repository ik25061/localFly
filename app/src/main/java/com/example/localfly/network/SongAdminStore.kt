package com.example.localfly.network

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
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
     * Compatibilidad con el estado antiguo: true significa que el usuario
     * decidió conservarla. La decisión actual se representa también mediante
     * markedForDeletion, que separa dislike de eliminación.
     */
    val kept: Boolean = false,
    val keptAtMs: Long = 0L,
    /** true solo cuando el usuario ha decidido explícitamente ponerla para eliminar. */
    val markedForDeletion: Boolean = false,
    /** Cuenta que solicitó el borrado diferido; evita ejecutar órdenes con otro usuario. */
    val ownerUserId: String? = null
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
    private const val KEY_LEGACY_OWNER = "claimed_by_user_id"
    private const val KEY_EDITS = "edits"
    private const val KEY_DISLIKED = "disliked"
    private const val KEY_GENRES = "genres"
    private const val KEY_PENDING_SYNC = "pending_sync_ids"
    private const val KEY_DELETED = "deleted_song_ids"
    private const val KEY_PENDING_DELETIONS = "pending_song_deletions"

    private var context: Context? = null
    private var prefsName: String = "${PREFS_NAME}_guest"

    private val gson = Gson()

    // Cachés en memoria para que applyTo() funcione sin depender del contexto
    // (los adaptadores se construyen antes y applyTo solo necesita el último
    // estado conocido). ensureContext() recarga desde disco.
    private var editsCache: Map<String, SongEdit> = emptyMap()
    private var dislikedCache: List<DislikedSong> = emptyList()
    private var genresCache: List<CustomGenre> = emptyList()

    // JSON del que venían las cachés anteriores (comparación por CONTENIDO
    // para saber si hace falta volver a analizarlo).
    private var editsJson: String? = null
    private var dislikedJson: String? = null
    private var genresJson: String? = null

    /** Ediciones locales aún no confirmadas por el servidor. */
    private var pendingSyncIds: Set<String> = emptySet()

    /** Ids eliminados del disco (tumba local): ya no deben reaparecer
     *  en la lista de "No me gusta" ni en la cola, aunque el servidor
     *  los siga devolviendo un tiempo en /hidden-songs o /by-ids. */
    private var deletedCache: Set<String> = emptySet()
    private var deletedJson: String? = null
    private var pendingDeletionsCache: List<DislikedSong> = emptyList()
    private var pendingDeletionsJson: String? = null

    /**
     * Separa ediciones, dislikes y colas administrativas por cuenta. La antigua
     * preferencia global no tenía propietario; se migra una sola vez a la
     * primera cuenta autenticada que abre esta versión, y después queda aislada.
     */
    fun ensureContext(appContext: Context, userId: String? = null) {
        val applicationContext = appContext.applicationContext
        val owner = userId?.takeIf { it.isNotBlank() }
        val newPrefsName = if (owner == null) {
            "${PREFS_NAME}_guest"
        } else {
            val suffix = java.security.MessageDigest.getInstance("SHA-256")
                .digest(owner.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            "${PREFS_NAME}_$suffix"
        }

        if (owner != null) migrateLegacyStore(applicationContext, newPrefsName, owner)
        val contextChanged = context !== applicationContext
        val prefsChanged = prefsName != newPrefsName
        context = applicationContext
        prefsName = newPrefsName
        if (contextChanged || prefsChanged) reload()
    }

    private fun migrateLegacyStore(appContext: Context, targetName: String, owner: String) {
        val legacy = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (legacy.getString(KEY_LEGACY_OWNER, null) != null) return

        val target = appContext.getSharedPreferences(targetName, Context.MODE_PRIVATE)
        val editor = target.edit()
        legacy.all.forEach { (key, value) ->
            if (key == KEY_LEGACY_OWNER || target.contains(key)) return@forEach
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }

        // Las solicitudes antiguas no guardaban propietario. Al migrar el
        // almacén legacy, se atribuyen a la cuenta que lo ha reclamado.
        val pendingJson = target.getString(KEY_PENDING_DELETIONS, null)
            ?: legacy.getString(KEY_PENDING_DELETIONS, null)
        if (!pendingJson.isNullOrBlank()) {
            val migrated = parsePendingDeletions(pendingJson).map { pending ->
                if (pending.ownerUserId.isNullOrBlank()) pending.copy(ownerUserId = owner) else pending
            }
            editor.putString(KEY_PENDING_DELETIONS, gson.toJson(migrated))
        }
        editor.apply()

        // Retener solo el marcador: los datos ya están en el fichero de la
        // cuenta y no deben aparecer en el almacén invitado ni en otra cuenta.
        legacy.edit().clear().putString(KEY_LEGACY_OWNER, owner).apply()
    }

    private fun prefs(): SharedPreferences? {
        val appContext = context ?: return null
        return appContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
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
        deletedJson = store.getString(KEY_DELETED, null)
        deletedCache = parseIdSet(deletedJson)
        pendingDeletionsJson = store.getString(KEY_PENDING_DELETIONS, null)
        pendingDeletionsCache = parsePendingDeletions(pendingDeletionsJson)
    }

    private fun parsePendingDeletions(json: String?): List<DislikedSong> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val type = object : TypeToken<List<DislikedSong>>() {}.type
            gson.fromJson<List<DislikedSong>>(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
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
     * OJO: comparar por referencia (`!==`) NO es fiable aquí: el mismo
     * proceso puede devolver una instancia distinta con el mismo contenido,
     * y lo que es peor, tras un `persist*()` local la instancia guardada NO
     * es la misma que la caché (`dislikedJson` quedaba apuntando al valor
     * viejo). Por eso se compara por CONTENIDO (`!=`): es O(n) en longitud
     * del JSON pero evita que una escritura local seguida de otra lectura
     * machaque la caché en memoria con el valor viejo de disco (bug: la
     * canción eliminada "reaparecía" en la lista).
     */
    private fun reloadIfChanged() {
        val store = prefs() ?: return
        val e = store.getString(KEY_EDITS, null)
        if (e != editsJson) {
            editsJson = e
            editsCache = parseEdits(e)
        }
        val d = store.getString(KEY_DISLIKED, null)
        if (d != dislikedJson) {
            dislikedJson = d
            dislikedCache = parseDisliked(d)
        }
        val g = store.getString(KEY_GENRES, null)
        if (g != genresJson) {
            genresJson = g
            genresCache = parseGenres(g)
        }
        val del = store.getString(KEY_DELETED, null)
        if (del != deletedJson) {
            deletedJson = del
            deletedCache = parseIdSet(del)
        }
        val pendingDeletes = store.getString(KEY_PENDING_DELETIONS, null)
        if (pendingDeletes != pendingDeletionsJson) {
            pendingDeletionsJson = pendingDeletes
            pendingDeletionsCache = parsePendingDeletions(pendingDeletes)
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
            // Migración compatible: antes kept=false significaba "pendiente de
            // decidir si borrar". Se conserva esa intención en las instalaciones
            // existentes; los nuevos dislikes se guardan como solo-dislike.
            JsonParser.parseString(json).asJsonArray.map { element ->
                val item = element.asJsonObject
                val song = gson.fromJson(item, DislikedSong::class.java)
                if (!item.has("markedForDeletion")) {
                    song.copy(markedForDeletion = !song.kept)
                } else {
                    song
                }
            }
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
        val json = gson.toJson(pendingSyncIds.toList())
        prefs()?.edit()?.putString(KEY_PENDING_SYNC, json)?.apply()
    }

    private fun persistEdits() {
        val store = prefs() ?: return
        val list = editsCache.values.toList().sortedByDescending { it.editedAtMs }
        val json = gson.toJson(list)
        editsJson = json
        store.edit().putString(KEY_EDITS, json).apply()
    }

    // ===================== CANCIONES "NO ME GUSTA" =====================

    fun getDislikedSongs(): List<DislikedSong> {
        if (context != null) reloadIfChanged() // Siempre recargar para asegurar datos frescos del disco
        return dislikedCache.filter { it.songId !in deletedCache }.sortedByDescending { it.dislikedAtMs }
    }

    /** True si la canción está marcada como "No me gusta" (revisada o no). */
    fun isDisliked(songId: String): Boolean {
        if (context != null) reloadIfChanged()
        if (songId in deletedCache) return false
        return dislikedCache.any { it.songId == songId }
    }

    /** True solo si el usuario la marcó expresamente para eliminar. */
    fun isPendingDislike(songId: String): Boolean {
        if (context != null) reloadIfChanged()
        if (songId in deletedCache) return false
        return dislikedCache.any { it.songId == songId && it.markedForDeletion }
    }

    /**
     * Canciones que el usuario ha marcado expresamente para eliminar. Son la
     * fuente de la cola de revisión; los dislikes normales no entran aquí.
     */
    fun getPendingDislikedSongs(): List<DislikedSong> = getDislikedSongs().filter { it.markedForDeletion }

    // ===================== COLA DE ELIMINACIÓN DIFERIDA =====================

    /** Copia persistente e independiente de la lista de dislikes. */
    internal fun filterPendingDeletionsForUser(
        pending: List<DislikedSong>,
        ownerUserId: String
    ): List<DislikedSong> = if (ownerUserId.isBlank()) {
        emptyList()
    } else {
        pending.filter { it.ownerUserId == ownerUserId }
    }

    fun getPendingSongDeletions(ownerUserId: String): List<DislikedSong> {
        if (ownerUserId.isBlank()) return emptyList()
        if (context != null) reloadIfChanged()
        return filterPendingDeletionsForUser(pendingDeletionsCache, ownerUserId)
    }

    fun isPendingSongDeletion(songId: String, ownerUserId: String): Boolean {
        if (ownerUserId.isBlank()) return false
        if (context != null) reloadIfChanged()
        return pendingDeletionsCache.any { it.songId == songId && it.ownerUserId == ownerUserId }
    }

    /** Añade de forma idempotente una eliminación solicitada sin conexión. */
    fun addPendingSongDeletion(song: DislikedSong, ownerUserId: String) {
        if (ownerUserId.isBlank() || song.songId.isBlank() || isDeleted(song.songId)) return
        if (context != null) reloadIfChanged()
        if (pendingDeletionsCache.any { it.songId == song.songId && it.ownerUserId == ownerUserId }) return
        pendingDeletionsCache = pendingDeletionsCache +
            song.copy(markedForDeletion = true, kept = false, ownerUserId = ownerUserId)
        persistPendingSongDeletions()
    }

    fun removePendingSongDeletion(songId: String, ownerUserId: String) {
        if (ownerUserId.isBlank()) return
        if (context != null) reloadIfChanged()
        val updated = pendingDeletionsCache.filterNot { it.songId == songId && it.ownerUserId == ownerUserId }
        if (updated.size == pendingDeletionsCache.size) return
        pendingDeletionsCache = updated
        persistPendingSongDeletions()
    }

    private fun persistPendingSongDeletions() {
        val json = gson.toJson(pendingDeletionsCache)
        pendingDeletionsJson = json
        prefs()?.edit()?.putString(KEY_PENDING_DELETIONS, json)?.apply()
    }

    /** El admin decide DEJAR la canción: ya la revisó y no la borrará. */
    fun markDislikedSongKept(songId: String) = setDislikedKept(songId, kept = true)

    /** Marca expresamente la canción para eliminarla; entra en la cola de revisión. */
    fun markDislikedSongPending(songId: String) = setDislikedKept(songId, kept = false)

    private fun setDislikedKept(songId: String, kept: Boolean) {
        if (context != null) reloadIfChanged()
        if (songId in deletedCache) return
        val index = dislikedCache.indexOfFirst { it.songId == songId }
        if (index == -1) return
        val current = dislikedCache[index]
        if (current.kept == kept && current.markedForDeletion == !kept) return
        val updated = current.copy(
            kept = kept,
            keptAtMs = if (kept) System.currentTimeMillis() else 0L,
            markedForDeletion = !kept
        )
        dislikedCache = dislikedCache.toMutableList().also { it[index] = updated }
        persistDisliked()
    }

    /** Registra la canción marcada como "No me gusta" para revisión del admin. */
    fun recordDislikedSong(song: Song) {
        if (song.id.isBlank()) return
        if (context != null) reloadIfChanged()
        // Una canción eliminada del disco no puede volver a la lista
        // (el servidor puede seguir devolviéndola un tiempo en hidden/by-ids).
        if (song.id in deletedCache) return

        Log.d("SongAdminStore", "Recording dislike for: ${song.title} (${song.id})")

        // Un dislike no significa que el usuario quiera borrar el archivo.
        // Si ya existe, respetar la decisión explícita de conservarlo o
        // marcarlo para eliminar; repetir la acción no cambia esa decisión.
        if (dislikedCache.any { it.songId == song.id }) return
        
        dislikedCache = dislikedCache + DislikedSong(
            songId = song.id,
            title = song.title,
            artist = song.artist,
            album = song.album,
            year = song.year,
            dislikedAtMs = System.currentTimeMillis(),
            kept = true,
            markedForDeletion = false
        )
        persistDisliked()
    }

    fun removeDislikedSong(songId: String) {
        if (context != null) reloadIfChanged()
        if (dislikedCache.none { it.songId == songId }) return
        dislikedCache = dislikedCache.filter { it.songId != songId }
        persistDisliked()
    }

    fun clearDislikedSongs() {
        if (context != null) reloadIfChanged()
        if (dislikedCache.isEmpty()) return
        dislikedCache = emptyList()
        persistDisliked()
    }

    private fun persistDisliked() {
        val store = prefs() ?: return
        val json = gson.toJson(dislikedCache)
        dislikedJson = json
        store.edit().putString(KEY_DISLIKED, json).apply()
    }

    // ===================== CANCIONES ELIMINADAS DEL DISCO =====================

    /** Marca un id como eliminado: no reaparece en "No me gusta" ni en colas. */
    fun markSongDeleted(songId: String) {
        if (songId.isBlank()) return
        if (context != null) reloadIfChanged()
        dislikedCache = dislikedCache.filter { it.songId != songId }
        val jsonDisliked = gson.toJson(dislikedCache)
        dislikedJson = jsonDisliked
        deletedCache = deletedCache + songId
        val jsonDeleted = gson.toJson(deletedCache.toList())
        deletedJson = jsonDeleted
        pendingDeletionsCache = pendingDeletionsCache.filterNot { it.songId == songId }
        val jsonPendingDeletions = gson.toJson(pendingDeletionsCache)
        pendingDeletionsJson = jsonPendingDeletions
        val store = prefs() ?: return
        store.edit()
            .putString(KEY_DISLIKED, jsonDisliked)
            .putString(KEY_DELETED, jsonDeleted)
            .putString(KEY_PENDING_DELETIONS, jsonPendingDeletions)
            .apply()
        // Las ediciones/pendientes de una canción que ya no existe estorban.
        removeEdit(songId)
        removeFromPendingSync(songId)
    }

    /** True si la canción se eliminó del disco (no debe sonar ni listarse). */
    fun isDeleted(songId: String): Boolean {
        if (context != null) reloadIfChanged()
        return songId in deletedCache
    }

    /** Limpia la tumba local (p. ej. tras reimportar la biblioteca). */
    fun clearDeletedSongs() {
        if (context != null) reloadIfChanged()
        if (deletedCache.isEmpty()) return
        deletedCache = emptySet()
        val json = gson.toJson(emptyList<String>())
        deletedJson = json
        prefs()?.edit()?.putString(KEY_DELETED, json)?.apply()
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
        val json = gson.toJson(genresCache)
        genresJson = json
        store.edit().putString(KEY_GENRES, json).apply()
    }
}
