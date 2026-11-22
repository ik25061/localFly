package com.example.localfly.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Comprueba si el SERVIDOR (no "internet" en general) es alcanzable ahora
 * mismo. Usa un timeout corto para no bloquear la UI si está caído o
 * inalcanzable. Se apoya en /api/config/ip por ser una ruta ligera que ya
 * existe y no depende de que la biblioteca esté cargada.
 */
object ServerReachability {

    /**
     * Última respuesta conocida, actualizada por el monitor de conectividad
     * de MainActivity. Se lee de forma síncrona (sin suspender) desde
     * PlaybackService para decidir, al vuelo, si la cola debe limitarse a
     * canciones descargadas. El valor inicial es optimista para la navegación,
     * pero PlaybackService no lo usa hasta que hasChecked sea true.
     */
    @Volatile
    var isOnline: Boolean = true

    /** La primera comprobación evita asumir que el servidor responde al arrancar offline. */
    @Volatile
    var hasChecked: Boolean = false
        private set

    fun reset() {
        hasChecked = false
        isOnline = false
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(2500, TimeUnit.MILLISECONDS)
        .readTimeout(2500, TimeUnit.MILLISECONDS)
        .build()

    suspend fun isServerReachable(): Boolean {
        val reachable = withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("${RetrofitClient.getBaseUrl()}/api/config/ip")
                    .build()
                client.newCall(request).execute().use { response -> response.isSuccessful }
            } catch (e: Exception) {
                false
            }
        }
        isOnline = reachable
        hasChecked = true
        return reachable
    }
}
