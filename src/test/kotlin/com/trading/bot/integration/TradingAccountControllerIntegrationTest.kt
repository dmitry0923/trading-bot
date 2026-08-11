package com.trading.bot.integration

import com.trading.bot.model.InstrumentType
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.entity.OrderOutbox
import com.trading.bot.model.entity.OutboxStatus
import com.trading.bot.model.entity.Position
import com.trading.bot.repository.DailyRiskSnapshotRepository
import com.trading.bot.repository.OrderOutboxRepository
import com.trading.bot.repository.PositionRepository
import com.trading.bot.service.TradingAccountService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
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
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Интеграционные тесты accounts API (multi-account, roadmap v2.2) поверх реального
 * HTTP (RANDOM_PORT) и Postgres: CRUD, валидация, per-account dashboard и история
 * дневных P&L, защита удаления аккаунта с позициями / неотправленными outbox-ордерами.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TradingAccountControllerIntegrationTest : AbstractTestContainerTest() {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    lateinit var positionRepository: PositionRepository

    @Autowired
    lateinit var orderOutboxRepository: OrderOutboxRepository

    @Autowired
    lateinit var dailyRiskSnapshotRepository: DailyRiskSnapshotRepository

    @Autowired
    lateinit var tradingAccountService: TradingAccountService

    private val objectMapper = ObjectMapper()

    private val httpClient: HttpClient = HttpClient.newBuilder().build()

    private val baseUrl: String
        get() = "http://127.0.0.1:$port"

    @BeforeEach
    fun cleanup() {
        runBlocking {
            orderOutboxRepository.deleteAll()
            positionRepository.deleteAll()
            dailyRiskSnapshotRepository.deleteAll()
            tradingAccountService.findAll().forEach { tradingAccountService.delete(it.id!!) }
        }
    }

    @Test
    fun `accounts endpoints require authentication`() {
        assertEquals(401, get("/api/v1/accounts").statusCode())
        assertEquals(401, get("/api/v1/accounts/1").statusCode())
    }

    @Test
    fun `create read list update and delete round trip`() {
        val created = createAccount()
        assertEquals("Alpha", created.get("name").asString())
        assertEquals("P1000", created.get("alorPortfolio").asString())
        assertEquals("MOEX", created.get("exchange").asString())
        assertEquals(true, created.get("enabled").asBoolean())
        assertEquals(2, created.get("weight").asInt())
        assertEquals(1000000.0, created.get("aumRub").asDouble())
        assertEquals(20000.0, created.get("maxDailyLossRub").asDouble())
        val id = created.get("id").asLong()

        val fetched = objectMapper.readTree(get("/api/v1/accounts/$id", adminToken()).body())
        assertEquals("Alpha", fetched.get("name").asString())
        assertEquals(0, fetched.get("openPositions").size())
        assertEquals(0, fetched.get("openPositionsCount").asInt())

        val listed = objectMapper.readTree(get("/api/v1/accounts", adminToken()).body())
        assertEquals(1, listed.size())
        assertEquals(id, listed.first().get("id").asLong())
        assertEquals(0, listed.first().get("openPositions").asInt())

        val updated =
            objectMapper.readTree(
                putJson(
                    "/api/v1/accounts/$id",
                    """{"name":"Alpha-renamed","alorPortfolio":"P2000","exchange":"SPBX","enabled":false,"weight":3,"aumRub":null,"maxOpenPositions":null,"maxDailyLossRub":null}""",
                    adminToken(),
                ).body(),
            )
        assertEquals("Alpha-renamed", updated.get("name").asString())
        assertEquals(false, updated.get("enabled").asBoolean())
        assertEquals(3, updated.get("weight").asInt())
        assertEquals(true, updated.get("aumRub").isNull)

        val deleted = objectMapper.readTree(delete("/api/v1/accounts/$id", adminToken()).body())
        assertEquals(true, deleted.get("deleted").asBoolean())
        assertEquals(404, get("/api/v1/accounts/$id", adminToken()).statusCode())
    }

    @Test
    fun `create validates blank name blank portfolio and invalid weight`() {
        assertEquals(400, postJson("/api/v1/accounts", """{"name":" ","alorPortfolio":"P1"}""", adminToken()).statusCode())
        assertEquals(400, postJson("/api/v1/accounts", """{"name":"A","alorPortfolio":" "}""", adminToken()).statusCode())
        assertEquals(400, postJson("/api/v1/accounts", """{"name":"A","alorPortfolio":"P1","weight":0}""", adminToken()).statusCode())
    }

    @Test
    fun `analytics token can read but cannot create accounts`() {
        val analyticsToken = login("test-analytics", "test-analytics-pass").get("accessToken").asString()
        assertEquals(200, get("/api/v1/accounts", analyticsToken).statusCode())
        val response =
            postJson(
                "/api/v1/accounts",
                """{"name":"Blocked","alorPortfolio":"P9"}""",
                analyticsToken,
            )
        assertEquals(403, response.statusCode())
    }

    @Test
    fun `dashboard aggregates aum limits and open and closed positions per account`() {
        val id = createAccountWithAum().get("id").asLong()
        runBlocking {
            positionRepository.save(
                Position(
                    ticker = "SBER",
                    direction = PositionDirection.LONG,
                    quantity = 10,
                    entryPrice = BigDecimal("250"),
                    currentPrice = BigDecimal("275"),
                    pnl = BigDecimal("250"),
                    status = PositionStatus.OPEN,
                    instrumentType = InstrumentType.STOCK,
                    openedAt = LocalDateTime.now(),
                    accountId = id,
                ),
            )
            positionRepository.save(
                Position(
                    ticker = "GAZP",
                    direction = PositionDirection.LONG,
                    quantity = 5,
                    entryPrice = BigDecimal("200"),
                    closePrice = BigDecimal("170"),
                    pnl = BigDecimal("-150"),
                    status = PositionStatus.CLOSED,
                    instrumentType = InstrumentType.STOCK,
                    openedAt = LocalDateTime.now(),
                    closedAt = LocalDateTime.now(),
                    accountId = id,
                ),
            )
        }
        dailyRiskSnapshotRepository.upsert(LocalDate.now(), BigDecimal("300.5"), true, BigDecimal("-300.5"), id)

        val body = objectMapper.readTree(get("/api/v1/accounts/$id/dashboard", adminToken()).body())

        assertEquals("P1000", body.get("portfolio").asString())
        assertEquals(1000000.0, body.get("aum").asDouble())
        assertEquals(300.5, body.get("dailyPnl").asDouble())
        assertEquals(true, body.get("dailyLossLimitReached").asBoolean())
        assertEquals(true, body.get("entryBlocked").asBoolean())
        assertEquals(20000.0, body.get("maxDailyLossRub").asDouble())
        assertEquals(3, body.get("maxOpenPositions").asInt())
        assertEquals(1, body.get("openPositionsCount").asInt())
        assertEquals(250.0, body.get("openPnl").asDouble())
        assertEquals(-150.0, body.get("realizedPnlToday").asDouble())
        assertEquals(1, body.get("closedTodayCount").asInt())
        assertEquals(
            "SBER",
            body
                .get("openPositions")
                .first()
                .get("ticker")
                .asString(),
        )
    }

    @Test
    fun `dashboard returns 404 for unknown account`() {
        assertEquals(404, get("/api/v1/accounts/424242/dashboard", adminToken()).statusCode())
    }

    @Test
    fun `daily pnl history returns snapshot points per account ascending`() {
        val id = createAccountWithAum().get("id").asLong()
        val today = LocalDate.now()
        dailyRiskSnapshotRepository.upsert(today.minusDays(2), BigDecimal("-500"), true, BigDecimal("-500"), id)
        dailyRiskSnapshotRepository.upsert(today.minusDays(1), BigDecimal("1200"), false, BigDecimal.ZERO, id)

        val response = get("/api/v1/accounts/$id/daily-pnl?days=30", adminToken())
        assertEquals(200, response.statusCode(), "daily-pnl failed: ${response.body()}")
        val body = objectMapper.readTree(response.body())

        assertEquals(id, body.get("accountId").asLong())
        val points = body.get("points")
        assertEquals(2, points.size())
        assertEquals(today.minusDays(2).toString(), points.get(0).get("tradeDate").asString())
        assertEquals(-500.0, points.get(0).get("pnl").asDouble())
        assertEquals(true, points.get(0).get("limitReached").asBoolean())
        assertEquals(today.minusDays(1).toString(), points.get(1).get("tradeDate").asString())
    }

    @Test
    fun `delete is rejected when open positions reference the account`() {
        val id = createAccount().get("id").asLong()
        runBlocking {
            positionRepository.save(
                Position(
                    ticker = "SBER",
                    direction = PositionDirection.LONG,
                    quantity = 1,
                    entryPrice = BigDecimal("250"),
                    status = PositionStatus.OPEN,
                    instrumentType = InstrumentType.STOCK,
                    openedAt = LocalDateTime.now(),
                    accountId = id,
                ),
            )
        }

        val response = delete("/api/v1/accounts/$id", adminToken())
        assertEquals(409, response.statusCode())
        // Аккаунт остаётся (FK не позволяет удаление с историей) — доступен на чтение.
        assertEquals(200, get("/api/v1/accounts/$id", adminToken()).statusCode())
    }

    @Test
    fun `delete is rejected when undelivered outbox order references the account`() {
        val id = createAccount().get("id").asLong()
        runBlocking {
            orderOutboxRepository.save(
                OrderOutbox(
                    payloadJson = """{"ticker":"Si","side":"sell","qty":1,"price":"92000","type":"limit"}""",
                    status = OutboxStatus.PENDING,
                    accountId = id,
                ),
            )
        }

        val response = delete("/api/v1/accounts/$id", adminToken())
        assertEquals(409, response.statusCode())
        assertEquals(200, get("/api/v1/accounts/$id", adminToken()).statusCode())
    }

    @Test
    fun `dashboard filters positions and daily pnl by account`() {
        val accountA = createAccount().get("id").asLong()
        val accountB = createAccount().get("id").asLong()
        runBlocking {
            positionRepository.save(
                Position(
                    ticker = "SBER",
                    direction = PositionDirection.LONG,
                    quantity = 10,
                    entryPrice = BigDecimal("250"),
                    currentPrice = BigDecimal("275"),
                    pnl = BigDecimal("250"),
                    status = PositionStatus.OPEN,
                    instrumentType = InstrumentType.STOCK,
                    openedAt = LocalDateTime.now(),
                    accountId = accountA,
                ),
            )
            positionRepository.save(
                Position(
                    ticker = "GAZP",
                    direction = PositionDirection.LONG,
                    quantity = 5,
                    entryPrice = BigDecimal("200"),
                    closePrice = BigDecimal("170"),
                    pnl = BigDecimal("-150"),
                    status = PositionStatus.CLOSED,
                    instrumentType = InstrumentType.STOCK,
                    openedAt = LocalDateTime.now(),
                    closedAt = LocalDateTime.now(),
                    accountId = accountA,
                ),
            )
            positionRepository.save(
                Position(
                    ticker = "VTBR",
                    direction = PositionDirection.LONG,
                    quantity = 100,
                    entryPrice = BigDecimal("50"),
                    currentPrice = BigDecimal("55"),
                    pnl = BigDecimal("500"),
                    status = PositionStatus.OPEN,
                    instrumentType = InstrumentType.STOCK,
                    openedAt = LocalDateTime.now(),
                    accountId = accountB,
                ),
            )
        }
        dailyRiskSnapshotRepository.upsert(LocalDate.now(), BigDecimal("300.5"), false, BigDecimal.ZERO, accountA)

        val filtered =
            objectMapper.readTree(get("/api/v1/dashboard?accountId=$accountA", adminToken()).body())
        assertEquals(accountA, filtered.get("accountId").asLong())
        assertEquals(1, filtered.get("openPositionsCount").asInt())
        assertEquals(1, filtered.get("closedTodayCount").asInt())
        assertEquals(300.5, filtered.get("dailyPnl").asDouble())
        assertEquals(
            accountA,
            filtered
                .get("openPositions")
                .first()
                .get("accountId")
                .asLong(),
        )

        val aggregated = objectMapper.readTree(get("/api/v1/dashboard", adminToken()).body())
        assertTrue(aggregated.get("accountId").isNull)
        assertEquals(2, aggregated.get("openPositionsCount").asInt())
        assertEquals(1, aggregated.get("closedTodayCount").asInt())
    }

    @Test
    fun `dashboard with unknown account filter returns 404`() {
        assertEquals(404, get("/api/v1/dashboard?accountId=424242", adminToken()).statusCode())
    }

    @Test
    fun `dashboard stream with account filter sends filtered snapshot`() {
        val accountId = createAccount().get("id").asLong()
        runBlocking {
            positionRepository.save(
                Position(
                    ticker = "SBER",
                    direction = PositionDirection.LONG,
                    quantity = 10,
                    entryPrice = BigDecimal("250"),
                    status = PositionStatus.OPEN,
                    instrumentType = InstrumentType.STOCK,
                    openedAt = LocalDateTime.now(),
                    accountId = accountId,
                ),
            )
        }

        val request =
            HttpRequest
                .newBuilder(URI("$baseUrl/api/v1/dashboard/stream?accountId=$accountId"))
                .header("Authorization", "Bearer ${adminToken()}")
                .header("Accept", "text/event-stream")
                .GET()
                .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines())
        try {
            assertEquals(200, response.statusCode())
            val lines = response.body().iterator()
            val dataLine = lines.asSequence().first { it.startsWith("data:") }
            val snapshot = objectMapper.readTree(dataLine.removePrefix("data:"))

            assertEquals(accountId, snapshot.get("accountId").asLong())
            assertEquals(1, snapshot.get("openPositionsCount").asInt())
            assertEquals(
                "SBER",
                snapshot
                    .get("openPositions")
                    .first()
                    .get("ticker")
                    .asString(),
            )
        } finally {
            response.body().close()
        }
    }

    private fun createAccount(): JsonNode =
        objectMapper.readTree(
            postJson(
                "/api/v1/accounts",
                """{"name":"Alpha","alorPortfolio":"P1000","aumRub":1000000,"maxOpenPositions":3,"maxDailyLossRub":20000,"weight":2}""",
                adminToken(),
            ).body(),
        )

    private fun createAccountWithAum(): JsonNode = createAccount()

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

    private fun putJson(
        path: String,
        body: String,
        bearer: String,
    ): HttpResponse<String> {
        val builder =
            HttpRequest
                .newBuilder(URI("$baseUrl$path"))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .header("Authorization", "Bearer $bearer")
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun delete(
        path: String,
        bearer: String,
    ): HttpResponse<String> {
        val builder =
            HttpRequest
                .newBuilder(URI("$baseUrl$path"))
                .DELETE()
                .header("Authorization", "Bearer $bearer")
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }
}
