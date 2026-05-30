package com.example.cursovaya.data.repository

import android.content.Context
import com.example.cursovaya.data.local.AppPrefs
import com.example.cursovaya.data.model.AuthRequest
import com.example.cursovaya.data.model.MessageResponse
import com.example.cursovaya.data.network.ApiClient
import com.google.gson.Gson
import retrofit2.HttpException
import java.io.IOException

class AuthRepository(context: Context) {
    private val prefs = AppPrefs(context.applicationContext)
    private val api = ApiClient.api
    private val gson = Gson()

    fun isLoggedIn(): Boolean = !prefs.token().isNullOrBlank()

    fun isDarkThemeEnabled(): Boolean = prefs.isDarkThemeEnabled()

    fun toggleDarkTheme(enabled: Boolean) {
        prefs.saveDarkTheme(enabled)
    }

    fun displayName(): String = prefs.displayName()

    fun logout() {
        prefs.clearSession()
        prefs.clearHistory()
    }

    suspend fun login(login: String, password: String): String =
        runApiCall { api.login(AuthRequest(login = login, password = password)) }

    suspend fun register(login: String, displayName: String, password: String): String =
        runApiCall { api.register(AuthRequest(login = login, password = password, displayName = displayName)) }

    private suspend fun runApiCall(block: suspend () -> com.example.cursovaya.data.model.AuthResponse): String {
        try {
            val response = block()
            prefs.saveSession(response.token, response.login, response.displayName)
            return response.displayName
        } catch (error: HttpException) {
            val message = errorBodyMessage(error)
            throw IllegalStateException(message ?: "Ошибка запроса: ${error.code()}")
        } catch (error: IOException) {
            throw IllegalStateException("Сервер недоступен. Проверьте подключение.")
        }
    }

    private fun errorBodyMessage(error: HttpException): String? {
        val body = error.response()?.errorBody()?.string()?.trim().orEmpty()
        if (body.isBlank()) return null
        return runCatching { gson.fromJson(body, MessageResponse::class.java).message }.getOrNull()
    }
}

