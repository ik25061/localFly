package com.example.localfly.network

import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

interface LrclibApiService {
    @GET("api/get")
    suspend fun getLyrics(
        @Query("track_name") trackName: String,
        @Query("artist_name") artistName: String?
    ): Response<LrclibResult>
}

/**
 * Cliente independiente hacia lrclib.net (fuente pública de letras, sin
 * API key). Se usa como respaldo directo desde el móvil cuando el
 * SERVIDOR local no es alcanzable pero sí hay internet — el propio
 * servidor ya usa LRCLIB como una de sus fuentes, así que esto solo
 * replica esa misma fuente saltándose el servidor cuando hace falta.
 */
object LrclibClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    val api: LrclibApiService by lazy {
        Retrofit.Builder()
            .baseUrl("https://lrclib.net/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(LrclibApiService::class.java)
    }
}
