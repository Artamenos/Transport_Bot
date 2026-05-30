package com.example.cursovaya.data.local

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class AppPrefs(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    fun saveSession(token: String, login: String, displayName: String) {
        prefs.edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_LOGIN, login)
            .putString(KEY_DISPLAY_NAME, displayName)
            .apply()
    }

    fun token(): String? = prefs.getString(KEY_TOKEN, null)

    fun displayName(): String = prefs.getString(KEY_DISPLAY_NAME, "Пользователь") ?: "Пользователь"

    fun saveDarkTheme(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DARK_THEME, enabled).apply()
    }

    fun isDarkThemeEnabled(): Boolean = prefs.getBoolean(KEY_DARK_THEME, false)

    fun clearSession() {
        prefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_LOGIN)
            .remove(KEY_DISPLAY_NAME)
            .apply()
    }

    fun history(): List<String> = decodeHistory(prefs.getString(KEY_HISTORY, null))

    fun replaceHistory(items: List<String>) {
        saveHistory(items.distinct().take(10))
    }

    fun addHistoryItem(item: String) {
        val normalized = item.trim()
        if (normalized.isBlank()) return
        val current = history().toMutableList()
        current.removeAll { it.equals(normalized, ignoreCase = true) }
        current.add(0, normalized)
        saveHistory(current.take(10))
    }

    fun clearHistory() {
        prefs.edit().remove(KEY_HISTORY).apply()
    }

    private fun saveHistory(items: List<String>) {
        prefs.edit().putString(KEY_HISTORY, gson.toJson(items)).apply()
    }

    private fun decodeHistory(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson<List<String>>(raw, type).orEmpty()
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val PREFS_NAME = "transport_bot_prefs"
        const val KEY_TOKEN = "token"
        const val KEY_LOGIN = "login"
        const val KEY_DISPLAY_NAME = "display_name"
        const val KEY_DARK_THEME = "dark_theme"
        const val KEY_HISTORY = "history"
    }
}

