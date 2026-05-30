package com.example.cursovaya.ui.search

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.cursovaya.data.model.TransportRouteDto
import com.example.cursovaya.data.repository.AuthRepository
import com.example.cursovaya.data.repository.SearchRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val isLoading: Boolean = false,
    val results: List<TransportRouteDto> = emptyList(),
    val history: List<String> = emptyList(),
    val showHistory: Boolean = false,
    val showEmptyState: Boolean = false,
    val showErrorState: Boolean = false,
    val errorMessage: String? = null,
    val darkTheme: Boolean = false,
    val displayName: String = "",
)

class SearchViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = SearchRepository(application.applicationContext)
    private val authRepository = AuthRepository(application.applicationContext)

    private val _state = MutableStateFlow(
        SearchUiState(
            history = repository.currentHistory(),
            darkTheme = repository.isDarkThemeEnabled(),
            displayName = repository.displayName(),
        )
    )
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    init {
        applyTheme(repository.isDarkThemeEnabled())
    }

    fun initializeHistory() {
        viewModelScope.launch {
            runCatching { repository.loadHistory() }
                .onSuccess { items ->
                    _state.update {
                        it.copy(
                            history = items,
                            showHistory = it.query.isBlank() && items.isNotEmpty(),
                        )
                    }
                }
                .onFailure {
                    _state.update {
                        it.copy(
                            history = repository.currentHistory(),
                            showHistory = it.query.isBlank() && repository.currentHistory().isNotEmpty(),
                        )
                    }
                }
        }
    }

    fun onQueryChanged(query: String) {
        _state.update {
            it.copy(
                query = query,
                showHistory = query.isBlank() && it.history.isNotEmpty(),
                showErrorState = false,
                showEmptyState = false,
                errorMessage = null,
            )
        }
    }

    fun onFieldFocusChanged(hasFocus: Boolean) {
        _state.update {
            it.copy(showHistory = hasFocus && it.query.isBlank() && it.history.isNotEmpty())
        }
    }

    fun search() {
        val query = state.value.query.trim()
        if (query.isBlank()) return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    isLoading = true,
                    showHistory = false,
                    showEmptyState = false,
                    showErrorState = false,
                    errorMessage = null,
                )
            }
            runCatching { repository.search(query) }
                .onSuccess { results ->
                    val history = repository.currentHistory()
                    _state.update {
                        it.copy(
                            isLoading = false,
                            results = results,
                            history = history,
                            showHistory = false,
                            showEmptyState = results.isEmpty(),
                            showErrorState = false,
                            errorMessage = null,
                        )
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            showErrorState = true,
                            showEmptyState = false,
                            errorMessage = error.message ?: "Поиск завершился с ошибкой",
                        )
                    }
                }
        }
    }

    fun refresh() {
        search()
    }

    fun onHistoryClicked(item: String) {
        onQueryChanged(item)
        search()
    }

    fun onResultClicked(item: TransportRouteDto) {
        val label = "${item.routeNumber} ${item.title}"
        viewModelScope.launch {
            runCatching { repository.addHistory(label) }
                .onSuccess { history ->
                    _state.update { it.copy(history = history, showHistory = false) }
                }
        }
    }

    fun clearSearch() {
        _state.update {
            it.copy(
                query = "",
                results = emptyList(),
                showErrorState = false,
                showEmptyState = false,
                errorMessage = null,
                showHistory = it.history.isNotEmpty(),
            )
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            runCatching { repository.clearHistory() }
                .onSuccess {
                    _state.update {
                        it.copy(history = emptyList(), showHistory = false)
                    }
                }
        }
    }

    fun toggleTheme(enabled: Boolean) {
        repository.saveTheme(enabled)
        applyTheme(enabled)
        _state.update { it.copy(darkTheme = enabled) }
    }

    fun logout() {
        authRepository.logout()
    }

    private fun applyTheme(enabled: Boolean) {
        AppCompatDelegate.setDefaultNightMode(
            if (enabled) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
    }
}

