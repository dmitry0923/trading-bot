package com.trading.bot.integration

import com.trading.bot.model.entity.Candle
import com.trading.bot.repository.CandleRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * Интеграционные тесты «тяжёлых» и мутирующих эндпоинтов [com.trading.bot.controller.ApiController]
 * поверх реального HTTP (RANDOM_PORT) и Postgres/Redis.
 *
 * В отличие от [ApiControllerReadSmokeIntegrationTest] здесь эндпоинты бэктеста
 * вызываются на НЕ пустой истории: в `candles` засевается детерминированный ряд
 * `TEST_SYNTH` (900 баров MINUTE_10), поэтому реально исполняются:
 *  - `/backtest/{ticker}` (включая `includeTrades` и сборку signal generator),
 *  - `/validate` (включая ресемплинг в HOUR_1 и неподдерживаемый таймфрейм),
 *  - `/robustness` (Monte Carlo + stress),
 *  - `/holdout` (walk-forward на dev-части + независимый holdout),
 *  - `/deployment-gate` (весь пайплайн гейта и маппинг checks),
 *  - `POST /backtest/portfolio-check`.
 *
 * Плюс дешёвые мутирующие эндпоинты (управление торговлей, emergency stop,
 * эксперимент, инвесторы, RAG, ручные триггеры), проверяемые по фактическому
 * изменению состояния через последующий GET. Внешние сети намеренно отключены
 * (`funding.moex-history-url` пуст, RAG/trace-storage/news выключены дефолтом),
 * поэтому тесты не зависят от доступности MOEX/CBR/LLM/MinIO.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("integration")
class ApiControllerHeavyEndpointsIntegrationTest : AbstractTestContainerTest() {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    lateinit var candleRepository: CandleRepository

    private val objectMapper = ObjectMapper()

    private val httpClient: HttpClient = HttpClient.newBuilder().build()

    private var seeded = false

    private var cachedAdminToken: String? = null

    private val baseUrl: String
        get() = "http://127.0.0.1:$port"

    @BeforeEach
    fun seedSyntheticHistory() {
        if (seeded) return
        runBlocking { candleRepository.saveAll(syntheticCandles()) }
        seeded = true
    }

    @Test
    fun `backtest on seeded history returns metrics and trade log`() {
        val backtest = assertOkJson("/api/v1/backtest/$TICKER?days=$DAYS&includeTrades=true&mlDirectionEnabled=false")
        assertEquals(TICKER, backtest.get("ticker").asString())
        assertTrue(backtest.has("totalReturn"), "backtest must expose totalReturn: $backtest")
        assertTrue(backtest.has("sharpeRatio"))
        assertTrue(backtest.get("trades").isArray, "includeTrades must return a trade array: $backtest")
        assertTrue(backtest.has("passable"))
    }

    @Test
    fun `walk forward validation covers native resampled and unsupported timeframes`() {
        val native = assertOkJson("/api/v1/backtest/$TICKER/validate?days=$DAYS&folds=2&includeOosTrades=true")
        assertEquals(TICKER, native.get("ticker").asString())
        assertEquals("MINUTE_10", native.get("timeframe").asString())
        assertEquals(2, native.get("folds").asInt(), "900 bars / folds=2 must produce 2 folds: $native")
        assertTrue(native.has("oosTrades"))
        assertTrue(native.has("consistency"))
        assertTrue(native.has("robust"))
        assertTrue(native.get("oosFoldTrades").isArray, "includeOosTrades must expose per-fold trades: $native")

        // Ресемплинг MINUTE_10 → HOUR_1 (isResampleTimeframe = true).
        val hourly = assertOkJson("/api/v1/backtest/$TICKER/validate?days=$DAYS&folds=2&timeframe=HOUR_1")
        assertEquals("HOUR_1", hourly.get("timeframe").asString())
        assertEquals(2, hourly.get("folds").asInt(), "150 resampled HOUR_1 bars must support 2 folds: $hourly")

        // Неподдерживаемый таймфрейм — resample не выполняется, свечей такого ТФ нет.
        val unsupported = assertOkJson("/api/v1/backtest/$TICKER/validate?days=$DAYS&folds=2&timeframe=MICROSECOND_1")
        assertEquals("MICROSECOND_1", unsupported.get("timeframe").asString())
        assertEquals(0, unsupported.get("folds").asInt())
        assertEquals(0, unsupported.get("oosTrades").asInt())
    }

