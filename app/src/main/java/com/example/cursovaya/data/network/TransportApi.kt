package com.example.cursovaya.data.network

import com.example.cursovaya.data.model.AuthRequest
import com.example.cursovaya.data.model.AuthResponse
import com.example.cursovaya.data.model.HistoryRequest
import com.example.cursovaya.data.model.HistoryResponse
import com.example.cursovaya.data.model.SearchResponse
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

interface TransportApi {
    @POST("api/auth/register")
    suspend fun register(@Body request: AuthRequest): AuthResponse

    @POST("api/auth/login")
    suspend fun login(@Body request: AuthRequest): AuthResponse

    @GET("api/search")
    suspend fun search(
        @Header("Authorization") token: String,
        @Query("q") query: String,
    ): SearchResponse

    @GET("api/history")
    suspend fun history(@Header("Authorization") token: String): HistoryResponse

    @POST("api/history")
    suspend fun addHistory(
        @Header("Authorization") token: String,
        @Body request: HistoryRequest,
    ): HistoryResponse

    @DELETE("api/history")
    suspend fun clearHistory(@Header("Authorization") token: String)
}

