package com.example.localfly

import android.content.Context
import com.example.localfly.network.Song
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

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
    val fileSize: Long = 0
)

/**
 * Gestiona la descarga, listado y borrado de canciones offline.
 * Guarda los archivos de audio en el almacenamiento privado de la app
 * (no requiere permisos especiales de almacenamiento) y guarda la lista
 * de canciones descargadas en SharedPreferences como JSON.
 */
class DownloadManagerHelper(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("localfly_downloads", Context.MODE_PRIVATE)
    private val gson = Gson()

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
        val json = prefs.getString("list", null) ?: return emptyList()
        val type = object : TypeToken<List<DownloadedSong>>() {}.type
        return try {
            gson.fromJson<List<DownloadedSong>>(json, type) ?: emptyList()
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
                        fileSize = file!!.length()
                    )
                )
                prefs.edit().putString("list", gson.toJson(current)).apply()

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

    fun removeDownload(songId: String) {
        val current = getDownloadedSongs().toMutableList()
        val target = current.find { it.id == songId }
        target?.let { File(it.filePath).delete() }
        current.removeAll { it.id == songId }
        prefs.edit().putString("list", gson.toJson(current)).apply()
    }
}
