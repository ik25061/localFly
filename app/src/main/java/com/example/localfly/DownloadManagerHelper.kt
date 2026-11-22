
package com.example.localfly

import android.content.Context
import com.example.localfly.ai.AIWeightsStore
import com.example.localfly.network.Song
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class DownloadProgress(
    val isDownloading: Boolean = false,
    val current: Int = 0,
    val total: Int = 0,
    val songTitle: String = ""
)

/**
 * Representa una canción ya descargada al almacenamiento del teléfono.
 */
data class DownloadedSong(
    val id: String,
    val title: String,
    val artist: String?,
    val filePath: String,
    val duration: Double? = null,
    val bpm: Double? = null,
    val key: String? = null,
    val hasCover: Boolean = false,
    val hasLyrics: Boolean = false,
    val hasKaraoke: Boolean = false,
    val liked: Boolean = false,
    val subtitleUrl: String? = null,
    val isEpisode: Boolean = false,
    val fileSize: Long = 0,
    val genre: List<String> = emptyList(),
    // Se guardan para poder precargar el editor de metadatos sin conexión.
    val album: String? = null,
    val year: Int? = null
)

/**
 * Gestiona la descarga, listado y borrado de canciones offline.
 *
 * Es un singleton ([getInstance]) para que todas las pantallas compartan la
 * misma instancia y no se cree un [OkHttpClient] nuevo en cada uso.
 */
class DownloadManagerHelper private constructor(context: Context) {

