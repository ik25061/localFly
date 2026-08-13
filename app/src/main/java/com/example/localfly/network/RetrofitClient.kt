package com.example.localfly.network

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
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

    /**
     * Adaptador personalizado para Booleanos.
     * Permite procesar tanto valores booleanos reales (true/false) como
     * números (0/1) que a veces envía el servidor (ej. desde MySQL/MariaDB).
     */
    private val booleanTypeAdapter = object : TypeAdapter<Boolean>() {
        override fun write(out: JsonWriter, value: Boolean?) {
            out.value(value)
        }

        override fun read(reader: JsonReader): Boolean? {
            return when (reader.peek()) {
                JsonToken.BOOLEAN -> reader.nextBoolean()
                JsonToken.NUMBER -> reader.nextInt() != 0
                JsonToken.NULL -> {
                    reader.nextNull()
                    null
                }
                else -> false
            }
        }
    }

    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(Boolean::class.java, booleanTypeAdapter)
        .registerTypeAdapter(Boolean::class.javaPrimitiveType, booleanTypeAdapter)
        .create()

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
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
    }
}
