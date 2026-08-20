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

                // Descargar letra si existe y no la tenemos
                if (song.hasLyrics) {
                    downloadLyrics(song)
                }

                val current = getDownloadedSongs().toMutableList()
                current.removeAll { it.id == song.id }
                current.add(
                    DownloadedSong(
                        id = song.id,
                        title = song.title,
                        artist = song.artist,
                        filePath = file!!.absolutePath,
                        duration = song.duration,
                        hasCover = song.hasCover,
                        hasLyrics = song.hasLyrics,
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
        val baseUrl = com.example.localfly.network.ApiConfig.BASE_URL
        val client = httpClient // Usar el OkHttpClient ya configurado en la clase

        // Intentamos varias variantes de nombre para asegurar que encontramos el archivo .lrc
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

                client.newCall(request).execute().use { response ->
                    val contentType = response.header("Content-Type")
                    if (response.isSuccessful && contentType?.contains("text/html") == false) {
                        val body = response.body ?: return@use
                        val content = body.string()
                        
                        // Verificar que no sea HTML (ej. página de error 404 personalizada)
                        if (content.isNotBlank() && !content.trim().startsWith("<")) {
                            val lrcFile = File(downloadsDir(), "${song.id}.lrc")
                            lrcFile.writeText(content)
                            return // Éxito, salimos del bucle de variantes
                        }
                    }
                }
            } catch (e: Exception) {
                // Siguiente variante
            }
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
}
