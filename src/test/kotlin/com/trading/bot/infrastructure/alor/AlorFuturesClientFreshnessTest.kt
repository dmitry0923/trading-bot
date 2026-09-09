package com.trading.bot.infrastructure.alor

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.trading.bot.config.AlorConfig
import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.config.RiskConfig
import com.trading.bot.config.TradingConfig
import com.trading.bot.model.PositionDirection
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * P0-аудит (fresh GO/balance): в LIVE AlorFuturesClient кэширует ГО и свободные
 * средства с TTL [RiskConfig.maxGoAgeMs]. В пределах TTL — повторный запрос не
 * выполняется; по истечении TTL — перезапрос; при недоступности API и устаревшем
 * кэше возвращается null (fail-closed: сайзинг по устаревшим данным запрещён).
 */
class AlorFuturesClientFreshnessTest {
    private val instrumentsConfig = InstrumentsConfig()

    @Test
    fun `live GO served from cache within TTL without repeated API call`() {
        val ctx =
            liveServer(
                goBody = """{"long": {"initialMargin": "850"}}""",
                summariesBody = """{"moneyAmount": "50000"}""",
                maxGoAgeMs = 5000,
            )
        try {
            val first = runBlocking { ctx.client.getFuturesGO("CNYRUBF") }
            val second = runBlocking { ctx.client.getFuturesGO("CNYRUBF") }

            assertEquals(0, BigDecimal("850").compareTo(first!!))
            assertEquals(0, BigDecimal("850").compareTo(second!!))
            assertEquals(1, ctx.goHits.get(), "second call within TTL must not hit the API")
        } finally {
            ctx.server.stop(0)
        }
    }

    @Test
    fun `live GO publishes cache age metric for monitoring`() {
        val ctx =
            liveServer(
                goBody = """{"long": {"initialMargin": "850"}}""",
                summariesBody = """{"moneyAmount": "50000"}""",
                maxGoAgeMs = 80,
            )
        try {
            runBlocking { ctx.client.getFuturesGO("CNYRUBF") }

            val freshAge =
                ctx.meterRegistry
                    .find("futures.go_cache_age_ms")
                    .tags("fresh", "true")
                    .gauges()
                    .firstOrNull()
            assertEquals(0.0, freshAge?.value() ?: -1.0, 0.0, "fresh GO cache age must be 0ms")

            Thread.sleep(150)
            // после TTL кэш устарел, но не феился (сервер жив) — следующий вызов обновит (age 0).
            runBlocking { ctx.client.getFuturesGO("CNYRUBF") }

            Thread.sleep(150)
            ctx.fail.set(true)
            runBlocking { ctx.client.getFuturesGO("CNYRUBF") }

            val staleAge =
                ctx.meterRegistry
                    .find("futures.go_cache_age_ms")
                    .tags("fresh", "false")
                    .gauges()
                    .firstOrNull()
            assertNotNull(staleAge, "stale (API-down) path must publish cache age with fresh=false")
        } finally {
            ctx.server.stop(0)
        }
    }

    @Test
    fun `live GO refetched after TTL elapses`() {
        val ctx =
            liveServer(
                goBody = """{"long": {"initialMargin": "850"}}""",
                summariesBody = """{"moneyAmount": "50000"}""",
                maxGoAgeMs = 80,
            )
        try {
            val first = runBlocking { ctx.client.getFuturesGO("CNYRUBF") }
            Thread.sleep(150)
            val second = runBlocking { ctx.client.getFuturesGO("CNYRUBF") }

            assertEquals(0, BigDecimal("850").compareTo(first!!))
            assertEquals(0, BigDecimal("850").compareTo(second!!))
            assertEquals(2, ctx.goHits.get(), "stale cache must trigger refresh")
        } finally {
            ctx.server.stop(0)
        }
    }

    @Test
    fun `live GO with stale cache returns null when API fails fail-closed`() {
        val ctx =
            liveServer(
                goBody = """{"long": {"initialMargin": "850"}}""",
                summariesBody = """{"moneyAmount": "50000"}""",
                maxGoAgeMs = 80,
            )
        try {
            val first = runBlocking { ctx.client.getFuturesGO("CNYRUBF") }
            assertEquals(0, BigDecimal("850").compareTo(first!!))
            Thread.sleep(150)
            ctx.fail.set(true)
            val second = runBlocking { ctx.client.getFuturesGO("CNYRUBF") }

            assertNull(second, "stale cache must NOT be reused when API is down (fail-closed)")
            assertEquals(2, ctx.goHits.get())
        } finally {
            ctx.server.stop(0)
        }
    }

