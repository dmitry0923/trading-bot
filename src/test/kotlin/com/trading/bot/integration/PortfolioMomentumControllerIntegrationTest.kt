package com.trading.bot.integration

import com.trading.bot.model.entity.Candle
import com.trading.bot.repository.CandleRepository
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.LocalDateTime
import kotlin.math.exp

/**
 * Интеграционные тесты research-эндпоинтов cross-sectional momentum (`docs/26`) поверх
 * реального HTTP (RANDOM_PORT) и Postgres: аутентификация, прогон с IS/OOS-сплитом,
 * walk-forward, портфельный deployment-gate и отказ на некорректных параметрах.
 *
 * Свечи трёх инструментов засеяны детерминированно (400 баров MINUTE_10 на тикер) и
 * удаляются в конце — данные не утекают в другие интеграционные классы.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("integration")
class PortfolioMomentumControllerIntegrationTest : AbstractTestContainerTest() {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    lateinit var candleRepository: CandleRepository

    @Autowired
    lateinit var databaseClient: DatabaseClient

    private val objectMapper = ObjectMapper()

    private val httpClient: HttpClient = HttpClient.newBuilder().build()

    private val universe = listOf("CNYRUBF", "USDRUBF", "EURRUBF")

    private val timeframe = "MINUTE_10"

    private val bars = 500

    private val start: LocalDateTime = historyStart()

    private fun historyStart(): LocalDateTime {
        val now = LocalDateTime.now()
        val weekAgo = now.minusDays(6)
        return weekAgo.withSecond(0).withNano(0)
    }

    private val baseUrl: String
        get() = "http://127.0.0.1:$port"

    @BeforeEach
    fun seed() {
        runBlocking {
            deleteCandles()
            candleRepository.saveAll(candles())
        }
    }

    @AfterEach
    fun cleanup() {
        runBlocking { deleteCandles() }
    }

    private fun candles(): List<Candle> =
        universe.flatMapIndexed { leg, ticker ->
            val drift = 0.0006 - 0.0006 * leg
            (0 until bars).map { index ->
                val price = BigDecimal.valueOf(exp(2.4 + drift * index))
                Candle(
                    ticker = ticker,
                    timeframe = timeframe,
                    openPrice = price,
                    highPrice = price,
                    lowPrice = price,
                    closePrice = price,
                    volume = 1_000L,
                    time = start.plusMinutes(10L * index),
                )
            }
        }

    private suspend fun deleteCandles() {
        val placeholders = universe.indices.joinToString(", ") { ":ticker_$it" }
        var spec = databaseClient.sql("DELETE FROM candles WHERE ticker IN ($placeholders)")
        universe.forEachIndexed { index, ticker -> spec = spec.bind("ticker_$index", ticker) }
        spec.fetch().rowsUpdated().awaitSingle()
    }

    @Test
    fun `momentum endpoints require authentication`() {
        assertEquals(401, get("/api/v1/portfolio/momentum").statusCode())
        assertEquals(401, get("/api/v1/portfolio/momentum/validate").statusCode())
        assertEquals(401, get("/api/v1/portfolio/momentum/deployment-gate").statusCode())
    }

    @Test
    fun `momentum backtest returns IS and OOS metrics`() {
        val response = get(momentumQuery("/api/v1/portfolio/momentum"), adminToken())

        assertEquals(200, response.statusCode(), "momentum failed: ${response.body()}")
        val body = objectMapper.readTree(response.body())
        assertEquals(3, body.get("universe").size())
        assertEquals(timeframe, body.get("timeframe").asString())
        assertEquals(bars, body.get("bars").asInt())
        assertTrue(body.get("rebalances").asInt() > 0, "portfolio must rebalance")
        assertTrue(body.get("verdict").asString().isNotBlank())
        assertTrue(body.get("full").has("totalReturn"))
        assertTrue(body.get("outOfSample").has("profitFactor"))
    }

    @Test
    fun `momentum validate returns walk forward folds on the dev slice`() {
        val response = get(momentumQuery("/api/v1/portfolio/momentum/validate", "&folds=3&holdoutFraction=0.30"), adminToken())

        assertEquals(200, response.statusCode(), "validate failed: ${response.body()}")
        val body = objectMapper.readTree(response.body())
        assertEquals(3, body.get("folds").asInt())
        assertEquals((bars * 0.7).toInt(), body.get("bars").asInt())
        assertEquals(3, body.get("foldResults").size())
        assertEquals(0.30, body.get("holdoutFraction").asDouble(), 1e-9)
        assertTrue(body.get("aggregate").has("totalTrades"))
    }

    @Test
    fun `deployment gate returns consolidated checks and never allows live in research mode`() {
        val response =
            get(
                momentumQuery(
                    "/api/v1/portfolio/momentum/deployment-gate",
                    "&folds=3&holdoutFraction=0.30&simulations=25&seed=7",
                ),
                adminToken(),
            )

        assertEquals(200, response.statusCode(), "deployment-gate failed: ${response.body()}")
        val body = objectMapper.readTree(response.body())
        assertTrue(body.get("status").asString().isNotBlank())
        assertEquals(3, body.get("foldsRequested").asInt())
        assertTrue(body.get("checks").size() > 0, "gate must report its checks")
        assertFalse(body.get("liveAllowed").asBoolean(), "research mode never allows live")
        assertTrue(body.get("researchMode").asBoolean())
        assertFalse(body.get("confirmedForProduction").asBoolean())
        assertTrue(body.get("devBacktest").has("sharpeRatio"))
        assertTrue(body.get("holdout").has("totalTrades"))
    }

    @Test
    fun `malformed numeric parameter is rejected`() {
        assertEquals(400, get("/api/v1/portfolio/momentum?days=abc", adminToken()).statusCode())
        assertEquals(400, get("/api/v1/portfolio/momentum/deployment-gate?holdoutFraction=abc", adminToken()).statusCode())
    }

    @Test
    fun `unknown instrument in the universe is rejected`() {
        // Теперь есть ApiExceptionHandler: ошибка входных данных отдаётся как 400
        // с телом {"error": ...}, а не 500 (docs/28 §3.1 №38).
        val response = get("/api/v1/portfolio/momentum?universe=NOSUCHTICKER&days=30", adminToken())
        assertEquals(400, response.statusCode(), "unknown ticker must be a 400, got ${response.body()}")
        val body = objectMapper.readTree(response.body())
        assertTrue(body.get("error").asString().contains("NOSUCHTICKER"), "error must name the ticker: $body")
    }

    /** Общий набор query-параметров прогона (маленький lookback, чтобы движок реально торговал). */
    private fun momentumQuery(
        path: String,
        extra: String = "",
    ): String =
        path +
            "?universe=${universe.joinToString(",")}" +
            "&days=30&lookbackBars=60&rebalanceBars=40&minUniverse=3&capitalSlice=0.5" +
            extra

    private fun adminToken(): String = login("test-admin", "test-admin-pass").get("accessToken").asString()

    private fun login(
        username: String,
        password: String,
    ): JsonNode {
        val response =
            postJson(
                "/api/v1/auth/login",
                """{"username":"$username","password":"$password"}""",
            )
        assertEquals(200, response.statusCode(), "login failed: ${response.body()}")
        return objectMapper.readTree(response.body())
    }

    private fun get(
        path: String,
        bearer: String? = null,
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("$baseUrl$path"))
        if (bearer != null) {
            builder.header("Authorization", "Bearer $bearer")
        }
        return httpClient.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun postJson(
        path: String,
        body: String,
    ): HttpResponse<String> {
        val builder =
            HttpRequest
                .newBuilder(URI("$baseUrl$path"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }
}
