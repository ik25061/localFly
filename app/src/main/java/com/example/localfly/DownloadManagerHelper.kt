package com.example.localfly

import android.content.Context
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
    val liked: Boolean = false,
    val fileSize: Long = 0
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

    private var cachedDownloads: List<DownloadedSong>? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun downloadsDir(): File {
        val dir = File(appContext.filesDir, "downloads")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getDownloadedSongs(): List<DownloadedSong> {
        cachedDownloads?.let { return it }
        
        val json = prefs.getString("list", null) ?: return emptyList()
        val type = object : TypeToken<List<DownloadedSong>>() {}.type
        return try {
            val list = gson.fromJson<List<DownloadedSong>>(json, type) ?: emptyList()
            cachedDownloads = list
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun isDownloaded(songId: String): Boolean = getDownloadedSongs().any { it.id == songId }

    fun getLocalFilePath(songId: String): String? {
        val song = getDownloadedSongs().find { it.id == songId } ?: return null
        val file = File(song.filePath)
        return if (file.exists()) file.absolutePath else null
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
                        liked = song.liked,
                        fileSize = file!!.length()
                    )
                )
                val newList = current.toList()
                cachedDownloads = newList
                prefs.edit().putString("list", gson.toJson(newList)).apply()

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
        val baseUrl = com.example.localfly.network.ApiConfig.BASE_URL
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

    fun removeDownload(songId: String) {
        val current = getDownloadedSongs().toMutableList()
        val target = current.find { it.id == songId }
        target?.let { 
            File(it.filePath).delete() 
            // También borrar la letra si existe
            File(downloadsDir(), "$songId.lrc").delete()
        }
        current.removeAll { it.id == songId }
        val newList = current.toList()
        cachedDownloads = newList
        prefs.edit().putString("list", gson.toJson(newList)).apply()
    }

    /** Elimina todas las canciones descargadas (archivos y registro). */
    fun removeAllDownloads() {
        getDownloadedSongs().forEach { song ->
            runCatching { File(song.filePath).delete() }
        }
        cachedDownloads = emptyList()
        prefs.edit().putString("list", gson.toJson(emptyList<DownloadedSong>())).apply()
    }

    /** Actualiza el estado "me gusta" guardado de una canción descargada (si existe). */
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

        var count = 0
        for (song in pending) {
            _downloadProgress.value = _downloadProgress.value.copy(
                current = count + 1,
                songTitle = song.title
            )
            
            val audioUrl = "$serverBaseUrl/audio/${song.id}"
            if (download(song, audioUrl)) {
                count++
            }
        }

        _downloadProgress.value = DownloadProgress(false, 0, 0, "")
    }

    /**
     * Algoritmo de auto-descarga inteligente: analiza gustos (vía IA) y mantiene
     * el dispositivo lleno con hasta 500 canciones para uso offline.
     */
    suspend fun autoDownloadSmart(sessionManager: com.example.localfly.network.SessionManager) {
        val currentCount = getDownloadedSongs().size
        if (currentCount >= 500) {
            android.util.Log.d("DownloadManager", "Auto-descarga: ya tienes $currentCount temas (límite 500).")
            return
        }

        val limit = 500 - currentCount
        android.util.Log.d("DownloadManager", "Auto-descarga: iniciando búsqueda de $limit temas nuevos...")
        
        val aiManager = com.example.localfly.ai.AIRecommendationManager(sessionManager)
        
        // Obtener recomendaciones (la IA ya usa los likes y artistas favoritos)
        val recommendations = aiManager.getRecommendations(limit = limit)
        val toDownload = recommendations.filter { !isDownloaded(it.id) }

        if (toDownload.isNotEmpty()) {
            android.util.Log.d("DownloadManager", "Auto-descarga: descargando ${toDownload.size} canciones recomendadas.")
            downloadAll(toDownload, com.example.localfly.network.ApiConfig.BASE_URL)
        } else {
            android.util.Log.d("DownloadManager", "Auto-descarga: no se encontraron temas nuevos para descargar.")
        }
    }
}
