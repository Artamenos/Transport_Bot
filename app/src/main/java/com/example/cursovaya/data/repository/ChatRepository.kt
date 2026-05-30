package com.example.cursovaya.data.repository

import android.content.Context
import com.example.cursovaya.data.local.AppPrefs
import com.example.cursovaya.data.model.ChatHistoryResponse
import com.example.cursovaya.data.model.ChatMessageDto
import com.example.cursovaya.data.model.ChatSendRequest
import com.example.cursovaya.data.network.ApiClient

class ChatRepository(context: Context) {
    private val prefs = AppPrefs(context.applicationContext)
    private val api = ApiClient.api

    suspend fun loadHistory(): List<ChatMessageDto> {
        val token = requireToken()
        val response: ChatHistoryResponse = api.chatHistory(token)
        return response.items
    }

    suspend fun sendMessage(text: String, topic: String? = null): List<ChatMessageDto> {
        val token = requireToken()
        val response = api.sendChat(token, ChatSendRequest(text = text, topic = topic))
        return response.items
    }

    suspend fun sendTopic(topic: String): List<ChatMessageDto> {
        val token = requireToken()
        val response = api.sendChat(token, ChatSendRequest(text = null, topic = topic))
        return response.items
    }

    suspend fun clearChat(): List<ChatMessageDto> {
        val token = requireToken()
        api.clearChat(token)
        return emptyList()
    }

    private fun requireToken(): String = prefs.token()?.let { "Bearer $it" }
        ?: throw IllegalStateException("Требуется повторный вход в приложение")
}
