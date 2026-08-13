package com.example.localfly.network

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {

    private var currentBaseUrl = "${ApiConfig.BASE_URL}/"

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private var retrofit: Retrofit = buildRetrofit(currentBaseUrl)

    var api: ApiService = retrofit.create(ApiService::class.java)
        private set

    fun updateBaseUrl(newIp: String) {
        val newUrl = "http://$newIp:5172/"
        if (currentBaseUrl == newUrl) return
        
        currentBaseUrl = newUrl
        retrofit = buildRetrofit(currentBaseUrl)
        api = retrofit.create(ApiService::class.java)
    }

    private fun buildRetrofit(url: String): Retrofit {
        return Retrofit.Builder()
            .baseUrl(url)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }
}
