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
    val filePath: String
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
     */
    suspend fun download(song: Song, audioUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(audioUrl).build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) return@withContext false

            val file = File(downloadsDir(), "${song.id}.mp3")
            response.body?.byteStream()?.use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }

            val current = getDownloadedSongs().toMutableList()
            current.removeAll { it.id == song.id }
            current.add(DownloadedSong(song.id, song.title, song.artist, file.absolutePath))
            prefs.edit().putString("list", gson.toJson(current)).apply()

            true
        } catch (e: Exception) {
            false
        }
    }

    fun removeDownload(songId: String) {
        val current = getDownloadedSongs().toMutableList()
        val target = current.find { it.id == songId }
        target?.let { File(it.filePath).delete() }
        current.removeAll { it.id == songId }
        prefs.edit().putString("list", gson.toJson(current)).apply()
    }
}