    companion object {
        @Volatile
        private var instance: DownloadManagerHelper? = null

        fun getInstance(context: Context): DownloadManagerHelper {
            val appContext = context.applicationContext
            return instance ?: synchronized(this) {
                instance ?: DownloadManagerHelper(appContext).also { instance = it }
            }
        }

        private val _downloadProgress = MutableStateFlow(DownloadProgress())
        val downloadProgress: StateFlow<DownloadProgress> = _downloadProgress
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("localfly_downloads", Context.MODE_PRIVATE)
    private val gson = Gson()

    @Volatile private var cachedDownloads: List<DownloadedSong>? = null
        set(value) {
            field = value
            cachedDownloadById = value?.associateBy { it.id }
        }
    @Volatile private var cachedDownloadById: Map<String, DownloadedSong>? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun downloadsDir(): File {
        val dir = File(appContext.filesDir, "downloads")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Carga el índice JSON una sola vez. No valida todos los archivos aquí:
     * getLocalFilePath() se usa desde el hilo del reproductor y debe consultar
     * únicamente la canción solicitada, no recorrer cientos de descargas.
     */
    @Synchronized
    private fun loadDownloadIndex(): List<DownloadedSong> {
        cachedDownloads?.let { return it }
        val json = prefs.getString("list", null) ?: return emptyList()
        val type = object : TypeToken<List<DownloadedSong>>() {}.type
        val loaded = try {
            gson.fromJson<List<DownloadedSong>>(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        cachedDownloads = loaded
        return loaded
    }

    /**
     * Devuelve el índice sin recorrer todos los archivos. Esta función se usa
     * desde listas y pantallas; validar cientos de rutas aquí en el hilo UI
     * provocaba bloqueos al abrir Descargas o iniciar una cola offline.
     */
    @Synchronized
    fun getDownloadedSongs(): List<DownloadedSong> = loadDownloadIndex()

    /** Indica si el índice ya está cargado en memoria. */
    fun isDownloadIndexLoaded(): Boolean = cachedDownloadById != null

    /** Carga el JSON del índice en IO antes de resolver una cola offline. */
    suspend fun warmDownloadIndex() = withContext(Dispatchers.IO) {
        synchronized(this@DownloadManagerHelper) { loadDownloadIndex() }
    }

    /**
     * Comprueba y elimina entradas cuyo archivo ya no existe. La exploración
     * completa se hace fuera del lock: getLocalFilePath() no debe esperar a que
     * se validen cientos/miles de descargas desde otra corrutina.
     */
    suspend fun pruneMissingDownloads() = withContext(Dispatchers.IO) {
        val snapshot = synchronized(this@DownloadManagerHelper) {
            loadDownloadIndex().toList()
        }
        val snapshotIds = snapshot.map { it.id }.toSet()
        val validIds = snapshot.filter { song ->
            val file = File(song.filePath)
            file.isFile && file.length() > 0L
        }.map { it.id }.toSet()

        synchronized(this@DownloadManagerHelper) {
            val current = loadDownloadIndex()
            // Mantener descargas añadidas mientras se comprobaba el snapshot.
            val valid = current.filter { it.id !in snapshotIds || it.id in validIds }
            if (valid.size != current.size) {
                cachedDownloads = valid
                prefs.edit().putString("list", gson.toJson(valid)).apply()
            }
        }
    }

    fun isDownloaded(songId: String): Boolean = getLocalFilePath(songId) != null

    @Synchronized
    fun getLocalFilePath(songId: String): String? {
        // Búsqueda O(1) en el índice en memoria + una sola comprobación del
        // archivo solicitado. No llamar a getDownloadedSongs(): esa función
        // valida todos los archivos y provocaba un recorrido completo por cada
        // canción al construir una cola offline (O(canciones × descargas)).
        loadDownloadIndex()
        val song = cachedDownloadById?.get(songId) ?: return null
        val file = File(song.filePath)
        return if (file.isFile && file.length() > 0L) file.absolutePath else null
    }

    /**
     * Descarga el audio de la canción. Debe llamarse desde una corrutina
     * (usa Dispatchers.IO internamente, así que no bloquea la interfaz).
     *
     * Devuelve `true` solo si el archivo se descargó y guardó correctamente.
     * Si el servidor responde con error o con un cuerpo vacío/corrupto,
     * devuelve `false` y no deja ningún archivo a medias ni lo registra
     * como descargado.
     */
    suspend fun download(song: Song, audioUrl: String): Boolean = withContext(Dispatchers.IO) {
        var file: File? = null
        try {
            // .use() cierra siempre la respuesta (y su body), evitando fugas
            // de conexión cuando se hacen varias descargas.
            httpClient.newCall(Request.Builder().url(audioUrl).build()).execute().use { response ->
                if (!response.isSuccessful) return@withContext false

                val body = response.body ?: return@withContext false

                // El servidor sirve tanto MP3 como M4A; guardamos con la
                // extensión real según el Content-Type para que ExoPlayer
                // lo reconozca siempre sin problemas.
                val ext = extensionFor(body.contentType()?.subtype)
                file = File(downloadsDir(), "${song.id}.$ext")

                body.byteStream().use { input ->
                    FileOutputStream(file!!).use { output ->
                        input.copyTo(output)
                    }
                }

                // Un audio correcto nunca debe tener 0 bytes; si lo tiene,
                // borramos el archivo y tratamos la descarga como fallida.
                if (file!!.length() <= 0L) {
                    file!!.delete()
                    return@withContext false
                }

                // Intentar descargar letra siempre (respaldo offline)
                downloadLyrics(song)
                val hasLyricsNow = song.hasLyrics || File(downloadsDir(), "${song.id}.lrc").exists()

                synchronized(this@DownloadManagerHelper) {
                    val current = getDownloadedSongs().toMutableList()
                    current.removeAll { it.id == song.id }
                    current.add(
                        DownloadedSong(
                            id = song.id,
                            title = song.title,
                            artist = song.artist,
                            filePath = file!!.absolutePath,
                            duration = song.duration,
                            bpm = song.bpm,
                            key = song.key,
                            hasCover = song.hasCover,
                            hasLyrics = hasLyricsNow,
                            hasKaraoke = song.hasKaraoke,
                            liked = song.liked,
                            fileSize = file!!.length(),
                            genre = song.genre ?: emptyList(),
                            album = song.album,
                            year = song.year
                        )
                    )
                    val newList = current.toList()
                    cachedDownloads = newList
                    prefs.edit().putString("list", gson.toJson(newList)).apply()
                }

                true
            }
        } catch (e: Exception) {
            // Si algo falla a mitad de la escritura, no dejar un archivo
            // a medias que luego no se pueda reproducir.
            try {
                file?.delete()
            } catch (_: Exception) {
            }
            false
        }
    }

    /** Traduce el Content-Type del servidor a una extensión de archivo. */
    private fun extensionFor(subtype: String?): String = when (subtype?.trim()?.lowercase()) {
        "mp3", "mpeg", "mpga" -> "mp3"
        "mp4", "m4a", "x-m4a", "x-mp4", "aac" -> "m4a"
        "flac", "x-flac" -> "flac"
        "ogg", "opus" -> "ogg"
        "wav", "wave", "x-wav", "vnd.wave" -> "wav"
        "webm" -> "webm"
        else -> "mp3"
    }

    private suspend fun downloadLyrics(song: Song) {
        val lrcFile = File(downloadsDir(), "${song.id}.lrc")
        
        // 1. Intentar obtener del servidor vía API (puede traer sincronizada)
        try {
            val response = com.example.localfly.network.RetrofitClient.api.getLyrics(song.id)
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                // Preferimos la sincronizada si el servidor la devuelve como texto LRC
                // El servidor guarda en synced_text el formato [mm:ss.xx]
                // Nota: El endpoint /api/lyrics/:id en el servidor actual devuelve 'lyrics' (plain) 
                // y 'syncedLines' (JSON). Para guardar un .lrc físico, necesitaríamos el RAW.
                // Como respaldo, si hay plain lyrics, las guardamos.
                val lyricsContent = body.lyrics
                if (!lyricsContent.isNullOrBlank()) {
                    lrcFile.writeText(lyricsContent)
                    return
                }
            }
        } catch (e: Exception) { }

        // 2. Intentar buscar directo en LRCLIB (internet)
        try {
            val lrclibResponse = com.example.localfly.network.LrclibClient.api.getLyrics(song.title, song.artist)
            if (lrclibResponse.isSuccessful && lrclibResponse.body() != null) {
                val result = lrclibResponse.body()!!
                val content = result.syncedLyrics ?: result.plainLyrics
                if (!content.isNullOrBlank()) {
                    lrcFile.writeText(content)
                    return
                }
            }
        } catch (e: Exception) { }

        // 3. Fallback: buscar en /resources/ del servidor (patrón antiguo)
        val baseUrl = com.example.localfly.network.RetrofitClient.getBaseUrl()
        val variants = listOf(
            song.title,
            "${song.artist} - ${song.title}",
            song.title.lowercase(java.util.Locale.getDefault()),
            "${song.artist?.lowercase(java.util.Locale.getDefault())} - ${song.title.lowercase(java.util.Locale.getDefault())}"
        ).distinct()

        for (variant in variants) {
            try {
                val encoded = java.net.URLEncoder.encode(variant, "UTF-8").replace("+", "%20")
                val url = "$baseUrl/resources/$encoded.lrc"
                val request = Request.Builder().url(url).build()

                httpClient.newCall(request).execute().use { response ->
                    val contentType = response.header("Content-Type")
                    if (response.isSuccessful && contentType?.contains("text/html") == false) {
                        val body = response.body ?: return@use
                        val content = body.string()
                        if (content.isNotBlank() && !content.trim().startsWith("<")) {
                            lrcFile.writeText(content)
                            return
                        }
                    }
                }
            } catch (e: Exception) { }
        }
    }

    @Synchronized
    fun removeDownload(songId: String) {
        val current = getDownloadedSongs().toMutableList()
        val target = current.find { it.id == songId }
        target?.let {
            runCatching { File(it.filePath).delete() }
        }
        // La letra y el instrumental son artefactos locales de la canción:
        // se borran siempre, esté o no registrada como descarga (p. ej. la
        // letra pudo guardarse desde el editor sin descargar el audio).
        // La subida pendiente NO se toca aquí: al quitar una descarga la
        // canción sigue existiendo en el servidor y la letra debe subir igual.
        deleteLyricsAndKaraoke(songId)
        current.removeAll { it.id == songId }
        val newList = current.toList()
        cachedDownloads = newList
        prefs.edit().putString("list", gson.toJson(newList)).apply()
    }

    /**
     * Borra TODOS los artefactos locales de una canción eliminada del
     * servidor, haya o no descarga registrada: audio descargado (si lo hay),
     * letra (downloads/<id>.lrc y lyrics/<id>.lrc), instrumental de karaoke
     * cacheado y subida de letra pendiente (ya no tiene destino).
     * Usar al eliminar la canción del servidor para no dejar huérfanos.
     */
    @Synchronized
    fun removeLocalArtifacts(songId: String) {
        val current = getDownloadedSongs().toMutableList()
        current.find { it.id == songId }?.let {
            runCatching { File(it.filePath).delete() }
        }
        current.removeAll { it.id == songId }
        cachedDownloads = current.toList()
        prefs.edit().putString("list", gson.toJson(current.toList())).apply()
        deleteLyricsAndKaraoke(songId)
        // La canción ya no existe: su letra pendiente nunca podrá subirse.
        runCatching {
            com.example.localfly.network.SessionManager(appContext)
                .removePendingLyricsUpload(songId)
        }
    }

    /** Borra letra local (ambas copias) e instrumental de karaoke. */
    private fun deleteLyricsAndKaraoke(songId: String) {
        val downloadsDir = File(appContext.filesDir, "downloads")
        if (downloadsDir.isDirectory) {
            runCatching { File(downloadsDir, "$songId.lrc").delete() }
        }
        val lyricsDir = File(appContext.filesDir, "lyrics")
        if (lyricsDir.isDirectory) {
            runCatching { File(lyricsDir, "$songId.lrc").delete() }
        }
        runCatching { com.example.localfly.karaoke.KaraokeCache.deleteFor(appContext, songId) }
    }

    /** Elimina todas las canciones descargadas (archivos y registro). */
    fun removeAllDownloads() {
        val snapshot = synchronized(this) { getDownloadedSongs().toList() }
        snapshot.forEach { song ->
            runCatching { File(song.filePath).delete() }
            deleteLyricsAndKaraoke(song.id)
        }
        synchronized(this) {
            val removedIds = snapshot.map { it.id }.toSet()
            val remaining = getDownloadedSongs().filter { it.id !in removedIds }
            cachedDownloads = remaining
            prefs.edit().putString("list", gson.toJson(remaining)).apply()
        }
    }

    /** Actualiza el estado "me gusta" guardado de una canción descargada (si existe). */
    @Synchronized
    fun updateLiked(songId: String, liked: Boolean) {
        val current = getDownloadedSongs().toMutableList()
        val idx = current.indexOfFirst { it.id == songId }
        if (idx == -1) return
        current[idx] = current[idx].copy(liked = liked)
        val newList = current.toList()
        cachedDownloads = newList
        prefs.edit().putString("list", gson.toJson(newList)).apply()
    }

    suspend fun downloadAll(songs: List<Song>, serverBaseUrl: String) {
        val pending = songs.filter { !isDownloaded(it.id) }
        if (pending.isEmpty()) return

        _downloadProgress.value = DownloadProgress(true, 0, pending.size, "")

        var attempted = 0
        val normalizedBaseUrl = serverBaseUrl.trimEnd('/')
        for (song in pending) {
            attempted++
            _downloadProgress.value = _downloadProgress.value.copy(
                current = attempted,
                songTitle = song.title
            )

            val audioUrl = "$normalizedBaseUrl/audio/${song.id}"
            download(song, audioUrl)
        }

        _downloadProgress.value = DownloadProgress(false, 0, 0, "")
    }

    /**
     * Algoritmo de auto-descarga inteligente: analiza gustos (vía IA) y mantiene
     * el dispositivo lleno con hasta 500 canciones para uso offline.
     *
     * Respeta el modo configurado por el usuario ([SessionManager.getAutoDownloadMode]):
     *  - "liked": rellena con las canciones que ya le gustan (gustos musicales).
     *  - "new"  : rellena solo con canciones aún no escuchadas, priorizando gustos;
     *             si ya escuchó todas, reinicia el ciclo tomando todo el catálogo de
     *             forma aleatoria.
     */
    suspend fun autoDownloadSmart(sessionManager: com.example.localfly.network.SessionManager) {
        val currentCount = getDownloadedSongs().size
        if (currentCount >= 500) {
            android.util.Log.d("DownloadManager", "Auto-descarga: ya tienes $currentCount temas (límite 500).")
            return
        }

        val limit = 500 - currentCount
        val mode = sessionManager.getAutoDownloadMode()
        val baseUrl = com.example.localfly.network.RetrofitClient.getBaseUrl()
        val userId = sessionManager.getUserId() ?: return
        val already = getDownloadedSongs().map { it.id }.toSet()

        android.util.Log.d("DownloadManager", "Auto-descarga: modo '$mode', buscando $limit temas nuevos...")

        // Modo favoritos: rellenar con las canciones que ya le gustan.
        if (mode == "liked") {
            val resp = com.example.localfly.network.RetrofitClient.api.getLikedSongs(userId, limit = 500)
            if (!resp.isSuccessful) {
                android.util.Log.d("DownloadManager", "Auto-descarga (liked): sin respuesta del servidor.")
                return
            }
            val favorites = (resp.body()?.songs ?: emptyList())
                .filter { !it.isEpisode && it.id !in already }
                .shuffled()
                .take(limit)
            android.util.Log.d("DownloadManager", "Auto-descarga (liked): ${favorites.size} favoritas para descargar.")
            if (favorites.isNotEmpty()) downloadAll(favorites, baseUrl)
            return
        }

        // Modo novedades: solo canciones aún no escuchadas, priorizando gustos (IA).
        val weightsStore = AIWeightsStore(appContext)
        val aiManager = com.example.localfly.ai.AIRecommendationManager(sessionManager, weightsStore)
        // Se pide margen amplio para poder filtrar tras excluir descargadas y ya escuchadas.
        val recommendations = aiManager.getRecommendations(limit = (limit * 4).coerceAtMost(400))
        android.util.Log.d("DownloadManager", "Auto-descarga (new): IA devolvió ${recommendations.size} recomendaciones.")

        val dislikedIds = com.example.localfly.network.SongAdminStore.getDislikedSongs().mapNotNull { it.songId }.toSet()
        val playedIds = sessionManager.getPlayedSongIds()

        val candidates = recommendations.filter {
            !it.isEpisode && it.id !in already && it.id !in dislikedIds
        }

        val unheard = candidates.filter { it.id !in playedIds }
        val toDownload = if (unheard.isNotEmpty()) {
            // Primero las no escuchadas, en orden de gusto.
            unheard.take(limit)
        } else if (candidates.isNotEmpty()) {
            // Ya escuchó todas las posibles: reiniciar el ciclo usando todo el
            // catálogo, servido de forma aleatoria.
            android.util.Log.d("DownloadManager", "Auto-descarga (new): ya escuchaste todo, reiniciando ciclo aleatorio.")
            sessionManager.clearPlayedSongs()
            candidates.shuffled().take(limit)
        } else {
            emptyList()
        }

        if (toDownload.isNotEmpty()) {
            android.util.Log.d("DownloadManager", "Auto-descarga: descargando ${toDownload.size} canciones.")
            downloadAll(toDownload, baseUrl)
        } else {
            android.util.Log.d("DownloadManager", "Auto-descarga: no se encontraron temas nuevos para descargar.")
        }
    }
}