    @Test
    fun `robustness returns monte carlo and stress report`() {
        val robustness = assertOkJson("/api/v1/backtest/$TICKER/robustness?days=$DAYS&simulations=10&mlDirectionEnabled=false")
        assertEquals(TICKER, robustness.get("ticker").asString())
        assertTrue(robustness.has("robust"), "robustness must expose verdict: $robustness")
        assertTrue(robustness.get("base").has("totalTrades"), "base scenario must be mapped: $robustness")
        val monteCarlo = robustness.get("monteCarlo")
        assertTrue(monteCarlo.has("medianReturn"))
        assertTrue(monteCarlo.has("p5Return"))
        assertTrue(monteCarlo.has("probabilityOfLoss"))
        assertTrue(robustness.get("stress").isArray, "stress scenarios must be an array: $robustness")
    }

    @Test
    fun `holdout validation splits dev and independent holdout`() {
        val holdout = assertOkJson("/api/v1/backtest/$TICKER/holdout?days=$DAYS&folds=2")
        assertEquals(TICKER, holdout.get("ticker").asString())
        assertEquals(0.2, holdout.get("holdoutFraction").asDouble(), 1e-9)
        assertTrue(holdout.has("passed"), "holdout must expose verdict: $holdout")
        assertTrue(holdout.has("holdoutTrades"))
        assertTrue(holdout.has("wfaOosTrades"))
        assertTrue(holdout.has("wfaConsistency"))
        val paramsUsed = holdout.get("paramsUsed")
        assertTrue(paramsUsed.has("slPercent"))
        assertTrue(paramsUsed.has("tpPercent"))

        // Явный research-override confidence должен собирать signal generator
        // (ветка adaptiveConfidenceThreshold != null) и не ломать пайплайн.
        val withConfidenceOverride =
            assertOkJson("/api/v1/backtest/$TICKER/holdout?days=$DAYS&folds=2&adaptiveConfidenceThreshold=0.6")
        assertTrue(withConfidenceOverride.has("passed"))
        assertEquals(TICKER, withConfidenceOverride.get("ticker").asString())
    }

    @Test
    fun `deployment gate runs full pipeline and reports checks`() {
        val gate = assertOkJson("/api/v1/backtest/$TICKER/deployment-gate?days=$DAYS&folds=2")
        assertEquals(TICKER, gate.get("ticker").asString())
        val status = gate.get("status").asString()
        assertTrue(
            status in setOf("REJECTED", "RESEARCH_ONLY", "PAPER_ALLOWED", "LIVE_ALLOWED"),
            "unexpected deployment status: $status",
        )
        // Синтетическая история не может быть LIVE-одобрена (research gate + слабые метрики).
        assertFalse(gate.get("liveAllowed").asBoolean(), "synthetic history must never be LIVE allowed")
        assertFalse(gate.get("liveApprovalActive").asBoolean())
        assertEquals(true, gate.get("researchMode").asBoolean())
        val checks = gate.get("checks")
        assertTrue(checks.size() >= 5, "deployment gate must expose all checks: $gate")
        checks.forEach { check ->
            assertTrue(check.has("key"), "check without key: $check")
            assertTrue(check.has("label"))
            assertTrue(check.has("passed"))
        }
    }

    @Test
    fun `panel backtest and stored run history expose metrics`() {
        // `/validate` персистит прогон в backtest_results (roadmap 13.7.3) — проверяем
        // историю прогонов на живых данных, а не на пустой таблице.
        assertOkJson("/api/v1/backtest/$TICKER/validate?days=$DAYS&folds=2")

        val history = assertOkJson("/api/v1/backtest/results?ticker=$TICKER&limit=5")
        assertEquals(TICKER, history.get("ticker").asString())
        val results = history.get("results")
        assertTrue(results.size() >= 1, "validate must persist the run: $history")
        val stored = results.get(0)
        assertTrue(stored.has("id"), "stored run must expose id: $stored")
        assertTrue(stored.has("params"))
        assertTrue(stored.has("metrics"))
        assertTrue(stored.has("createdAt"))

        val panel =
            assertOkJson(
                "/api/v1/backtest/panel",
                method = "POST",
                body = """{"tickers":["$TICKER"],"days":$DAYS}""",
            )
        assertEquals(TICKER, panel.get("tickers").get(0).asString())
        assertEquals(1, panel.get("summary").get("tickerCount").asInt())
        assertEquals(1, panel.get("results").size(), "one ticker per request: $panel")
        val tickerResult = panel.get("results").get(0)
        assertEquals(TICKER, tickerResult.get("ticker").asString())
        assertTrue(tickerResult.has("totalReturn"))
        assertTrue(tickerResult.has("passable"))
    }

