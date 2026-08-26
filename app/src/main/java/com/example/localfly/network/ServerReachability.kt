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

    private val client = OkHttpClient.Builder()
        .connectTimeout(2500, TimeUnit.MILLISECONDS)
        .readTimeout(2500, TimeUnit.MILLISECONDS)
        .build()

    suspend fun isServerReachable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("${ApiConfig.BASE_URL}/api/config/ip")
                .build()
            client.newCall(request).execute().use { response -> response.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }
}
