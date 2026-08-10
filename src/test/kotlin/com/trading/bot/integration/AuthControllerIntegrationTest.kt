package com.trading.bot.integration

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Интеграционные тесты JWT-аутентификации поверх реального HTTP (RANDOM_PORT):
 * login / refresh (ротация + детекция reuse) / logout, защита `/api/v1/` по
 * ролям и закрытый prometheus-эндпоинт (METRICS_SCRAPE_TOKEN).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthControllerIntegrationTest : AbstractTestContainerTest() {
    @LocalServerPort
    private var port: Int = 0

    private val objectMapper = ObjectMapper()

    private val httpClient: HttpClient = HttpClient.newBuilder().build()

    private val baseUrl: String
        get() = "http://127.0.0.1:$port"

    @Test
    fun `login returns access and refresh tokens and sets httpOnly cookie`() {
        val response =
            postJson(
                "/api/v1/auth/login",
                """{"username":"test-admin","password":"test-admin-pass"}""",
            )
        assertEquals(200, response.statusCode())

        val body = objectMapper.readTree(response.body())
        assertTrue(body.get("accessToken").asString().isNotBlank())
        assertTrue(body.get("refreshToken").asString().isNotBlank())
        assertEquals("Bearer", body.get("tokenType").asString())
        assertEquals("test-admin", body.get("username").asString())
        assertTrue(body.get("roles").any { it.asString() == "ROLE_ADMIN" })

        val cookie = response.headers().firstValue("Set-Cookie").orElse("")
        assertTrue(cookie.contains("refresh_token="))
        assertTrue(cookie.contains("HttpOnly"))
        assertTrue(cookie.contains("Path=/api/v1/auth"))
    }

    @Test
    fun `login with wrong password returns 401`() {
        val response =
            postJson(
                "/api/v1/auth/login",
                """{"username":"test-admin","password":"wrong"}""",
            )
        assertEquals(401, response.statusCode())
    }

    @Test
    fun `refresh rotates token and detects reuse`() {
        val loginBody = login()
        val originalRefresh = loginBody.get("refreshToken").asString()

        val firstRefresh =
            postJson(
                "/api/v1/auth/refresh",
                """{"refreshToken":"$originalRefresh"}""",
            )
        assertEquals(200, firstRefresh.statusCode())

        // Повторное использование уже ротированного токена = 401
        val reuse =
            postJson(
                "/api/v1/auth/refresh",
                """{"refreshToken":"$originalRefresh"}""",
            )
        assertEquals(401, reuse.statusCode())

        // Обнаруженный reuse отзывает всю сессию пользователя
        val rotated =
            objectMapper
                .readTree(firstRefresh.body())
                .get("refreshToken")
                .asString()
        val afterReuse =
            postJson(
                "/api/v1/auth/refresh",
                """{"refreshToken":"$rotated"}""",
            )
        assertEquals(
            401,
            afterReuse.statusCode(),
            "expected 401 after reuse, got ${afterReuse.statusCode()}: ${afterReuse.body().take(200)}",
        )
    }

    @Test
    fun `logout revokes refresh token`() {
        val refreshToken = login().get("refreshToken").asString()

        val logout =
            postJson(
                "/api/v1/auth/logout",
                """{"refreshToken":"$refreshToken"}""",
            )
        assertEquals(200, logout.statusCode())

        val afterLogout =
            postJson(
                "/api/v1/auth/refresh",
                """{"refreshToken":"$refreshToken"}""",
            )
        assertEquals(401, afterLogout.statusCode())
    }

    @Test
    fun `protected endpoints require authentication`() {
        assertEquals(401, get("/api/v1/settings").statusCode())
        assertEquals(401, get("/api/v1/me").statusCode())
    }

    @Test
    fun `admin access token grants read and write access`() {
        val accessToken = login().get("accessToken").asString()

        val me = objectMapper.readTree(get("/api/v1/me", accessToken).body())
        assertEquals("test-admin", me.get("username").asString())

        val settingsBody = get("/api/v1/settings", accessToken).body()
        val update = postJson("/api/v1/settings", settingsBody, accessToken)
        assertEquals(200, update.statusCode())
    }

    @Test
    fun `analytics token is read-only and cannot POST`() {
        val accessToken = login("test-analytics", "test-analytics-pass").get("accessToken").asString()

        val me = objectMapper.readTree(get("/api/v1/me", accessToken).body())
        assertTrue(me.get("roles").any { it.asString() == "ROLE_ANALYTICS" })

        val settingsBody = get("/api/v1/settings", accessToken).body()
        val update = postJson("/api/v1/settings", settingsBody, accessToken)
        assertEquals(403, update.statusCode())
    }

    @Test
    fun `prometheus metrics require scrape token`() {
        assertEquals(401, get("/actuator/prometheus").statusCode())
        assertEquals(401, get("/actuator/prometheus", "wrong-token").statusCode())
        val ok = get("/actuator/prometheus", "test-metrics-scrape-token")
        assertEquals(200, ok.statusCode(), "expected 200 for valid scrape token, got ${ok.statusCode()}: ${ok.body().take(200)}")
    }

    private fun login(): JsonNode = login("test-admin", "test-admin-pass")

    private fun login(
        username: String,
        password: String,
    ): JsonNode {
        val response =
            postJson(
                "/api/v1/auth/login",
                """{"username":"$username","password":"$password"}""",
            )
        assertEquals(200, response.statusCode())
        return objectMapper.readTree(response.body())
    }

    private fun get(
        path: String,
        bearer: String? = null,
    ): HttpResponse<String> {
        val builder =
            HttpRequest
                .newBuilder(URI("$baseUrl$path"))
        if (bearer != null) {
            builder.header("Authorization", "Bearer $bearer")
        }
        return httpClient.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun postJson(
        path: String,
        body: String,
        bearer: String? = null,
    ): HttpResponse<String> {
        val builder =
            HttpRequest
                .newBuilder(URI("$baseUrl$path"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
        if (bearer != null) {
            builder.header("Authorization", "Bearer $bearer")
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }
}