    @Test
    fun `portfolio backtest check returns verdict for configured tickers`() {
        val response = post("/api/v1/backtest/portfolio-check")
        assertEquals(200, response.statusCode(), "portfolio-check failed: ${response.body()}")
        val body = objectMapper.readTree(response.body())
        assertTrue(body.has("accepted"), "portfolio check must expose verdict: $body")
        assertTrue(body.get("tickerCount").asInt() > 0, "portfolio check must cover configured tickers: $body")
        assertEquals(false, body.get("accepted").asBoolean(), "empty history cannot pass the portfolio gate")
        assertTrue(body.get("results").isArray)
    }

    @Test
    fun `funding history backfill is a no-op without configured moex url`() {
        // `funding.moex-history-url` пуст (см. dynamic property) → загрузчик не ходит в MOEX.
        val backfill = assertOkJson("/api/v1/backtest/$TICKER/funding-history?days=5")
        assertEquals(TICKER, backfill.get("ticker").asString())
        assertEquals(0, backfill.get("loaded").asInt())
        assertEquals(0, backfill.get("saved").asInt())
    }

    @Test
    fun `trading control endpoints toggle settings and force close clock`() {
        val enabled = assertOkJson("/api/v1/trading/enable", method = "POST")
        assertEquals(true, enabled.get("tradingEnabled").asBoolean())
        assertEquals(true, assertOkJson("/api/v1/settings").get("tradingEnabled").asBoolean())

        val disabled = assertOkJson("/api/v1/trading/disable", method = "POST")
        assertEquals(false, disabled.get("tradingEnabled").asBoolean())
        assertEquals(false, assertOkJson("/api/v1/settings").get("tradingEnabled").asBoolean())

        // Позиций нет → закрывать нечего, но эндпоинт обязан вернуть счётчик.
        val forceClose = assertOkJson("/api/v1/trading/force-close", method = "POST")
        assertEquals(0, forceClose.get("closed").asInt())

        val scheduled = assertOkJson("/api/v1/trading/force-close-at?time=18:45", method = "POST")
        assertEquals(true, scheduled.get("forceCloseEnabled").asBoolean())
        assertEquals("18:45", scheduled.get("forceCloseTime").asString())
        val settingsAfterSchedule = assertOkJson("/api/v1/settings")
        assertEquals(true, settingsAfterSchedule.get("forceCloseEnabled").asBoolean())
        assertEquals("18:45", settingsAfterSchedule.get("forceCloseTime").asString())

        val cancelled = assertOkJson("/api/v1/trading/force-close-cancel", method = "POST")
        assertEquals(false, cancelled.get("forceCloseEnabled").asBoolean())
        assertEquals("", assertOkJson("/api/v1/settings").get("forceCloseTime").asString())

        // Возвращаем торговлю во включённое состояние, чтобы не влиять на другие тесты класса.
        assertOkJson("/api/v1/trading/enable", method = "POST")
    }

    @Test
    fun `emergency stop blocks trading and resume lifts it`() {
        val stopped =
            assertOkJson(
                "/api/v1/bot/emergency-stop",
                method = "POST",
                body = """{"reason":"coverage-test","source":"auto","liquidate":false}""",
            )
        assertEquals(true, stopped.get("stopped").asBoolean())
        assertEquals("coverage-test", stopped.get("reason").asString())
        assertEquals("AUTO", stopped.get("source").asString())
        assertEquals(0, stopped.get("positionsLiquidated").asInt())

        val manual = assertOkJson("/api/v1/bot/emergency-stop", method = "POST", body = """{"liquidate":false}""")
        assertEquals("MANUAL_OPERATOR", manual.get("reason").asString())
        assertEquals("MANUAL", manual.get("source").asString())

        val resumed = assertOkJson("/api/v1/bot/resume", method = "POST")
        assertEquals(false, resumed.get("stopped").asBoolean())
    }

    @Test
    fun `experiment toggle persists to settings`() {
        val on = assertOkJson("/api/v1/experiment/enable?enabled=true", method = "POST")
        assertEquals(true, on.get("experimentEnabled").asBoolean())
        assertEquals(true, assertOkJson("/api/v1/settings").get("experimentEnabled").asBoolean())

        val off = assertOkJson("/api/v1/experiment/enable?enabled=false", method = "POST")
        assertEquals(false, off.get("experimentEnabled").asBoolean())
        assertEquals(false, assertOkJson("/api/v1/settings").get("experimentEnabled").asBoolean())
    }

    @Test
    fun `investor can be created via api`() {
        val investor =
            assertOkJson(
                "/api/v1/investors",
                method = "POST",
                body = """{"name":"Coverage Investor","email":"coverage@example.com","initialDeposit":"1000"}""",
            )
        assertEquals("Coverage Investor", investor.get("investor").get("name").asString())
        assertEquals("coverage@example.com", investor.get("investor").get("email").asString())
        assertTrue(investor.get("realizedPnL").isNumber, "investor view must expose realized P&L: $investor")
    }

