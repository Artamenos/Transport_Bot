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
                SELECT DISTINCT r.id, r.route_number, r.title, r.transport_type, r.origin, r.destination, r.schedule, r.description
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
                    routeStops = listOf("Центральный вокзал", "Проспект Мира", "Торговый центр", "Университет"),
                ),
                SeedRoute(
                    number = "7",
                    title = "Парковое кольцо",
                    type = "Троллейбус",
                    origin = "Южный район",
                    destination = "Парк Победы",
                    description = "Соединяет жилые кварталы с центральным парком города.",
                    schedule = "Интервал 15 минут, с 05:40 до 22:40",
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
                    routeStops = listOf("Микрорайон Солнечный", "Торговый центр", "Набережная"),
                ),
            )

            conn.prepareStatement(
                "INSERT INTO transport_routes(route_number, title, transport_type, origin, destination, description, schedule) VALUES(?, ?, ?, ?, ?, ?, ?)",
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

