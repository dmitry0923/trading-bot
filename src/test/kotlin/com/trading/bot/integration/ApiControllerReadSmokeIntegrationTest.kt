package com.trading.bot.integration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Smoke-тесты read-only API поверх реального HTTP (RANDOM_PORT) и Postgres/Redis:
 * каждый дешёвый GET-эндпоинт дашборда/аналитики/риска вызывается с admin-JWT и
 * проверяется (а) код ответа и (б) что тело — валидный JSON.
 *
 * Намеренно не вызываются тяжёлые эндпоинты (полный бэктест/WFA на реальной истории),
 * эндпоинты с внешними сетями (MOEX/CBR backfill, LLM) и мутирующие GET — они
 * проверяются отдельно (см. SecurityConfig-матрицу и research-раннеры).
 *
 * Отдельно зафиксированы:
 *  - `/backtest/{ticker}` и `/validate` на ПУСТОЙ истории отдают нулевой результат (200),
 *    а не падают — историческое поведение движка;
 *  - state-changing GET (`deployment-gate`, `macro/rates-history`) закрыты ролью ADMIN:
 *    ANALYTICS получает 403 ещё до вычислений.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("integration")
class ApiControllerReadSmokeIntegrationTest : AbstractTestContainerTest() {
    @LocalServerPort
    private var port: Int = 0

    private val objectMapper = ObjectMapper()

    private val httpClient: HttpClient = HttpClient.newBuilder().build()

    private val baseUrl: String
        get() = "http://127.0.0.1:$port"

    @Test
    fun `core dashboard endpoints return 200 with json`() {
        val me = assertOkJson("/api/v1/me")
        assertEquals("test-admin", me.get("username").asString())
        assertTrue(me.get("roles").toString().contains("ROLE_ADMIN"), "roles must be exposed: $me")

        assertOkJson("/api/v1/settings")
        assertOkJson("/api/v1/positions")
        assertOkJson("/api/v1/positions/all")
        assertOkJson("/api/v1/strategies")
        assertOkJson("/api/v1/risk/daily-pnl")
        assertOkJson("/api/v1/risk/daily-pnl-history?days=7")
        assertOkJson("/api/v1/risk/drawdown")
        assertOkJson("/api/v1/trading/status")
        assertOkJson("/api/v1/experiment/status")
        assertOkJson("/api/v1/rag/status")
        assertOkJson("/api/v1/llm/providers")
        assertOkJson("/api/v1/accounts")

        // Последняя стратегия тикера может отсутствовать — тело пустое, но статус 200.
        assertEquals(200, get("/api/v1/strategies/CNYRUBF", adminToken()).statusCode())
    }

    @Test
    fun `analytics endpoints return 200 with json`() {
        assertOkJson("/api/v1/analytics/trade-stats?days=7")
        assertOkJson("/api/v1/analytics/blind-spots")
        assertOkJson("/api/v1/analytics/adjustments")
        assertOkJson("/api/v1/analytics/adjustments?ticker=SBER")
        assertOkJson("/api/v1/analytics/health")
        assertOkJson("/api/v1/analytics/time-pattern/SBER?days=7")
        // Окно аналитики зажимается в [1, 365]: без этого отрицательное значение
        // уводило окно в будущее, огромное — на полный скан таблицы сделок.
        assertOkJson("/api/v1/analytics/trade-stats?days=0")
        assertOkJson("/api/v1/analytics/trade-stats?days=-5")
        assertOkJson("/api/v1/analytics/trade-stats?days=100000")
        assertOkJson("/api/v1/analytics/time-pattern/SBER?days=-5")
        assertOkJson("/api/v1/analytics/time-pattern/SBER?days=100000")
        assertOkJson("/api/v1/analytics/adaptive-params/SBER")
        assertOkJson("/api/v1/positions/424242/events")
        assertOkJson("/api/v1/traces?limit=5")
        assertOkJson("/api/v1/traces?cycleId=unknown-cycle&limit=5")
    }

    @Test
    fun `portfolio and research endpoints return 200 with json`() {
        assertOkJson("/api/v1/investors")
        assertOkJson("/api/v1/investors/allocations")
        assertOkJson("/api/v1/clearing/pool")
        assertOkJson("/api/v1/backtest/results?ticker=CNYRUBF")
        assertOkJson("/api/v1/backtest/CNYRUBF/funding-history/status")
        assertOkJson("/api/v1/lineage/unknown-cycle")
        assertOkJson("/api/v1/microstructure/coverage?ticker=CNYRUBF")
        assertOkJson("/api/v1/microstructure/snapshots?ticker=CNYRUBF&limit=10")
        assertOkJson("/api/v1/microstructure/bbo?ticker=CNYRUBF&limit=10")
        assertOkJson("/api/v1/microstructure/ticks?ticker=CNYRUBF&limit=10")
        assertOkJson("/api/v1/macro/rates-history/status")
        assertOkJson("/api/v1/macro/rates-history/series?days=30")
        assertOkJson("/api/v1/risk/exposure")
        assertOkJson("/api/v1/risk/correlation?tickers=SBER,GAZP&period=50")
    }

