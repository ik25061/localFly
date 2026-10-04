package com.example.localfly.karaoke

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Clave de caché del instrumental: fichero estable por canción
 * (`karaoke/<songId>.audio`), independiente de la IP/puerto del servidor.
 * Las versiones anteriores usaban el hash SHA-256 de la URL completa
 * (incluía IP/puerto): [deleteFor] sigue borrando esos ficheros legacy.
 */
object KaraokeCache {
    private const val PREFS = "localfly_karaoke_index"
    private const val KEY_FILES = "files_by_song"

    fun fileFor(context: android.content.Context, baseUrl: String, songId: String): File {
        val appContext = context.applicationContext
        val file = stableFileFor(appContext, songId)
        if (file.isFile && file.length() > 0) {
            // Camino caliente (reproducir): solo registrar el nombre si falta,
            // sin reescribir prefs en cada reproducción.
            try {
                val index = readIndex(appContext)
                val names = index[songId]
                if (names == null || file.name !in names) remember(appContext, songId, file.name)
            } catch (_: Exception) { }
            return file
        }
        // Migración perezosa: si existe el hash legacy con esta base URL y el
        // estable aún no, renombrarlo para no descargar dos veces.
        try {
            val legacy = legacyFileFor(appContext, baseUrl, songId)
            if (legacy != null && legacy.isFile && legacy.length() > 0 &&
                legacy.absolutePath != file.absolutePath
            ) {
                if (legacy.renameTo(file)) {
                    // El índice apuntaba al hash legacy: actualizarlo.
                    val index = readIndex(appContext)
                    val names = index[songId]
                    if (names != null && names.remove(legacy.name)) {
                        names.add(file.name)
                        writeIndex(appContext, index)
                    }
                }
            }
        } catch (_: Exception) { }
        // Índice songId -> nombre de fichero (migración desde el esquema
        // legacy con hash de URL; también cubre ids saneados).
        remember(appContext, songId, file.name)
        return file
    }

    private fun stableFileFor(context: android.content.Context, songId: String): File {
        // Nombre estable: no depende de la URL base, así el fichero siempre
        // se encuentra para reproducir y para borrar aunque cambie la IP.
        // No crear la carpeta aquí: fileFor/deleteFor deciden si toca mkdirs
        // (evita crear karaoke/ vacía solo por consultar o borrar).
        val safeId = songId.replace(Regex("[^A-Za-z0-9_-]"), "_").takeIf { it.isNotBlank() } ?: "song"
        return File(File(context.filesDir, "karaoke"), "$safeId.audio")
    }

    /** Fichero legacy: hash SHA-256 de la URL GET /karaoke/{songId}. */
    private fun legacyFileFor(context: android.content.Context, baseUrl: String, songId: String): File? {
        return try {
            val url = baseUrl.toHttpUrl().newBuilder().addPathSegment("karaoke").addPathSegment(songId).build()
            val key = MessageDigest.getInstance("SHA-256").digest(url.toString().toByteArray())
                .joinToString("") { "%02x".format(it) }
            File(File(context.filesDir, "karaoke"), "$key.audio")
        } catch (_: Exception) { null }
    }

