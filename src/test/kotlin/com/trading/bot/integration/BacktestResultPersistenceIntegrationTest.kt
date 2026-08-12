package com.trading.bot.integration

import com.trading.bot.backtest.BacktestEngine
import com.trading.bot.model.entity.BacktestResultEntity
import com.trading.bot.model.entity.Candle
import com.trading.bot.repository.BacktestResultRepository
import com.trading.bot.repository.CandleRepository
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Интеграционные тесты персиста результатов бэктеста (roadmap v2.2, 13.7.3)
 * поверх реального Postgres и HTTP (RANDOM_PORT):
 *  - `backtest_results`: jsonb round-trip и выборка по убыванию времени;
 *  - движок автосохраняет результат прогона и инкрементирует `bt_pass_total`;
 *  - пустые прогоны не сохраняются;
 *  - `GET /api/v1/backtest/results` отдаёт сохранённые прогоны;
 *  - `/backtest/{ticker}/validate` персистит walk-forward OOS-сводку.
 *
 * Свечи для прогона — закоммиченная фикстура реальных данных MOEX ISS
 * (10-мин, SBER, апрель-август 2026) из `src/test/resources/fixtures`,
 * та же, что в `RealDataBacktestFixtureTest` (гарантированно даёт сделки).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BacktestResultPersistenceIntegrationTest : AbstractTestContainerTest() {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    lateinit var backtestResultRepository: BacktestResultRepository

    @Autowired
    lateinit var candleRepository: CandleRepository

    @Autowired
    lateinit var backtestEngine: BacktestEngine

    @Autowired
    lateinit var meterRegistry: MeterRegistry

    private val objectMapper = ObjectMapper()

    private val httpClient: HttpClient = HttpClient.newBuilder().build()

    private val baseUrl: String
        get() = "http://127.0.0.1:$port"

    @Test
    fun `repository round-trips jsonb records newest first`() {
        runBlocking {
            val older =
                BacktestResultEntity(
                    ticker = "ZZZBT",
                    params = """{"days":365,"timeframe":"MINUTE_10"}""",
                    metrics = """{"totalTrades":12,"passable":false}""",
                    createdAt = LocalDateTime.now().minusMinutes(10),
                )
            val newer =
                BacktestResultEntity(
                    ticker = "ZZZBT",
                    params = """{"days":30,"timeframe":"MINUTE_10"}""",
                    metrics = """{"totalTrades":20,"passable":false}""",
                    oos = """{"consistency":0.5,"robust":false}""",
                )
            backtestResultRepository.save(older)
            backtestResultRepository.save(newer)

            val recent = backtestResultRepository.findRecent("ZZZBT", 10)
            assertEquals(2, recent.size)
            // Порядок: newest first (created_at DESC).
            assertEquals(20, objectMapper.readTree(recent[0].metrics).get("totalTrades").asInt())
            assertEquals(12, objectMapper.readTree(recent[1].metrics).get("totalTrades").asInt())
            // OOS: null для старого прогона, заполнен для нового.
            assertNotNull(recent[0].oos)
            assertTrue(recent[0].oos!!.contains("robust"))
            assertNull(recent[1].oos)
            assertNotNull(recent[0].id)
        }
    }

    @Test
    fun `engine run auto-persists result and increments bt_pass_total`() {
        val candles = loadFixtureCandles()
        runBlocking { candleRepository.saveAll(candles) }

        val result = runBlocking { backtestEngine.run("SBER", days = 365) }
        assertTrue(result.totalTrades > 0, "expected trades on real fixture, got ${result.totalTrades}")

        val records = runBlocking { backtestResultRepository.findRecent("SBER", 10) }
        assertTrue(records.isNotEmpty(), "engine run must persist a record")
        val record = records.first()
        assertEquals("SBER", record.ticker)

        val params = objectMapper.readTree(record.params)
        assertEquals(365, params.get("days").asInt())
        assertEquals("MINUTE_10", params.get("timeframe").asString())

        val metrics = objectMapper.readTree(record.metrics)
        assertEquals(result.totalTrades, metrics.get("totalTrades").asInt())
        assertEquals(result.isPassable(), metrics.get("passable").asBoolean())

        val tag = if (result.isPassable()) "PASS" else "REJECT"
        val counter = meterRegistry.find("bt_pass_total").tag("result", tag).counter()
        assertNotNull(counter, "bt_pass_total{result=$tag} must be registered")
        assertEquals(1.0, counter!!.count(), 1e-9)
    }

    @Test
    fun `empty run is not persisted`() {
        runBlocking { backtestEngine.run("EMPTYBT", days = 30) }

        val records = runBlocking { backtestResultRepository.findRecent("EMPTYBT", 10) }
        assertTrue(records.isEmpty(), "empty run must not be persisted")
    }

    @Test
    fun `results endpoint returns persisted records with parsed json`() {
        runBlocking {
            backtestResultRepository.save(
                BacktestResultEntity(
                    ticker = "HTTPBT",
                    params = """{"days":365}""",
                    metrics = """{"totalTrades":5,"passable":false}""",
                    oos = """{"robust":false}""",
                ),
            )
        }

        val response = get("/api/v1/backtest/results?ticker=HTTPBT&limit=10", adminToken())
        assertEquals(200, response.statusCode())

        val body = objectMapper.readTree(response.body())
        assertEquals("HTTPBT", body.get("ticker").asString())
        val results = body.get("results")
        assertTrue(results.size() >= 1)
        val first = results.get(0)
        assertNotNull(first.get("id"))
        assertTrue(first.get("params").has("days"))
        assertEquals(5, first.get("metrics").get("totalTrades").asInt())
        assertTrue(first.get("oos").has("robust"))
        assertTrue(first.hasNonNull("createdAt"))
    }

    @Test
    fun `validate endpoint persists walk-forward oos summary`() {
        val validateResponse = get("/api/v1/backtest/VALBT/validate?days=365&folds=2", adminToken())
        assertEquals(200, validateResponse.statusCode())

        val resultsResponse = get("/api/v1/backtest/results?ticker=VALBT&limit=10", adminToken())
        assertEquals(200, resultsResponse.statusCode())
        val body = objectMapper.readTree(resultsResponse.body())
        val results = body.get("results")
        assertTrue(results.size() >= 1, "validate must persist an oos record")
        val first = results.get(0)
        assertTrue(first.get("params").has("folds"))
        assertTrue(first.get("oos").has("robust"))
        assertTrue(first.get("oos").has("consistency"))
        assertTrue(first.get("oos").has("oosTrades"))
    }

    @Test
    fun `panel endpoint backtests multiple tickers and computes distribution`() {
        val candles = loadFixtureCandles()
        runBlocking { candleRepository.saveAll(candles) }

        val response =
            postJson(
                "/api/v1/backtest/panel",
                """{"tickers":["SBER","NODATABT"],"days":365}""",
                adminToken(),
            )
        assertEquals(200, response.statusCode())

        val body = objectMapper.readTree(response.body())
        assertEquals("MINUTE_10", body.get("timeframe").asString())
        assertEquals(365, body.get("days").asInt())
        val results = body.get("results")
        assertEquals(2, results.size())

        // SBER — реальная фикстура, сохранена ранее.
        val sber = results.find { it.get("ticker").asString() == "SBER" }
        assertNotNull(sber)
        assertTrue(sber!!.get("totalTrades").asInt() > 0)

        // NODATABT — нет данных: пустой результат.
        val noData = results.find { it.get("ticker").asString() == "NODATABT" }
        assertNotNull(noData)
        assertEquals(0, noData!!.get("totalTrades").asInt())

        val summary = body.get("summary")
        assertEquals(2, summary.get("tickerCount").asInt())
        assertTrue(summary.get("totalTrades").asInt() >= 1)
        assertTrue(summary.has("passShare"))
        assertTrue(summary.has("medianTotalReturn"))
        assertTrue(summary.has("minTotalReturn"))
        assertTrue(summary.has("maxTotalReturn"))
    }

    @Test
    fun `panel endpoint rejects empty tickers`() {
        val response =
            postJson(
                "/api/v1/backtest/panel",
                """{"tickers":[]}""",
                adminToken(),
            )
        assertEquals(400, response.statusCode())
    }

    private fun loadFixtureCandles(): List<Candle> {
        val stream = checkNotNull(javaClass.classLoader.getResourceAsStream("fixtures/moex_sber_minute10.csv"))
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        return stream
            .bufferedReader()
            .useLines { lines ->
                lines
                    .drop(1)
                    .filter { it.isNotBlank() }
                    .map { line ->
                        val p = line.split(",")
                        Candle(
                            ticker = "SBER",
                            timeframe = "MINUTE_10",
                            time = LocalDateTime.parse(p[0], formatter),
                            openPrice = BigDecimal(p[1]),
                            highPrice = BigDecimal(p[2]),
                            lowPrice = BigDecimal(p[3]),
                            closePrice = BigDecimal(p[4]),
                            volume = p[5].toLong(),
                        )
                    }.toList()
            }
    }

    private fun adminToken(): String = login().get("accessToken").asString()

    private fun login(): JsonNode {
        val response =
            postJson(
                "/api/v1/auth/login",
                """{"username":"test-admin","password":"test-admin-pass"}""",
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
