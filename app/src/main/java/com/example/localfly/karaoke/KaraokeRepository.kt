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
 * Clave de caché del instrumental: debe coincidir con [KaraokeRepository]
 * (URL GET /karaoke/{songId} hasheada con SHA-256).
 */
object KaraokeCache {
    fun fileFor(context: android.content.Context, baseUrl: String, songId: String): File {
        val url = baseUrl.toHttpUrl().newBuilder().addPathSegment("karaoke").addPathSegment(songId).build()
        val key = MessageDigest.getInstance("SHA-256").digest(url.toString().toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(File(context.filesDir, "karaoke"), "$key.audio")
    }

    /**
     * Borra el instrumental cacheado de una canción, si existe.
     * El nombre del fichero es un hash de la URL completa (incluye IP/puerto):
     * si el servidor cambió de IP desde que se cacheó, el hash antiguo no
     * coincide y el fichero huérfano quedará sin borrar (limitación conocida;
     * no se purga por antigüedad para no borrar instrumentales de otras
     * canciones).
     */
    fun deleteFor(context: android.content.Context, songId: String): Boolean {
        return try {
            val baseUrl = com.example.localfly.network.RetrofitClient.getBaseUrl()
            fileFor(context, baseUrl, songId).delete()
        } catch (_: Exception) { false }
    }
}

/** Cache independiente: nunca sobrescribe ni elimina la descarga original. */
class KaraokeRepository(context: Context) {
    private val appContext = context.applicationContext
    private val directory = File(appContext.filesDir, "karaoke").apply { mkdirs() }
    private val client = OkHttpClient.Builder().callTimeout(3, TimeUnit.MINUTES).build()

    suspend fun instrumental(baseUrl: String, songId: String): File = withContext(Dispatchers.IO) {
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