    @Test
    fun `rag endpoints answer without llm when rag is disabled`() {
        val analysis = assertOkJson("/api/v1/rag/analyze", method = "POST", body = """{"query":"connection reset"}""")
        assertEquals("DISABLED", analysis.get("mode").asString())
        assertEquals("connection reset", analysis.get("query").asString())

        val traceAnalysis =
            assertOkJson("/api/v1/rag/analyze-trace", method = "POST", body = """{"storageKey":"missing-key"}""")
        assertEquals("RULE_BASED", traceAnalysis.get("mode").asString())
        assertTrue(traceAnalysis.get("report").asString().contains("Trace not found"))

        val refresh = assertOkJson("/api/v1/rag/refresh", method = "POST")
        assertTrue(refresh.has("indexed"), "refresh must report indexed size: $refresh")
        assertTrue(refresh.get("status").has("enabled"))
    }

    @Test
    fun `manual cycle triggers are accepted`() {
        assertEquals(200, post("/api/v1/strategy/trigger").statusCode())
        assertEquals(200, post("/api/v1/bot/trigger").statusCode())
    }

    @Test
    fun `dashboard stream rejects unknown account before opening emitter`() {
        assertEquals(
            404,
            get("/api/v1/dashboard/stream?accountId=424242").statusCode(),
        )
    }

    // ---------- helpers ----------

    /**
     * Детерминированный ряд MINUTE_10: медленный рост + пилообразная волна.
     * Цена и объём фиксированы, поэтому прогоны воспроизводимы (без random).
     */
    private fun syntheticCandles(): List<Candle> {
        val lastBar = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES).minusMinutes(BAR_MINUTES.toLong())
        return (0 until BAR_COUNT).map { i ->
            val close = 100.0 + i * 0.02 + (((i * 7) % 17) - 8) * 0.12
            val open = if (i == 0) close else 100.0 + (i - 1) * 0.02 + ((((i - 1) * 7) % 17) - 8) * 0.12
            Candle(
                ticker = TICKER,
                timeframe = TIMEFRAME,
                openPrice = decimal(open),
                highPrice = decimal(maxOf(open, close) + 0.1),
                lowPrice = decimal(minOf(open, close) - 0.1),
                closePrice = decimal(close),
                volume = 1_000L + (i * 3) % 500,
                time = lastBar.minusMinutes((BAR_COUNT - 1 - i) * BAR_MINUTES.toLong()),
            )
        }
    }

    private fun decimal(value: Double): BigDecimal = BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP)

    private fun assertOkJson(
        path: String,
        method: String = "GET",
        body: String = "",
    ): JsonNode {
        val response = if (method == "POST") post(path, body) else get(path)
        assertEquals(200, response.statusCode(), "$method $path failed: ${response.body()}")
        val payload = response.body().trim()
        assertTrue(payload.isNotEmpty(), "$method $path returned an empty body")
        return objectMapper.readTree(payload)
    }

    private fun adminToken(): String {
        cachedAdminToken?.let { return it }
        val response =
            post(
                "/api/v1/auth/login",
                """{"username":"test-admin","password":"test-admin-pass"}""",
                authenticated = false,
            )
        assertEquals(200, response.statusCode(), "login failed: ${response.body()}")
        val token = objectMapper.readTree(response.body()).get("accessToken").asString()
        cachedAdminToken = token
        return token
    }

    private fun get(path: String): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(URI("$baseUrl$path"))
                .header("Authorization", "Bearer ${adminToken()}")
                .GET()
                .build()
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun post(
        path: String,
        body: String = "",
        authenticated: Boolean = true,
    ): HttpResponse<String> {
        val builder =
            HttpRequest
                .newBuilder(URI("$baseUrl$path"))
                .header("Content-Type", "application/json")
        if (authenticated) builder.header("Authorization", "Bearer ${adminToken()}")
        return httpClient.send(
            builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
    }

    companion object {
        private const val TICKER = "TEST_SYNTH"
        private const val TIMEFRAME = "MINUTE_10"
        private const val BAR_COUNT = 900
        private const val BAR_MINUTES = 10
        private const val DAYS = 30

        /**
         * `funding.moex-history-url` по умолчанию пуст, но тест не должен зависеть
         * от окружения: пустое значение гарантирует, что `/funding-history` не
         * пойдёт в MOEX ISS (иначе прогон зависел бы от внешней сети).
         */
        @DynamicPropertySource
        @JvmStatic
        @Suppress("unused") // Вызывается рефлексивно Spring TestContext через @DynamicPropertySource
        fun disableExternalBackfills(registry: DynamicPropertyRegistry) {
            registry.add("funding.moex-history-url") { "" }
        }
    }
}
