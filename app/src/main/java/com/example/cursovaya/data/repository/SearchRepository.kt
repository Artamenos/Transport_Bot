package com.example.cursovaya.data.repository

import android.content.Context
import com.example.cursovaya.data.local.AppPrefs
import com.example.cursovaya.data.model.HistoryRequest
import com.example.cursovaya.data.model.RouteCodeRequest
import com.example.cursovaya.data.model.DriverProfileResponse
import com.example.cursovaya.data.model.TransportRouteDto
import com.example.cursovaya.data.network.ApiClient

class SearchRepository(context: Context) {
    private val prefs = AppPrefs(context.applicationContext)
    private val api = ApiClient.api

    fun currentHistory(): List<String> = prefs.history()

    fun saveTheme(enabled: Boolean) {
        prefs.saveDarkTheme(enabled)
    }

    fun isDarkThemeEnabled(): Boolean = prefs.isDarkThemeEnabled()

    fun displayName(): String = prefs.displayName()

    fun token(): String? = prefs.token()

    suspend fun loadHistory(): List<String> {
        val token = requireToken()
        val response = api.history(token)
        prefs.replaceHistory(response.items)
        return response.items
    }

    suspend fun search(query: String): List<TransportRouteDto> {
        val token = requireToken()
        val response = api.search(token, query)
        val updatedHistory = api.history(token).items
        prefs.replaceHistory(updatedHistory)
        return response.results
    }

    suspend fun loadPopularRoutes(): List<TransportRouteDto> {
        val token = requireToken()
        return api.search(token, "").results
    }

    suspend fun loadProfile(): DriverProfileResponse {
        val token = requireToken()
        return api.profile(token)
    }

    suspend fun claimRoute(code: String): DriverProfileResponse {
        val token = requireToken()
        return api.claimRoute(token, RouteCodeRequest(code.trim()))
    }

    suspend fun releaseRoute(code: String): DriverProfileResponse {
        val token = requireToken()
        return api.releaseRoute(token, RouteCodeRequest(code.trim()))
    }

    suspend fun addHistory(item: String): List<String> {
        val token = requireToken()
        val response = api.addHistory(token, HistoryRequest(item))
        prefs.replaceHistory(response.items)
        return response.items
    }

    suspend fun clearHistory(): List<String> {
        val token = requireToken()
        api.clearHistory(token)
        prefs.clearHistory()
        return emptyList()
    }

    private fun requireToken(): String = prefs.token()?.let { "Bearer $it" }
        ?: throw IllegalStateException("Требуется повторный вход в приложение")
}