    @Test
    fun `live GO is side-specific long vs short`() {
        val ctx =
            liveServer(
                goBody = """{"long": {"initialMargin": "850"}, "short": {"initialMargin": "900"}}""",
                summariesBody = """{"moneyAmount": "50000"}""",
                maxGoAgeMs = 5000,
            )
        try {
            val longGo = runBlocking { ctx.client.getFuturesGO("CNYRUBF", PositionDirection.LONG) }
            val shortGo = runBlocking { ctx.client.getFuturesGO("CNYRUBF", PositionDirection.SHORT) }

            assertEquals(0, BigDecimal("850").compareTo(longGo!!))
            assertEquals(0, BigDecimal("900").compareTo(shortGo!!))
            assertEquals(1, ctx.goHits.get(), "both sides served from one cached pair")
        } finally {
            ctx.server.stop(0)
        }
    }

    @Test
    fun `live GO with missing requested side returns null fail-closed`() {
        // /risk ответил только long.initialMargin — SHORT-вход не может сайзиться
        // от ГО другой стороны (или от устаревшей пары): null → вход блокируется.
        val ctx =
            liveServer(
                goBody = """{"long": {"initialMargin": "850"}}""",
                summariesBody = """{"moneyAmount": "50000"}""",
                maxGoAgeMs = 5000,
            )
        try {
            val longGo = runBlocking { ctx.client.getFuturesGO("CNYRUBF", PositionDirection.LONG) }
            val shortGo = runBlocking { ctx.client.getFuturesGO("CNYRUBF", PositionDirection.SHORT) }

            assertEquals(0, BigDecimal("850").compareTo(longGo!!))
            assertNull(shortGo, "missing side margin must NOT fall back to the other side in LIVE")
        } finally {
            ctx.server.stop(0)
        }
    }

    @Test
    fun `live portfolio money with stale cache returns null when API fails fail-closed`() {
        val ctx =
            liveServer(
                goBody = """{"long": {"initialMargin": "850"}}""",
                summariesBody = """{"moneyAmount": "50000"}""",
                maxGoAgeMs = 80,
            )
        try {
            val first = runBlocking { ctx.client.getPortfolioMoney("P1") }
            assertEquals(0, BigDecimal("50000").compareTo(first!!))
            Thread.sleep(150)
            ctx.fail.set(true)
            val second = runBlocking { ctx.client.getPortfolioMoney("P1") }

            assertNull(second, "stale balance must NOT be reused when API is down (fail-closed)")
        } finally {
            ctx.server.stop(0)
        }
    }

    private data class Ctx(
        val server: HttpServer,
        val goHits: AtomicInteger,
        val fail: AtomicBoolean,
        val meterRegistry: SimpleMeterRegistry,
        val client: AlorFuturesClient,
    )

    private fun liveServer(
        goBody: String,
        summariesBody: String,
        maxGoAgeMs: Long,
    ): Ctx {
        val fail = AtomicBoolean(false)
        val goHits = AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/md/v2/Securities/MOEX/CNYRUBF/risk") { exchange ->
            goHits.incrementAndGet()
            if (fail.get()) {
                respond(exchange, "server down", 500)
            } else {
                respond(exchange, goBody, 200)
            }
        }
        server.createContext("/md/v2/Clients/P1/summaries") { exchange ->
            if (fail.get()) {
                respond(exchange, "server down", 500)
            } else {
                respond(exchange, summariesBody, 200)
            }
        }
        server.start()

        val tradingConfig = TradingConfig().apply { mode = "LIVE" }
        val alorConfig =
            AlorConfig().apply {
                apiUrl = "http://127.0.0.1:${server.address.port}"
                portfolio = "P1"
            }
        val riskConfig = RiskConfig().apply { this.maxGoAgeMs = maxGoAgeMs }
        val meterRegistry = SimpleMeterRegistry()
        val client =
            AlorFuturesClient(alorConfig, tradingConfig, ObjectMapper(), instrumentsConfig, meterRegistry, riskConfig)
        return Ctx(server, goHits, fail, meterRegistry, client)
    }

    private fun respond(
        exchange: HttpExchange,
        body: String,
        statusCode: Int,
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        if (statusCode in 200..299) {
            exchange.sendResponseHeaders(statusCode, bytes.size.toLong())
            exchange.responseBody.write(bytes)
        } else {
            exchange.sendResponseHeaders(statusCode, -1)
        }
        exchange.close()
    }
}
