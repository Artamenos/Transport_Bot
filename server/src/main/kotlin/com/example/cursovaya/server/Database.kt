package com.example.cursovaya.server

import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

object TransportBotDatabase {
    private const val JDBC_URL = "jdbc:h2:file:./server-data/transportbot;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;AUTO_SERVER=TRUE"
    private const val USER = "sa"
    private const val PASSWORD = ""
    private val random = SecureRandom()

    private const val CHAT_HISTORY_LIMIT = 50

    private enum class PendingAction {
        CHANGE_TIME,
        CHANGE_DATE,
        CHANGE_DESTINATION,
    }

    private data class ChatState(
        val lastRouteId: Long?,
        val pendingAction: PendingAction?,
    )

    init {
        Class.forName("org.h2.Driver")
        connection().use { conn ->
            createSchema(conn)
            seedData(conn)
        }
    }

    fun register(login: String, password: String, displayName: String?): AuthResponse {
        val normalizedLogin = login.trim().lowercase()
        require(normalizedLogin.isNotBlank()) { "Введите логин" }
        require(password.length >= 4) { "Пароль должен содержать минимум 4 символа" }
        val name = displayName?.trim().takeUnless { it.isNullOrBlank() } ?: normalizedLogin

        connection().use { conn ->
            if (userExists(conn, normalizedLogin)) {
                throw IllegalStateException("Пользователь с таким логином уже существует")
            }
            val salt = generateSalt()
            val passwordHash = hashPassword(password, salt)
            val userId = insertUser(conn, normalizedLogin, name, salt, passwordHash)
            return createSession(conn, userId, normalizedLogin, name)
        }
    }

    fun login(login: String, password: String): AuthResponse {
        val normalizedLogin = login.trim().lowercase()
        require(normalizedLogin.isNotBlank()) { "Введите логин" }
        require(password.isNotBlank()) { "Введите пароль" }

        connection().use { conn ->
            val user = findUserByLogin(conn, normalizedLogin)
                ?: throw IllegalStateException("Пользователь не найден")
            val expectedHash = hashPassword(password, user.salt)
            if (!expectedHash.equals(user.passwordHash, ignoreCase = true)) {
                throw IllegalStateException("Неверный логин или пароль")
            }
            return createSession(conn, user.id, user.login, user.displayName)
        }
    }

