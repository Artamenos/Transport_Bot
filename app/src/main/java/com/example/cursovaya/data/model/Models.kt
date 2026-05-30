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
    val routeCode: String = "",
    val title: String,
    val transportType: String,
    val origin: String,
    val destination: String,
    val schedule: String,
    val description: String,
    val travelDate: String,
    val departureTime: String,
    val arrivalTime: String,
    val fare: String,
    val assignedTo: String? = null,
    val isAssigned: Boolean = false,
    val isMine: Boolean = false,
)

data class HistoryRequest(
    val query: String,
)

data class RouteCodeRequest(
    val code: String,
)

data class DriverProfileResponse(
    val login: String,
    val displayName: String,
    val routes: List<TransportRouteDto>,
)

data class HistoryResponse(
    val items: List<String>,
)

data class MessageResponse(
    val message: String,
)

data class ChatSendRequest(
    val text: String? = null,
    val topic: String? = null,
)

data class ChatMessageDto(
    val id: Long,
    val sender: String,
    val text: String,
    val createdAt: String,
)

data class ChatHistoryResponse(
    val items: List<ChatMessageDto>,
)
