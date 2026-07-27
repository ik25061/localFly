package com.example.localfly.network

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {

    // IMPORTANTE: cambia esta IP por la IP local de tu servidor mirepo.
    //
    // - Si pruebas en el EMULADOR de Android Studio y el servidor corre
    //   en tu propio PC: usa "http://10.0.2.2:5002/" (10.0.2.2 apunta al
    //   localhost de tu PC desde el emulador)
    //
    // - Si pruebas en un TELÉFONO FÍSICO conectado a la misma red WiFi
    //   que tu servidor: usa la IP local de tu PC, ej "http://192.168.1.50:5002/"
    //   (mira tu IP con `ipconfig` en Windows o `ip a` en Linux)
    private const val BASE_URL = "http://127.0.1.1:5002/"

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    val api: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}