    @Test
    fun `backtest endpoints on empty history return zeroed result instead of failing`() {
        val backtest = assertOkJson("/api/v1/backtest/ZZZ_NO_CANDLES?days=30")
        assertEquals(0, backtest.get("totalTrades").asInt())
        assertEquals("ZZZ_NO_CANDLES", backtest.get("ticker").asString())

        val validation = assertOkJson("/api/v1/backtest/ZZZ_NO_CANDLES/validate?days=30&folds=2")
        assertEquals(0, validation.get("folds").asInt(), "no candles → no folds")
        assertEquals(0, validation.get("oosTrades").asInt())
        assertEquals(false, validation.get("robust").asBoolean())

        val robustness = assertOkJson("/api/v1/backtest/ZZZ_NO_CANDLES/robustness?days=30&simulations=10")
        assertEquals(false, robustness.get("robust").asBoolean())
        assertEquals(0, robustness.get("base").get("totalTrades").asInt())
    }

    @Test
    fun `holdout and deployment gate report missing history as 404 instead of 500`() {
        // Раньше пустая история давала IllegalArgumentException → 500 без @ControllerAdvice,
        // при этом /backtest и /validate отвечали 200 с нулями (docs/28 §3.1 №34).
        assertEquals(
            404,
            get("/api/v1/backtest/ZZZ_NO_CANDLES/holdout?days=30", adminToken()).statusCode(),
        )
        assertEquals(
            404,
            get("/api/v1/backtest/ZZZ_NO_CANDLES/deployment-gate?days=30", adminToken()).statusCode(),
        )
    }

    @Test
    fun `missing required query param is rejected with 400`() {
        // ticker обязателен для истории прогонов бэктеста.
        assertEquals(400, get("/api/v1/backtest/results", adminToken()).statusCode())
        // ticker обязателен для микроструктурных выборок.
        assertEquals(400, get("/api/v1/microstructure/snapshots", adminToken()).statusCode())
        // source обязателен (и валидируется) для CSV-выгрузки.
        assertEquals(400, get("/api/v1/microstructure/export.csv?ticker=CNYRUBF", adminToken()).statusCode())
        assertEquals(
            400,
            get("/api/v1/microstructure/export.csv?source=unknown&ticker=CNYRUBF", adminToken()).statusCode(),
        )
    }

    @Test
    fun `unknown trace key returns 404`() {
        assertEquals(404, get("/api/v1/traces?key=missing-trace-key", adminToken()).statusCode())
    }

    @Test
    fun `analytics role cannot trigger state changing get endpoints`() {
        val analyticsToken = login("test-analytics", "test-analytics-pass").get("accessToken").asString()

        // Read-only роль читает дашборд...
        assertEquals(200, get("/api/v1/settings", analyticsToken).statusCode())
        // ...но не запускает пересчёт/одобрение деплоя и не дёргает внешние backfill'ы.
        assertEquals(403, get("/api/v1/backtest/CNYRUBF/deployment-gate", analyticsToken).statusCode())
        assertEquals(403, get("/api/v1/backtest/CNYRUBF/validate", analyticsToken).statusCode())
        assertEquals(403, get("/api/v1/backtest/CNYRUBF/funding-history", analyticsToken).statusCode())
        assertEquals(403, get("/api/v1/macro/rates-history?days=1", analyticsToken).statusCode())
    }

    private fun assertOkJson(path: String): JsonNode {
        val response = get(path, adminToken())
        assertEquals(200, response.statusCode(), "GET $path failed: ${response.body()}")
        val body = response.body().trim()
        assertTrue(body.isNotEmpty(), "GET $path returned an empty body")
        return objectMapper.readTree(body)
    }

    private fun adminToken(): String = login("test-admin", "test-admin-pass").get("accessToken").asString()

    private fun login(
        username: String,
        password: String,
    ): JsonNode {
        val request =
            HttpRequest
                .newBuilder(URI("$baseUrl/api/v1/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""{"username":"$username","password":"$password"}"""))
                .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        assertEquals(200, response.statusCode(), "login failed for $username: ${response.body()}")
        return objectMapper.readTree(response.body())
    }

    private fun get(
        path: String,
        bearer: String,
    ): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(URI("$baseUrl$path"))
                .header("Authorization", "Bearer $bearer")
                .GET()
                .build()
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }
}