    fun userIdByToken(token: String): Long? = connection().use { conn ->
        conn.prepareStatement(
            "SELECT user_id FROM sessions WHERE token = ? AND expires_at > ?"
        ).use { statement ->
            statement.setString(1, token)
            statement.setTimestamp(2, Timestamp.from(Instant.now()))
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.getLong("user_id") else null
            }
        }
    }

    fun search(query: String): List<TransportRouteDto> {
        val normalizedQuery = query.trim()
        require(normalizedQuery.isNotBlank()) { "Введите поисковый запрос" }

        val like = "%${normalizedQuery.lowercase()}%"
        connection().use { conn ->
            val sql = """
                SELECT DISTINCT r.id, r.route_number, r.title, r.transport_type, r.origin, r.destination, r.schedule, r.description,
                    r.travel_date, r.departure_time, r.arrival_time, r.fare
                FROM transport_routes r
                LEFT JOIN route_stops rs ON rs.route_id = r.id
                LEFT JOIN stops s ON s.id = rs.stop_id
                WHERE LOWER(r.route_number) LIKE ?
                   OR LOWER(r.title) LIKE ?
                   OR LOWER(r.transport_type) LIKE ?
                   OR LOWER(r.origin) LIKE ?
                   OR LOWER(r.destination) LIKE ?
                   OR LOWER(r.description) LIKE ?
                   OR LOWER(s.name) LIKE ?
                ORDER BY r.route_number
            """.trimIndent()
            conn.prepareStatement(sql).use { statement ->
                repeat(7) { index -> statement.setString(index + 1, like) }
                statement.executeQuery().use { resultSet ->
                    return resultSet.toRouteList()
                }
            }
        }
    }

    fun history(token: String): List<String> {
        val userId = requireUserId(token)
        connection().use { conn ->
            conn.prepareStatement(
                "SELECT query_text FROM search_history WHERE user_id = ? ORDER BY created_at DESC, id DESC LIMIT 10"
            ).use { statement ->
                statement.setLong(1, userId)
                statement.executeQuery().use { resultSet ->
                    val items = mutableListOf<String>()
                    while (resultSet.next()) {
                        items += resultSet.getString("query_text")
                    }
                    return items
                }
            }
        }
    }

    fun addHistory(token: String, query: String): List<String> {
        val userId = requireUserId(token)
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) {
            return history(token)
        }
        connection().use { conn ->
            conn.prepareStatement("DELETE FROM search_history WHERE user_id = ? AND LOWER(query_text) = LOWER(?)").use { deleteStatement ->
                deleteStatement.setLong(1, userId)
                deleteStatement.setString(2, normalizedQuery)
                deleteStatement.executeUpdate()
            }
            conn.prepareStatement("INSERT INTO search_history(user_id, query_text, created_at) VALUES(?, ?, ?)").use { insertStatement ->
                insertStatement.setLong(1, userId)
                insertStatement.setString(2, normalizedQuery)
                insertStatement.setTimestamp(3, Timestamp.from(Instant.now()))
                insertStatement.executeUpdate()
            }
            trimHistory(conn, userId)
            return loadHistory(conn, userId)
        }
    }

    fun clearHistory(token: String) {
        val userId = requireUserId(token)
        connection().use { conn ->
            conn.prepareStatement("DELETE FROM search_history WHERE user_id = ?").use { statement ->
                statement.setLong(1, userId)
                statement.executeUpdate()
            }
        }
    }

    fun chatHistory(token: String): List<ChatMessageDto> {
        val userId = requireUserId(token)
        connection().use { conn ->
            ensureChatGreeting(conn, userId)
            return loadChatHistory(conn, userId)
        }
    }

    fun sendChatMessage(token: String, text: String?, topic: String?): List<ChatMessageDto> {
        val userId = requireUserId(token)
        val sanitizedText = text?.trim().orEmpty()
        val sanitizedTopic = topic?.trim()?.uppercase().orEmpty()
        connection().use { conn ->
            if (sanitizedText.isNotBlank()) {
                insertChatMessage(conn, userId, "USER", sanitizedText)
            } else if (sanitizedTopic.isNotBlank()) {
                insertChatMessage(conn, userId, "USER", topicLabel(sanitizedTopic))
            }

            val reply = botReply(conn, userId, sanitizedText, sanitizedTopic)
            insertChatMessage(conn, userId, "BOT", reply)
            trimChatHistory(conn, userId)
            return loadChatHistory(conn, userId)
        }
    }

    fun clearChat(token: String) {
        val userId = requireUserId(token)
        connection().use { conn ->
            conn.prepareStatement("DELETE FROM chat_messages WHERE user_id = ?").use { statement ->
                statement.setLong(1, userId)
                statement.executeUpdate()
            }
        }
    }

    private fun requireUserId(token: String): Long {
        val normalizedToken = token.removePrefix("Bearer ").trim()
        return userIdByToken(normalizedToken) ?: throw IllegalStateException("Требуется авторизация")
    }

    private fun createSession(conn: Connection, userId: Long, login: String, displayName: String): AuthResponse {
        val token = UUID.randomUUID().toString().replace("-", "")
        conn.prepareStatement("DELETE FROM sessions WHERE user_id = ?").use { deleteStatement ->
            deleteStatement.setLong(1, userId)
            deleteStatement.executeUpdate()
        }
        conn.prepareStatement("INSERT INTO sessions(token, user_id, created_at, expires_at) VALUES(?, ?, ?, ?)").use { statement ->
            statement.setString(1, token)
            statement.setLong(2, userId)
            statement.setTimestamp(3, Timestamp.from(Instant.now()))
            statement.setTimestamp(4, Timestamp.from(Instant.now().plusSeconds(60L * 60L * 24L * 30L)))
            statement.executeUpdate()
        }
        return AuthResponse(token = token, login = login, displayName = displayName)
    }

    private fun insertUser(conn: Connection, login: String, displayName: String, salt: String, passwordHash: String): Long {
        conn.prepareStatement(
            "INSERT INTO users(login, display_name, password_salt, password_hash, created_at) VALUES(?, ?, ?, ?, ?)",
            java.sql.Statement.RETURN_GENERATED_KEYS
        ).use { statement ->
            statement.setString(1, login)
            statement.setString(2, displayName)
            statement.setString(3, salt)
            statement.setString(4, passwordHash)
            statement.setTimestamp(5, Timestamp.from(Instant.now()))
            statement.executeUpdate()
            statement.generatedKeys.use { keys ->
                if (keys.next()) {
                    return keys.getLong(1)
                }
            }
        }
        throw IllegalStateException("Не удалось создать пользователя")
    }

    private fun userExists(conn: Connection, login: String): Boolean {
        conn.prepareStatement("SELECT 1 FROM users WHERE login = ?").use { statement ->
            statement.setString(1, login)
            statement.executeQuery().use { resultSet ->
                return resultSet.next()
            }
        }
    }

    private data class UserRecord(
        val id: Long,
        val login: String,
        val displayName: String,
        val salt: String,
        val passwordHash: String,
    )

    private fun findUserByLogin(conn: Connection, login: String): UserRecord? {
        conn.prepareStatement("SELECT id, login, display_name, password_salt, password_hash FROM users WHERE login = ?").use { statement ->
            statement.setString(1, login)
            statement.executeQuery().use { resultSet ->
                return if (resultSet.next()) {
                    UserRecord(
                        id = resultSet.getLong("id"),
                        login = resultSet.getString("login"),
                        displayName = resultSet.getString("display_name"),
                        salt = resultSet.getString("password_salt"),
                        passwordHash = resultSet.getString("password_hash"),
                    )
                } else {
                    null
                }
            }
        }
    }

    private fun trimHistory(conn: Connection, userId: Long) {
        val idsToDelete = mutableListOf<Long>()
        conn.prepareStatement("SELECT id FROM search_history WHERE user_id = ? ORDER BY created_at DESC, id DESC").use { statement ->
            statement.setLong(1, userId)
            statement.executeQuery().use { resultSet ->
                var index = 0
                while (resultSet.next()) {
                    index++
                    if (index > 10) {
                        idsToDelete += resultSet.getLong("id")
                    }
                }
            }
        }
        if (idsToDelete.isNotEmpty()) {
            conn.prepareStatement("DELETE FROM search_history WHERE id = ?").use { statement ->
                for (id in idsToDelete) {
                    statement.setLong(1, id)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        }
    }

    private fun loadHistory(conn: Connection, userId: Long): List<String> {
        conn.prepareStatement("SELECT query_text FROM search_history WHERE user_id = ? ORDER BY created_at DESC, id DESC LIMIT 10").use { statement ->
            statement.setLong(1, userId)
            statement.executeQuery().use { resultSet ->
                val items = mutableListOf<String>()
                while (resultSet.next()) {
                    items += resultSet.getString("query_text")
                }
                return items
            }
        }
    }

    private fun ResultSet.toRouteList(): List<TransportRouteDto> {
        val items = mutableListOf<TransportRouteDto>()
        while (next()) {
            items += TransportRouteDto(
                id = getLong("id"),
                routeNumber = getString("route_number"),
                title = getString("title"),
                transportType = getString("transport_type"),
                origin = getString("origin"),
                destination = getString("destination"),
                schedule = getString("schedule"),
                description = getString("description"),
                travelDate = getString("travel_date") ?: "",
                departureTime = getString("departure_time") ?: "",
                arrivalTime = getString("arrival_time") ?: "",
                fare = getString("fare") ?: "",
            )
        }
        return items
    }

    private fun connection(): Connection = DriverManager.getConnection(JDBC_URL, USER, PASSWORD)

    private fun createSchema(conn: Connection) {
        conn.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS users (
                    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    login VARCHAR(64) NOT NULL UNIQUE,
                    display_name VARCHAR(120) NOT NULL,
                    password_salt VARCHAR(128) NOT NULL,
                    password_hash VARCHAR(128) NOT NULL,
                    created_at TIMESTAMP NOT NULL
                )
                """.trimIndent()
            )
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS sessions (
                    token VARCHAR(64) PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    created_at TIMESTAMP NOT NULL,
                    expires_at TIMESTAMP NOT NULL,
                    CONSTRAINT fk_sessions_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
                )
                """.trimIndent()
            )
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS transport_routes (
                    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    route_number VARCHAR(16) NOT NULL,
                    title VARCHAR(120) NOT NULL,
                    transport_type VARCHAR(40) NOT NULL,
                    origin VARCHAR(120) NOT NULL,
                    destination VARCHAR(120) NOT NULL,
                    description VARCHAR(255) NOT NULL,
                    schedule VARCHAR(255) NOT NULL
                )
                """.trimIndent()
            )
            statement.execute("ALTER TABLE transport_routes ADD COLUMN IF NOT EXISTS travel_date VARCHAR(20)")
            statement.execute("ALTER TABLE transport_routes ADD COLUMN IF NOT EXISTS departure_time VARCHAR(10)")
            statement.execute("ALTER TABLE transport_routes ADD COLUMN IF NOT EXISTS arrival_time VARCHAR(10)")
            statement.execute("ALTER TABLE transport_routes ADD COLUMN IF NOT EXISTS fare VARCHAR(20)")
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS stops (
                    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    name VARCHAR(120) NOT NULL
                )
                """.trimIndent()
            )
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS route_stops (
                    route_id BIGINT NOT NULL,
                    stop_id BIGINT NOT NULL,
                    stop_order INT NOT NULL,
                    PRIMARY KEY (route_id, stop_id),
                    CONSTRAINT fk_route_stops_route FOREIGN KEY (route_id) REFERENCES transport_routes(id) ON DELETE CASCADE,
                    CONSTRAINT fk_route_stops_stop FOREIGN KEY (stop_id) REFERENCES stops(id) ON DELETE CASCADE
                )
                """.trimIndent()
            )
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS search_history (
                    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    query_text VARCHAR(180) NOT NULL,
                    created_at TIMESTAMP NOT NULL,
                    CONSTRAINT fk_search_history_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
                )
                """.trimIndent()
            )
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS chat_messages (
                    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    sender VARCHAR(8) NOT NULL,
                    message_text VARCHAR(500) NOT NULL,
                    created_at TIMESTAMP NOT NULL,
                    CONSTRAINT fk_chat_messages_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
                )
                """.trimIndent()
            )
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS chat_state (
                    user_id BIGINT PRIMARY KEY,
                    last_route_id BIGINT,
                    pending_action VARCHAR(40),
                    updated_at TIMESTAMP NOT NULL,
                    CONSTRAINT fk_chat_state_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
                )
                """.trimIndent()
            )
        }
    }

    private fun seedData(conn: Connection) {
        val routeCount = conn.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) AS cnt FROM transport_routes").use { resultSet ->
                resultSet.next()
                resultSet.getInt("cnt")
            }
        }
        if (routeCount == 0) {
            val stops = listOf(
                "Центральный вокзал",
                "Университет",
                "Парк Победы",
                "Южный район",
                "Стадион",
                "Аэропорт",
                "Поликлиника №3",
                "ЖД вокзал",
                "Микрорайон Солнечный",
                "Набережная",
                "Торговый центр",
                "Проспект Мира",
            )
            val stopIds = mutableMapOf<String, Long>()
            conn.prepareStatement("INSERT INTO stops(name) VALUES(?)", java.sql.Statement.RETURN_GENERATED_KEYS).use { statement ->
                for (stop in stops) {
                    statement.setString(1, stop)
                    statement.executeUpdate()
                    statement.generatedKeys.use { keys ->
                        if (keys.next()) {
                            stopIds[stop] = keys.getLong(1)
                        }
                    }
                }
            }

            data class SeedRoute(
                val number: String,
                val title: String,
                val type: String,
                val origin: String,
                val destination: String,
                val description: String,
                val schedule: String,
                val travelDate: String,
                val departureTime: String,
                val arrivalTime: String,
                val fare: String,
                val routeStops: List<String>,
            )

            val routes = listOf(
                SeedRoute(
                    number = "12",
                    title = "Центральный маршрут",
                    type = "Автобус",
                    origin = "Центральный вокзал",
                    destination = "Университет",
                    description = "Подходит для поездок студентов и сотрудников университета.",
                    schedule = "Интервал 12 минут, с 06:00 до 23:00",
                    travelDate = "2026-06-01",
                    departureTime = "08:15",
                    arrivalTime = "08:45",
                    fare = "45 руб.",
                    routeStops = listOf("Центральный вокзал", "Проспект Мира", "Торговый центр", "Университет"),
                ),
                SeedRoute(
                    number = "100",
                    title = "Туристический маршрут",
                    type = "Автобус",
                    origin = "Аэропорт",
                    destination = "Центральный вокзал",
                    description = "Экспресс для туристов с улучшенным комфортом.",
                    schedule = "Каждые 30 минут",
                    travelDate = "2026-06-05",
                    departureTime = "12:00",
                    arrivalTime = "12:50",
                    fare = "150 руб.",
                    routeStops = listOf("Аэропорт", "Центральный вокзал"),
                ),
                SeedRoute(
                    number = "99",
                    title = "Ночной экспресс",
                    type = "Маршрутка",
                    origin = "ЖД вокзал",
                    destination = "Спальный район",
                    description = "Работает ночью, для поздних поездок.",
                    schedule = "С 23:00 до 05:00",
                    travelDate = "2026-06-03",
                    departureTime = "01:00",
                    arrivalTime = "01:30",
                    fare = "80 руб.",
                    routeStops = listOf("ЖД вокзал", "Спальный район"),
                ),
                SeedRoute(
                    number = "7",
                    title = "Парковое кольцо",
                    type = "Троллейбус",
                    origin = "Южный район",
                    destination = "Парк Победы",
                    description = "Соединяет жилые кварталы с центральным парком города.",
                    schedule = "Интервал 15 минут, с 05:40 до 22:40",
                    travelDate = "2026-06-01",
                    departureTime = "09:10",
                    arrivalTime = "09:40",
                    fare = "40 руб.",
                    routeStops = listOf("Южный район", "Поликлиника №3", "Проспект Мира", "Парк Победы"),
                ),
                SeedRoute(
                    number = "24",
                    title = "Аэропорт Экспресс",
                    type = "Трамвай",
                    origin = "Аэропорт",
                    destination = "Стадион",
                    description = "Быстрый маршрут между аэропортом и спортивным кластером.",
                    schedule = "Интервал 20 минут, с 06:20 до 21:40",
                    travelDate = "2026-06-01",
                    departureTime = "10:00",
                    arrivalTime = "10:35",
                    fare = "55 руб.",
                    routeStops = listOf("Аэропорт", "Торговый центр", "Стадион"),
                ),
                SeedRoute(
                    number = "3",
                    title = "Городская линия",
                    type = "Маршрутка",
                    origin = "ЖД вокзал",
                    destination = "Поликлиника №3",
                    description = "Популярная линия для быстрой поездки к медицинскому центру.",
                    schedule = "Интервал 8 минут, с 06:00 до 22:00",
                    travelDate = "2026-06-01",
                    departureTime = "07:30",
                    arrivalTime = "07:55",
                    fare = "50 руб.",
                    routeStops = listOf("ЖД вокзал", "Проспект Мира", "Поликлиника №3"),
                ),
                SeedRoute(
                    number = "18",
                    title = "Набережная",
                    type = "Автобус",
                    origin = "Микрорайон Солнечный",
                    destination = "Набережная",
                    description = "Маршрут для поездок к реке и зонам отдыха.",
                    schedule = "Интервал 18 минут, с 06:10 до 22:10",
                    travelDate = "2026-06-01",
                    departureTime = "11:15",
                    arrivalTime = "11:55",
                    fare = "45 руб.",
                    routeStops = listOf("Микрорайон Солнечный", "Торговый центр", "Набережная"),
                ),
            )

            conn.prepareStatement(
                "INSERT INTO transport_routes(route_number, title, transport_type, origin, destination, description, schedule, travel_date, departure_time, arrival_time, fare) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                java.sql.Statement.RETURN_GENERATED_KEYS
            ).use { routeStatement ->
                val routeIds = mutableMapOf<String, Long>()
                for (route in routes) {
                    routeStatement.setString(1, route.number)
                    routeStatement.setString(2, route.title)
                    routeStatement.setString(3, route.type)
                    routeStatement.setString(4, route.origin)
                    routeStatement.setString(5, route.destination)
                    routeStatement.setString(6, route.description)
                    routeStatement.setString(7, route.schedule)
                    routeStatement.setString(8, route.travelDate)
                    routeStatement.setString(9, route.departureTime)
                    routeStatement.setString(10, route.arrivalTime)
                    routeStatement.setString(11, route.fare)
                    routeStatement.executeUpdate()
                    routeStatement.generatedKeys.use { keys ->
                        if (keys.next()) {
                            routeIds[route.number] = keys.getLong(1)
                        }
                    }
                }

                conn.prepareStatement("INSERT INTO route_stops(route_id, stop_id, stop_order) VALUES(?, ?, ?)").use { linkStatement ->
                    for (route in routes) {
                        val routeId = routeIds[route.number] ?: continue
                        route.routeStops.forEachIndexed { index, stopName ->
                            val stopId = stopIds[stopName] ?: return@forEachIndexed
                            linkStatement.setLong(1, routeId)
                            linkStatement.setLong(2, stopId)
                            linkStatement.setInt(3, index + 1)
                            linkStatement.addBatch()
                        }
                    }
                    linkStatement.executeBatch()
                }
            }
        }

    }

    private fun loadChatHistory(conn: Connection, userId: Long): List<ChatMessageDto> {
        conn.prepareStatement(
            """
            SELECT id, sender, message_text, created_at
            FROM chat_messages
            WHERE user_id = ?
            ORDER BY created_at ASC, id ASC
            LIMIT ?
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, userId)
            statement.setInt(2, CHAT_HISTORY_LIMIT)
            statement.executeQuery().use { resultSet ->
                val items = mutableListOf<ChatMessageDto>()
                while (resultSet.next()) {
                    items += ChatMessageDto(
                        id = resultSet.getLong("id"),
                        sender = resultSet.getString("sender"),
                        text = resultSet.getString("message_text"),
                        createdAt = resultSet.getTimestamp("created_at").toInstant().toString(),
                    )
                }
                return items
            }
        }
    }

    private fun insertChatMessage(conn: Connection, userId: Long, sender: String, text: String) {
        conn.prepareStatement(
            "INSERT INTO chat_messages(user_id, sender, message_text, created_at) VALUES(?, ?, ?, ?)"
        ).use { statement ->
            statement.setLong(1, userId)
            statement.setString(2, sender)
            statement.setString(3, text)
            statement.setTimestamp(4, Timestamp.from(Instant.now()))
            statement.executeUpdate()
        }
    }

    private fun trimChatHistory(conn: Connection, userId: Long) {
        val idsToDelete = mutableListOf<Long>()
        conn.prepareStatement(
            "SELECT id FROM chat_messages WHERE user_id = ? ORDER BY created_at DESC, id DESC"
        ).use { statement ->
            statement.setLong(1, userId)
            statement.executeQuery().use { resultSet ->
                var index = 0
                while (resultSet.next()) {
                    index++
                    if (index > CHAT_HISTORY_LIMIT) {
                        idsToDelete += resultSet.getLong("id")
                    }
                }
            }
        }
        if (idsToDelete.isNotEmpty()) {
            conn.prepareStatement("DELETE FROM chat_messages WHERE id = ?").use { statement ->
                for (id in idsToDelete) {
                    statement.setLong(1, id)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        }
    }

    private fun ensureChatGreeting(conn: Connection, userId: Long) {
        conn.prepareStatement("SELECT COUNT(*) AS cnt FROM chat_messages WHERE user_id = ?").use { statement ->
            statement.setLong(1, userId)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next() && resultSet.getInt("cnt") == 0) {
                    insertChatMessage(
                        conn,
                        userId,
                        "BOT",
                        "Привет! Выберите тему вопроса или напишите запрос. Доступно: маршрут по номеру, расписание, остановки и общие вопросы по маршрутам. [MAIN_TOPICS]",
                    )
                }
            }
        }
    }

    private fun botReply(conn: Connection, userId: Long, text: String, topic: String): String {
        val state = loadChatState(conn, userId)
        if (state.pendingAction != null) {
            return handlePendingAction(conn, userId, state, text)
        }

        val number = extractRouteNumber(text)
        return when (topic) {
            "ROUTE_BY_ID" -> routeInfoReply(conn, userId, number, "Укажите номер маршрута, например 12.")
            "SCHEDULE" -> routeScheduleReply(conn, userId, number)
            "QUESTION" -> routeStopsReply(conn, userId, number)
            else -> handleFreeText(conn, userId, text, number)
        }
    }

    private fun handleFreeText(conn: Connection, userId: Long, text: String, number: String?): String {
        val lowered = text.lowercase()
        // Если пользователь просит "другой вопрос" — очищаем состояние диалога и возвращаем приветствие
        if (lowered.contains("другой вопрос") || lowered == "другой" || lowered.contains("другая тема")) {
            // очищаем last_route_id и pending_action
            saveChatState(conn, userId, null, null)
            return "Принято. Выберите новую тему вопроса. Доступно: маршрут по номеру, расписание, остановки и общие вопросы по маршрутам."
        }
        return when {
            lowered.contains("изменить время") || lowered.contains("перенести время") -> askForTimeChange(conn, userId)
            lowered.contains("изменить дату") || lowered.contains("перенести дату") -> askForDateChange(conn, userId)
            lowered.contains("изменить маршрут") -> askForDestinationChange(conn, userId)
            lowered.contains("распис") || lowered.contains("график") -> routeScheduleReply(conn, userId, number)
            lowered.contains("останов") -> routeStopsReply(conn, userId, number)
            number != null -> routeInfoReply(conn, userId, number, "Маршрут с таким номером не найден.")
            else -> "Я могу помочь с маршрутом, расписанием или остановками. Примеры: \"маршрут 12\", \"расписание 7\", \"остановки 24\"."
        }
    }

    private fun handlePendingAction(conn: Connection, userId: Long, state: ChatState, text: String): String {
        val routeId = state.lastRouteId ?: return "Сначала выберите маршрут по номеру."
        return when (state.pendingAction) {
            PendingAction.CHANGE_TIME -> {
                val time = text.trim()
                if (!time.matches(Regex("\\d{2}:\\d{2}"))) {
                    "Введите время в формате HH:MM, например 09:30."
                } else {
                    updateRouteTime(conn, routeId, time)
                    clearPendingAction(conn, userId, routeId)
                    val route = findRouteById(conn, routeId)
                    "Время отправления обновлено на $time. ${routeSummary(route)}"
                }
            }
            PendingAction.CHANGE_DATE -> {
                val date = text.trim()
                if (!date.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
                    "Введите дату в формате YYYY-MM-DD, например 2026-06-02."
                } else {
                    updateRouteDate(conn, routeId, date)
                    clearPendingAction(conn, userId, routeId)
                    val route = findRouteById(conn, routeId)
                    "Дата поездки обновлена на $date. ${routeSummary(route)}"
                }
            }
            PendingAction.CHANGE_DESTINATION -> {
                val destination = text.trim()
                if (destination.isBlank()) {
                    "Введите новое направление/пункт назначения."
                } else {
                    updateRouteDestination(conn, routeId, destination)
                    clearPendingAction(conn, userId, routeId)
                    val route = findRouteById(conn, routeId)
                    "Маршрут обновлен. ${routeSummary(route)}"
                }
            }
            null -> ""
        }
    }

    private fun askForTimeChange(conn: Connection, userId: Long): String {
        val state = loadChatState(conn, userId)
        return if (state.lastRouteId == null) {
            "Сначала укажите номер маршрута, чтобы изменить время."
        } else {
            saveChatState(conn, userId, state.lastRouteId, PendingAction.CHANGE_TIME)
            "Укажите новое время отправления (HH:MM)."
        }
    }

    private fun askForDateChange(conn: Connection, userId: Long): String {
        val state = loadChatState(conn, userId)
        return if (state.lastRouteId == null) {
            "Сначала укажите номер маршрута, чтобы изменить дату."
        } else {
            saveChatState(conn, userId, state.lastRouteId, PendingAction.CHANGE_DATE)
            "Укажите новую дату поездки (YYYY-MM-DD)."
        }
    }

    private fun askForDestinationChange(conn: Connection, userId: Long): String {
        val state = loadChatState(conn, userId)
        return if (state.lastRouteId == null) {
            "Сначала укажите номер маршрута, чтобы изменить направление."
        } else {
            saveChatState(conn, userId, state.lastRouteId, PendingAction.CHANGE_DESTINATION)
            "Введите новый пункт назначения маршрута."
        }
    }

    private fun routeInfoReply(conn: Connection, userId: Long, number: String?, fallback: String): String {
        if (number.isNullOrBlank()) return fallback
        val route = findRouteByNumber(conn, number) ?: return "Маршрут $number не найден. Попробуйте 7, 12, 18 или 24."
        saveChatState(conn, userId, route.id, null)
        // Возвращаем специальный маркер [AFTER_ROUTE] в конце, чтобы клиент знал, что нужно заменить набор кнопок
        return buildString {
            append("Маршрут ${route.routeNumber} — ${route.title} (${route.transportType}).\n")
            append("От: ${route.origin}\nДо: ${route.destination}\n")
            append("Дата: ${route.travelDate}, отправление: ${route.departureTime}, прибытие: ${route.arrivalTime}\n")
            append("Стоимость: ${route.fare}.\n")
            append("Что изменить: время, дату или маршрут? [AFTER_ROUTE]")
        }
    }

    private fun routeScheduleReply(conn: Connection, userId: Long, number: String?): String {
        if (number.isNullOrBlank()) return "Напишите номер маршрута для расписания, например 12."
        val route = findRouteByNumber(conn, number) ?: return "Маршрут $number не найден. Попробуйте 7, 12, 18 или 24."
        saveChatState(conn, userId, route.id, null)
        return "Расписание маршрута ${route.routeNumber}: ${route.schedule}"
    }

    private fun routeStopsReply(conn: Connection, userId: Long, number: String?): String {
        if (number.isNullOrBlank()) return "Укажите номер маршрута, чтобы показать остановки."
        val route = findRouteByNumber(conn, number) ?: return "Маршрут $number не найден. Попробуйте 7, 12, 18 или 24."
        saveChatState(conn, userId, route.id, null)
        val stops = routeStops(conn, route.id)
        return if (stops.isEmpty()) {
            "По маршруту ${route.routeNumber} нет данных об остановках."
        } else {
            "Остановки маршрута ${route.routeNumber}: ${stops.joinToString(", ")}."
        }
    }

    private fun routeSummary(route: TransportRouteDto?): String {
        if (route == null) return ""
        return "Текущие данные: ${route.origin} → ${route.destination}, ${route.travelDate} ${route.departureTime}. Что изменить дальше?"
    }

    private fun loadChatState(conn: Connection, userId: Long): ChatState {
        conn.prepareStatement("SELECT last_route_id, pending_action FROM chat_state WHERE user_id = ?").use { statement ->
            statement.setLong(1, userId)
            statement.executeQuery().use { resultSet ->
                return if (resultSet.next()) {
                    val action = resultSet.getString("pending_action")
                    ChatState(
                        lastRouteId = resultSet.getLong("last_route_id").takeIf { !resultSet.wasNull() },
                        pendingAction = action?.let { PendingAction.valueOf(it) },
                    )
                } else {
                    ChatState(null, null)
                }
            }
        }
    }

    private fun saveChatState(conn: Connection, userId: Long, routeId: Long?, pendingAction: PendingAction?) {
        conn.prepareStatement(
            """
            MERGE INTO chat_state(user_id, last_route_id, pending_action, updated_at)
            KEY(user_id) VALUES(?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, userId)
            if (routeId == null) statement.setNull(2, java.sql.Types.BIGINT) else statement.setLong(2, routeId)
            if (pendingAction == null) statement.setNull(3, java.sql.Types.VARCHAR) else statement.setString(3, pendingAction.name)
            statement.setTimestamp(4, Timestamp.from(Instant.now()))
            statement.executeUpdate()
        }
    }

    private fun clearPendingAction(conn: Connection, userId: Long, routeId: Long?) {
        saveChatState(conn, userId, routeId, null)
    }

    private fun updateRouteTime(conn: Connection, routeId: Long, time: String) {
        conn.prepareStatement("UPDATE transport_routes SET departure_time = ? WHERE id = ?").use { statement ->
            statement.setString(1, time)
            statement.setLong(2, routeId)
            statement.executeUpdate()
        }
    }

    private fun updateRouteDate(conn: Connection, routeId: Long, date: String) {
        conn.prepareStatement("UPDATE transport_routes SET travel_date = ? WHERE id = ?").use { statement ->
            statement.setString(1, date)
            statement.setLong(2, routeId)
            statement.executeUpdate()
        }
    }

    private fun updateRouteDestination(conn: Connection, routeId: Long, destination: String) {
        conn.prepareStatement("UPDATE transport_routes SET destination = ? WHERE id = ?").use { statement ->
            statement.setString(1, destination)
            statement.setLong(2, routeId)
            statement.executeUpdate()
        }
    }

    private fun findRouteById(conn: Connection, routeId: Long): TransportRouteDto? {
        conn.prepareStatement(
            """
            SELECT id, route_number, title, transport_type, origin, destination, schedule, description, travel_date, departure_time, arrival_time, fare
            FROM transport_routes
            WHERE id = ?
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, routeId)
            statement.executeQuery().use { resultSet ->
                return if (resultSet.next()) {
                    TransportRouteDto(
                        id = resultSet.getLong("id"),
                        routeNumber = resultSet.getString("route_number"),
                        title = resultSet.getString("title"),
                        transportType = resultSet.getString("transport_type"),
                        origin = resultSet.getString("origin"),
                        destination = resultSet.getString("destination"),
                        schedule = resultSet.getString("schedule"),
                        description = resultSet.getString("description"),
                        travelDate = resultSet.getString("travel_date") ?: "",
                        departureTime = resultSet.getString("departure_time") ?: "",
                        arrivalTime = resultSet.getString("arrival_time") ?: "",
                        fare = resultSet.getString("fare") ?: "",
                    )
                } else {
                    null
                }
            }
        }
    }

    private fun findRouteByNumber(conn: Connection, number: String): TransportRouteDto? {
        conn.prepareStatement(
            """
            SELECT id, route_number, title, transport_type, origin, destination, schedule, description, travel_date, departure_time, arrival_time, fare
            FROM transport_routes
            WHERE route_number = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, number)
            statement.executeQuery().use { resultSet ->
                return if (resultSet.next()) {
                    TransportRouteDto(
                        id = resultSet.getLong("id"),
                        routeNumber = resultSet.getString("route_number"),
                        title = resultSet.getString("title"),
                        transportType = resultSet.getString("transport_type"),
                        origin = resultSet.getString("origin"),
                        destination = resultSet.getString("destination"),
                        schedule = resultSet.getString("schedule"),
                        description = resultSet.getString("description"),
                        travelDate = resultSet.getString("travel_date") ?: "",
                        departureTime = resultSet.getString("departure_time") ?: "",
                        arrivalTime = resultSet.getString("arrival_time") ?: "",
                        fare = resultSet.getString("fare") ?: "",
                    )
                } else {
                    null
                }
            }
        }
    }

    private fun routeStops(conn: Connection, routeId: Long): List<String> {
        conn.prepareStatement(
            """
            SELECT s.name
            FROM route_stops rs
            JOIN stops s ON s.id = rs.stop_id
            WHERE rs.route_id = ?
            ORDER BY rs.stop_order
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, routeId)
            statement.executeQuery().use { resultSet ->
                val items = mutableListOf<String>()
                while (resultSet.next()) {
                    items += resultSet.getString("name")
                }
                return items
            }
        }
    }

    private fun topicLabel(topic: String): String = when (topic) {
        "ROUTE_BY_ID" -> "Маршрут по номеру"
        "SCHEDULE" -> "Расписание маршрута"
        "QUESTION" -> "Вопрос по маршруту"
        else -> "Запрос"
    }

    private fun extractRouteNumber(text: String): String? {
        val match = Regex("\\b\\d{1,3}\\b").find(text)
        return match?.value
    }

    private fun generateSalt(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun hashPassword(password: String, salt: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest((salt + password).toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
