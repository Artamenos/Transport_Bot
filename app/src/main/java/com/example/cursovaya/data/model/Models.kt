package com.example.cursovaya.data.model

data class AuthRequest(
    val login: String,
    val password: String,
    val displayName: String? = null,
)

data class AuthResponse(
    val token: String,
    val login: String,
    val displayName: String,
)

data class SearchResponse(
    val results: List<TransportRouteDto>,
)

data class TransportRouteDto(
    val id: Long,
    val routeNumber: String,
    val title: String,
    val transportType: String,
    val origin: String,
    val destination: String,
    val schedule: String,
    val description: String,
)

data class HistoryRequest(
    val query: String,
)

data class HistoryResponse(
    val items: List<String>,
)

data class MessageResponse(
    val message: String,
)

