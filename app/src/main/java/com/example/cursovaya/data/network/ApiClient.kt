package com.example.cursovaya.data.network

import com.google.gson.GsonBuilder
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object ApiClient {
    @Volatile
    private var retrofitInstance: Retrofit? = null

    fun initWithBaseUrl(baseUrl: String) {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .build()

        val gson = GsonBuilder().setLenient().create()
        retrofitInstance = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
    }

    val api: TransportApi
        get() = retrofitInstance?.create(TransportApi::class.java)
            ?: throw IllegalStateException("ApiClient not initialized. Call ApiClient.initWithBaseUrl(url) first")
}
