package com.example.cursovaya.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.cursovaya.data.model.ChatMessageDto
import com.example.cursovaya.data.repository.ChatRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TOPIC_ROUTE = "ROUTE_BY_ID"
private const val TOPIC_SCHEDULE = "SCHEDULE"
private const val TOPIC_QUESTION = "QUESTION"

data class ChatUiState(
    val messages: List<ChatMessageUi> = emptyList(),
    val isLoading: Boolean = false,
    val input: String = "",
    val errorMessage: String? = null,
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = ChatRepository(application.applicationContext)

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    fun loadHistory() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, errorMessage = null) }
            runCatching { repository.loadHistory() }
                .onSuccess { messages ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            messages = messages.toUi(),
                        )
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(isLoading = false, errorMessage = error.message ?: "Не удалось загрузить чат")
                    }
                }
        }
    }

    fun onInputChanged(text: String) {
        _state.update { it.copy(input = text) }
    }

    fun sendMessage() {
        val text = state.value.input.trim()
        if (text.isBlank()) return
        send(text, null, clearInput = true)
    }

    fun sendTopicRoute() = send("", TOPIC_ROUTE, clearInput = false)

    fun sendTopicSchedule() = send("", TOPIC_SCHEDULE, clearInput = false)

    fun sendTopicQuestion() = send("", TOPIC_QUESTION, clearInput = false)

    fun clearChat() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, errorMessage = null) }
            runCatching { repository.clearChat() }
                .onSuccess {
                    _state.update { it.copy(isLoading = false, messages = emptyList()) }
                }
                .onFailure { error ->
                    _state.update { it.copy(isLoading = false, errorMessage = error.message ?: "Не удалось очистить чат") }
                }
        }
    }

    private fun send(text: String, topic: String?, clearInput: Boolean) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, errorMessage = null) }
            runCatching {
                if (topic.isNullOrBlank()) {
                    repository.sendMessage(text, null)
                } else {
                    repository.sendMessage(text, topic)
                }
            }
                .onSuccess { messages ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            messages = messages.toUi(),
                            input = if (clearInput) "" else it.input,
                        )
                    }
                }
                .onFailure { error ->
                    _state.update { it.copy(isLoading = false, errorMessage = error.message ?: "Не удалось отправить сообщение") }
                }
        }
    }

    private fun List<ChatMessageDto>.toUi(): List<ChatMessageUi> = map {
        ChatMessageUi(
            id = it.id,
            text = it.text,
            isUser = it.sender.equals("USER", ignoreCase = true),
            time = formatTime(it.createdAt),
        )
    }

    private fun formatTime(value: String): String? {
        if (value.length >= 16 && value[10] == 'T') {
            return value.substring(11, 16)
        }
        return null
    }
}
