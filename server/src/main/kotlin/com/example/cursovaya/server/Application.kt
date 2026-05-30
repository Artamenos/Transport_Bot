package com.example.cursovaya.server

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.gson.gson
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.http.*
fun main() {
    embeddedServer(Netty, host = "0.0.0.0", port = 8080) {
        module()
    }.start(wait = true)
}

fun Application.module() {
    install(CallLogging)
    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Options)
    }
    install(ContentNegotiation) {
        gson { setPrettyPrinting() }
    }
    install(StatusPages) {
        exception<IllegalArgumentException> { call, error ->
            call.respond(HttpStatusCode.BadRequest, MessageResponse(error.message ?: "Некорректный запрос"))
        }
        exception<IllegalStateException> { call, error ->
            call.respond(HttpStatusCode.Unauthorized, MessageResponse(error.message ?: "Требуется авторизация"))
        }
    }

    routing {
        get("/health") {
            call.respond(MessageResponse("Сервер транспортного бота работает"))
        }

        post("/api/auth/register") {
            val request = call.receive<AuthRequest>()
            try {
                val response = TransportBotDatabase.register(request.login, request.password, request.displayName)
                call.respond(HttpStatusCode.Created, response)
            } catch (error: IllegalStateException) {
                val status = if (error.message?.contains("уже существует", ignoreCase = true) == true) {
                    HttpStatusCode.Conflict
                } else {
                    HttpStatusCode.BadRequest
                }
                call.respond(status, MessageResponse(error.message ?: "Ошибка регистрации"))
            } catch (error: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, MessageResponse(error.message ?: "Ошибка регистрации"))
            }
        }

        post("/api/auth/login") {
            val request = call.receive<AuthRequest>()
            try {
                val response = TransportBotDatabase.login(request.login, request.password)
                call.respond(response)
            } catch (error: IllegalStateException) {
                call.respond(HttpStatusCode.Unauthorized, MessageResponse(error.message ?: "Ошибка авторизации"))
            } catch (error: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, MessageResponse(error.message ?: "Ошибка авторизации"))
            }
        }

        get("/api/search") {
            val token = call.bearerToken()
            val query = call.request.queryParameters["q"].orEmpty()
            try {
                val results = TransportBotDatabase.search(token, query)
                TransportBotDatabase.addHistory(token, query)
                call.respond(SearchResponse(results))
            } catch (error: IllegalStateException) {
                call.respond(HttpStatusCode.Unauthorized, MessageResponse(error.message ?: "Требуется авторизация"))
            } catch (error: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, MessageResponse(error.message ?: "Некорректный запрос"))
            }
        }

        get("/api/history") {
            val token = call.bearerToken()
            try {
                call.respond(HistoryResponse(TransportBotDatabase.history(token)))
            } catch (error: IllegalStateException) {
                call.respond(HttpStatusCode.Unauthorized, MessageResponse(error.message ?: "Требуется авторизация"))
            }
        }

        post("/api/history") {
            val token = call.bearerToken()
            val request = call.receive<HistoryRequest>()
            try {
                call.respond(HistoryResponse(TransportBotDatabase.addHistory(token, request.query)))
            } catch (error: IllegalStateException) {
                call.respond(HttpStatusCode.Unauthorized, MessageResponse(error.message ?: "Требуется авторизация"))
            }
        }

        delete("/api/history") {
            val token = call.bearerToken()
            try {
                TransportBotDatabase.clearHistory(token)
                call.respond(MessageResponse("История поиска очищена"))
            } catch (error: IllegalStateException) {
                call.respond(HttpStatusCode.Unauthorized, MessageResponse(error.message ?: "Требуется авторизация"))
            }
        }

        get("/api/chat") {
            val token = call.bearerToken()
            try {
                call.respond(ChatHistoryResponse(TransportBotDatabase.chatHistory(token)))
            } catch (error: IllegalStateException) {
                call.respond(HttpStatusCode.Unauthorized, MessageResponse(error.message ?: "Требуется авторизация"))
            }
        }

        post("/api/chat") {
            val token = call.bearerToken()
            val request = call.receive<ChatSendRequest>()
            try {
                call.respond(ChatHistoryResponse(TransportBotDatabase.sendChatMessage(token, request.text, request.topic)))
            } catch (error: IllegalStateException) {
                call.respond(HttpStatusCode.Unauthorized, MessageResponse(error.message ?: "Требуется авторизация"))
            } catch (error: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, MessageResponse(error.message ?: "Некорректный запрос"))
            }
        }

        delete("/api/chat") {
            val token = call.bearerToken()
            try {
                TransportBotDatabase.clearChat(token)
                call.respond(MessageResponse("История чата очищена"))
            } catch (error: IllegalStateException) {
                call.respond(HttpStatusCode.Unauthorized, MessageResponse(error.message ?: "Требуется авторизация"))
            }
        }

        get("/api/me/profile") {
            val token = call.bearerToken()
            try {
                call.respond(TransportBotDatabase.profile(token))
            } catch (error: IllegalStateException) {
                call.respond(HttpStatusCode.Unauthorized, MessageResponse(error.message ?: "Требуется авторизация"))
            }
        }

        post("/api/routes/claim") {
            val token = call.bearerToken()
            val request = call.receive<RouteCodeRequest>()
            try {
                call.respond(TransportBotDatabase.claimRoute(token, request.code))
            } catch (error: IllegalStateException) {
                val status = if (TransportBotDatabase.userIdByToken(token) == null) HttpStatusCode.Unauthorized else HttpStatusCode.Conflict
                call.respond(status, MessageResponse(error.message ?: "Не удалось закрепить маршрут"))
            } catch (error: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, MessageResponse(error.message ?: "Некорректный код маршрута"))
            }
        }

        post("/api/routes/release") {
            val token = call.bearerToken()
            val request = call.receive<RouteCodeRequest>()
            try {
                call.respond(TransportBotDatabase.releaseRoute(token, request.code))
            } catch (error: IllegalStateException) {
                val status = if (TransportBotDatabase.userIdByToken(token) == null) HttpStatusCode.Unauthorized else HttpStatusCode.Conflict
                call.respond(status, MessageResponse(error.message ?: "Не удалось освободить маршрут"))
            } catch (error: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, MessageResponse(error.message ?: "Некорректный код маршрута"))
            }
        }
    }
}

private fun io.ktor.server.application.ApplicationCall.bearerToken(): String {
    val header = request.headers["Authorization"] ?: throw IllegalStateException("Требуется авторизация")
    return header.removePrefix("Bearer ").trim().takeIf { it.isNotBlank() }
        ?: throw IllegalStateException("Требуется авторизация")
}