    private fun prefs(context: android.content.Context) =
        context.applicationContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)

    private fun readIndex(context: android.content.Context): MutableMap<String, MutableSet<String>> {
        val raw = prefs(context).getString(KEY_FILES, null) ?: return mutableMapOf()
        return try {
            val type = object : com.google.gson.reflect.TypeToken<Map<String, List<String>>>() {}.type
            val parsed: Map<String, List<String>> =
                com.google.gson.Gson().fromJson(raw, type) ?: emptyMap()
            parsed.mapValues { it.value.toMutableSet() }.toMutableMap()
        } catch (_: Exception) { mutableMapOf() }
    }

    private fun writeIndex(context: android.content.Context, index: Map<String, Set<String>>) {
        val serializable = index.mapValues { it.value.toList() }
        prefs(context).edit()
            .putString(KEY_FILES, com.google.gson.Gson().toJson(serializable))
            .apply()
    }

    private fun remember(context: android.content.Context, songId: String, fileName: String) {
        try {
            val index = readIndex(context)
            val names = index.getOrPut(songId) { mutableSetOf() }
            if (names.add(fileName)) writeIndex(context, index)
        } catch (_: Exception) { }
    }

    /**
     * Borra el instrumental cacheado de una canción, si existe.
     * Borrado dirigido (solo esta canción): fichero estable actual,
     * ficheros del índice y hash legacy con la URL base actual.
     * No se barren hashes de otras canciones: un hash legacy con una IP
     * antigua no se puede atribuir a ningún songId sin adivinar, así que
     * esos huérfanos pre-índice se migran en [fileFor] al reproducir.
     */
    fun deleteFor(context: android.content.Context, songId: String): Boolean {
        var deleted = false
        try {
            val appContext = context.applicationContext
            val dir = File(appContext.filesDir, "karaoke")
            if (!dir.isDirectory) return false
            // 0. Fichero estable actual (<safeSongId>.audio).
            try {
                val stable = stableFileFor(appContext, songId)
                if (stable.delete()) deleted = true
                // Limpiar la entrada del índice si solo contenía ese nombre.
                val refreshed0 = readIndex(appContext)
                val remaining = refreshed0[songId]?.filter { it != stable.name }
                if (remaining.isNullOrEmpty()) {
                    if (refreshed0.remove(songId) != null) writeIndex(appContext, refreshed0)
                } else if (remaining.toSet() != refreshed0[songId]) {
                    refreshed0[songId] = remaining.toMutableSet()
                    writeIndex(appContext, refreshed0)
                }
            } catch (_: Exception) { }
            // 1. Ficheros registrados en el índice para este songId.
            val index = readIndex(appContext)
            val names = index.remove(songId)?.toList().orEmpty()
            if (names.isNotEmpty()) writeIndex(appContext, index)
            names.forEach { name ->
                // Solo nombres seguros dentro de la carpeta karaoke.
                if (name.isBlank() || name.contains("/") || name.contains("\\") || name.contains("..")) return@forEach
                if (File(dir, name).delete()) deleted = true
            }
            // 2. Hash legacy con la URL base actual (caso normal pre-migración).
            try {
                val baseUrl = com.example.localfly.network.RetrofitClient.getBaseUrl()
                val legacy = legacyFileFor(appContext, baseUrl, songId)
                if (legacy != null && legacy.absolutePath != stableFileFor(appContext, songId).absolutePath &&
                    legacy.delete()
                ) deleted = true
            } catch (_: Exception) { }
        } catch (_: Exception) { }
        return deleted
    }

    /** Limpia instrumentales huérfanos de canciones ya eliminadas. */
    fun deleteAllFor(context: android.content.Context, songIds: Collection<String>): Int {
        var count = 0
        songIds.forEach { if (deleteFor(context, it)) count++ }
        return count
    }
}

/** Cache independiente: nunca sobrescribe ni elimina la descarga original. */
class KaraokeRepository(context: Context) {
    private val appContext = context.applicationContext
    private val directory = File(appContext.filesDir, "karaoke")
    private val client = OkHttpClient.Builder().callTimeout(3, TimeUnit.MINUTES).build()

    suspend fun instrumental(baseUrl: String, songId: String): File = withContext(Dispatchers.IO) {
        directory.mkdirs()
        val target = KaraokeCache.fileFor(appContext, baseUrl, songId)
        if (target.isFile && target.length() > 0) return@withContext target
        val url = baseUrl.toHttpUrl().newBuilder().addPathSegment("karaoke").addPathSegment(songId).build()
        val temporary = File.createTempFile("karaoke-", ".part", directory)
        try {
            coroutineScope {
                val call = client.newCall(Request.Builder().url(url).build())
                // Cancels blocking OkHttp reads too, including while headers are pending.
                val cancellation = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { call.cancel() }
                }
                try {
                    call.execute().use { response ->
                        ensureActive()
                        if (response.code == 404) throw IOException("Esta canción aún no tiene instrumental en el servidor")
                        if (!response.isSuccessful) throw IOException("Servidor: HTTP ${response.code}")
                        val body = response.body ?: throw IOException("Instrumental vacío")
                        if (body.contentType()?.type == "text" || body.contentType()?.subtype == "json")
                            throw IOException("El servidor no devolvió audio")
                        body.byteStream().use { input -> temporary.outputStream().use { output ->
                            val buffer = ByteArray(32768)
                            while (true) {
                                ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                            }
                        } }
                        if (temporary.length() == 0L || (body.contentLength() >= 0 && body.contentLength() != temporary.length()))
                            throw IOException("Descarga incompleta")
                    }
                    ensureActive()
                    if (!temporary.renameTo(target)) throw IOException("No se pudo guardar el instrumental")
                    target
                } finally { cancellation.cancel() }
            }
        } finally { temporary.delete() }
    }
}
