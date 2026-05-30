package com.example.cursovaya.ui.auth

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.cursovaya.data.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AuthUiState(
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val isAuthenticated: Boolean = false,
    val displayName: String = "",
)

class AuthViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AuthRepository(application.applicationContext)

    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    fun login(login: String, password: String) {
        if (login.isBlank() || password.isBlank()) {
            _state.update { it.copy(errorMessage = "Заполните логин и пароль") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, errorMessage = null, isAuthenticated = false) }
            runCatching { repository.login(login.trim(), password) }
                .onSuccess { name ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            isAuthenticated = true,
                            displayName = name,
                            errorMessage = null,
                        )
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = error.message ?: "Не удалось выполнить вход",
                        )
                    }
                }
        }
    }

    fun register(login: String, displayName: String, password: String, confirmPassword: String) {
        if (login.isBlank() || displayName.isBlank() || password.isBlank()) {
            _state.update { it.copy(errorMessage = "Заполните все поля регистрации") }
            return
        }
        if (password != confirmPassword) {
            _state.update { it.copy(errorMessage = "Пароли не совпадают") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, errorMessage = null, isAuthenticated = false) }
            runCatching { repository.register(login.trim(), displayName.trim(), password) }
                .onSuccess { name ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            isAuthenticated = true,
                            displayName = name,
                            errorMessage = null,
                        )
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = error.message ?: "Не удалось выполнить регистрацию",
                        )
                    }
                }
        }
    }

    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }
}

