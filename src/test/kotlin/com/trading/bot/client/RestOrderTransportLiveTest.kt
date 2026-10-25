package com.trading.bot.client

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.trading.bot.config.AlorConfig
import com.trading.bot.config.TradingConfig
import com.trading.bot.service.LiveFrozenStrategyResolver
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.ratelimiter.RateLimiterRegistry
import io.github.resilience4j.retry.RetryRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.Executors

/**
 * LIVE-ветки [RestOrderTransport] поверх локального HTTP-сервера (реальная сеть
 * Alor не используется): успешная доставка limit/conditional/cancel, парсинг
 * `orderNumber`, определённый 4xx-отказ → [OrderRejectedException], 5xx/сетевой
 * сбой → [OrderDeliveryUncertainException], execution interlock (LIVE-allowlist
 * и resolver) до HTTP-вызова.
 */
class RestOrderTransportLiveTest {
    private val mapper = jacksonObjectMapper()
    private var registry = SimpleMeterRegistry()

    @Test
    fun `placeLimit sends full body and returns orderNumber`() =
        runBlocking {
            FakeAlorServer().use { server ->
                val transport = transport(server)

                val orderNumber = transport.placeLimit("CNYRUBF", "buy", 2, BigDecimal("12.34"), "idem-1", "P1", OrderPurpose.ENTRY)

                assertEquals("ord-limit-1", orderNumber)
                val request = server.recorded.single()
                assertEquals("POST", request.method)
                assertTrue(request.path.endsWith("/orders/actions/limit"))
                val body = mapper.readTree(request.body)
                assertEquals("P1", body.path("portfolio").asString())
                assertEquals("CNYRUBF", body.path("ticker").asString())
                assertEquals("MOEX", body.path("exchange").asString())
                assertEquals("buy", body.path("side").asString())
                assertEquals("limit", body.path("type").asString())
                assertEquals(2, body.path("quantity").asInt())
                assertEquals("12.34", body.path("price").asString())
                assertEquals("idem-1", body.path("id").asString())
                assertTrue(request.authorization?.startsWith("Bearer ") == true)
                assertEquals(1.0, registry.counter("alor.order.placed", "type", "limit", "status", "OK").count())
            }
        }

    @Test
    fun `placeLimit returns null when response has no orderNumber`() =
        runBlocking {
            FakeAlorServer().use { server ->
                server.responseBody = """{"status":"accepted"}"""
                val transport = transport(server)

                assertNull(transport.placeLimit("CNYRUBF", "buy", 1, BigDecimal("12.3"), "idem-2", "P1", OrderPurpose.ENTRY))
                assertEquals(0.0, registry.counter("alor.order.placed", "type", "limit", "status", "OK").count())
            }
        }

    @Test
    fun `placeLimit maps 4xx to definitive rejection`() =
        runBlocking {
            FakeAlorServer().use { server ->
                server.statusCode = 400
                val transport = transport(server)

                val error =
                    assertFailsWith<OrderRejectedException> {
                        transport.placeLimit("CNYRUBF", "buy", 1, BigDecimal("12.3"), "idem-3", "P1", OrderPurpose.ENTRY)
                    }

                assertTrue(error.message!!.contains("400"))
                assertEquals(1.0, registry.counter("alor.order.error", "side", "buy", "type", "limit").count())
            }
        }

    @Test
    fun `placeLimit maps 422 to definitive rejection`() =
        runBlocking {
            FakeAlorServer().use { server ->
                server.statusCode = 422
                val transport = transport(server)

                val error =
                    assertFailsWith<OrderRejectedException> {
                        transport.placeLimit("CNYRUBF", "buy", 1, BigDecimal("12.3"), "idem-4", "P1", OrderPurpose.ENTRY)
                    }

                assertTrue(error.message!!.contains("422"))
            }
        }

    @Test
    fun `placeLimit maps 5xx to uncertain delivery`() =
        runBlocking {
            FakeAlorServer().use { server ->
                server.statusCode = 500
                val transport = transport(server)

                val error =
                    assertFailsWith<OrderDeliveryUncertainException> {
                        transport.placeLimit("CNYRUBF", "buy", 1, BigDecimal("12.3"), "idem-5", "P1", OrderPurpose.ENTRY)
                    }

                assertTrue(error.message!!.contains("uncertain"))
            }
        }

    @Test
    fun `placeConditional sends stop payload and returns orderNumber`() =
        runBlocking {
            FakeAlorServer().use { server ->
                val transport = transport(server)

                val orderNumber =
                    transport.placeConditional(
                        "stop",
                        "CNYRUBF",
                        "sell",
                        1,
                        BigDecimal("11.9"),
                        "idem-6",
                        "P1",
                        OrderPurpose.SL,
                    )

                assertEquals("ord-stop-1", orderNumber)
                val body = mapper.readTree(server.requests.single().third)
                assertEquals("stop", body.path("type").asString())
                assertEquals("11.9", body.path("stopPrice").asString())
                assertEquals(0, body.path("stopEndUnixTime").asInt())
                assertEquals("sell", body.path("side").asString())
                assertEquals("idem-6", body.path("id").asString())
                assertEquals(1.0, registry.counter("alor.order.placed", "type", "stop", "status", "OK").count())
            }
        }

    @Test
    fun `placeConditional maps 5xx to uncertain delivery`() =
        runBlocking {
            FakeAlorServer().use { server ->
                server.statusCode = 503
                val transport = transport(server)

                val error =
                    assertFailsWith<OrderDeliveryUncertainException> {
                        transport.placeConditional("take-profit", "CNYRUBF", "sell", 1, BigDecimal("13.0"), "idem-7", "P1", OrderPurpose.TP)
                    }

                assertTrue(error.message!!.contains("uncertain"))
            }
        }

    @Test
    fun `cancel returns CONFIRMED and sends orderId`() =
        runBlocking {
            FakeAlorServer().use { server ->
                val transport = transport(server)

                val result = transport.cancel("ord-9", "idem-8", "P1")

                assertEquals(CancelResult.CONFIRMED, result)
                val body = mapper.readTree(server.requests.single().third)
                assertEquals("ord-9", body.path("orderId").asString())
                assertEquals("idem-8", body.path("id").asString())
                assertEquals("P1", body.path("portfolio").asString())
                assertEquals("MOEX", body.path("exchange").asString())
                assertEquals(1.0, registry.counter("alor.order.cancelled", "type", "limit").count())
            }
        }

    @Test
    fun `cancel maps 4xx to REJECTED and 5xx to uncertain`() =
        runBlocking {
            FakeAlorServer().use { server ->
                val transport = transport(server)

                server.statusCode = 400
                assertEquals(CancelResult.REJECTED, transport.cancel("ord-9", "idem-9", "P1"))

                server.statusCode = 500
                val error =
                    assertFailsWith<OrderDeliveryUncertainException> {
                        transport.cancel("ord-9", "idem-10", "P1")
                    }
                assertTrue(error.message!!.contains("uncertain"))
            }
        }

    @Test
    fun `live entry is blocked when ticker is not in allowlist before any HTTP call`() =
        runBlocking {
            FakeAlorServer().use { server ->
                val transport = transport(server, tickerAllowlist = listOf("OTHER"))

                val error =
                    assertFailsWith<OrderInterlockDeniedException> {
                        transport.placeLimit("CNYRUBF", "buy", 1, BigDecimal("12.3"), "idem-11", "P1", OrderPurpose.ENTRY)
                    }

                assertTrue(error.message!!.contains("liveTickersAllowlist"))
                assertTrue(server.requests.isEmpty())
                assertEquals(1.0, registry.counter("alor.order.blocked", "reason", "LIVE_TICKER_NOT_ALLOWED", "ticker", "CNYRUBF").count())
            }
        }

    @Test
    fun `live reducing order is blocked when resolver denies`() =
        runBlocking {
            FakeAlorServer().use { server ->
                val transport = transport(server, approved = false)

                val error =
                    assertFailsWith<OrderInterlockDeniedException> {
                        transport.placeLimit("CNYRUBF", "sell", 1, BigDecimal("12.3"), "idem-12", "P1", OrderPurpose.CLOSE)
                    }

                assertTrue(error.message!!.contains("interlock"))
                assertTrue(server.requests.isEmpty())
                assertEquals(1.0, registry.counter("alor.order.blocked", "reason", "NOT_LIVE_APPROVED", "ticker", "CNYRUBF").count())
            }
        }

    @Test
    fun `network failure is reported as uncertain delivery`() =
        runBlocking {
            val server = FakeAlorServer()
            val transport = transport(server)
            server.close()

            val error =
                assertFailsWith<OrderDeliveryUncertainException> {
                    transport.placeLimit("CNYRUBF", "buy", 1, BigDecimal("12.3"), "idem-13", "P1", OrderPurpose.ENTRY)
                }
            assertTrue(error.message!!.contains("uncertain"))
        }

    private fun transport(
        server: FakeAlorServer,
        approved: Boolean = true,
        tickerAllowlist: List<String> = listOf("CNYRUBF"),
    ): RestOrderTransport {
        registry = SimpleMeterRegistry()
        val tradingConfig =
            TradingConfig().apply {
                mode = "LIVE"
                liveTickersAllowlist = tickerAllowlist
            }
        val alorConfig =
            AlorConfig().apply {
                apiUrl = server.baseUrl
                token = "test-token"
                portfolio = "P1"
                exchange = "MOEX"
                retryEnabled = false
                rateLimiterEnabled = false
                circuitBreakerEnabled = false
            }
        return RestOrderTransport(
            tradingConfig,
            alorConfig,
            mapper,
            registry,
            AlorTokenProvider(alorConfig, mapper),
            RetryRegistry.ofDefaults(),
            RateLimiterRegistry.ofDefaults(),
            CircuitBreakerRegistry.ofDefaults(),
            resolver(approved),
        )
    }

    private fun resolver(approved: Boolean): LiveFrozenStrategyResolver {
        val resolver = mock<LiveFrozenStrategyResolver>()
        runBlocking { whenever(resolver.isOrderAllowed(any(), any())).thenReturn(approved) }
        return resolver
    }

    private suspend inline fun <reified T : Throwable> assertFailsWith(block: suspend () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw e
        }
        fail<T>("Expected ${T::class.simpleName} but no exception was thrown")
        error("unreachable")
    }

    private data class RecordedRequest(
        val method: String,
        val path: String,
        val body: String,
        val authorization: String?,
    )

    private class FakeAlorServer : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        /** (method, path, body, authorization). */
        val requests: MutableList<Triple<String, String, String>> = Collections.synchronizedList(mutableListOf())
        val headers: MutableList<String?> = Collections.synchronizedList(mutableListOf())

        var statusCode: Int = 200
        var responseBody: String = """{"orderNumber":"ord-limit-1"}"""

        val recorded: List<RecordedRequest>
            get() =
                requests.mapIndexed {
                    index,
                    request,
                    ->
                    RecordedRequest(request.first, request.second, request.third, headers.getOrNull(index))
                }

        val baseUrl: String
            get() = "http://127.0.0.1:${server.address.port}"

        init {
            server.executor = Executors.newCachedThreadPool()
            server.createContext("/") { handle(it) }
            server.start()
        }

        private fun handle(exchange: HttpExchange) {
            val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            requests.add(Triple(exchange.requestMethod, exchange.requestURI.path, body))
            headers.add(exchange.requestHeaders.getFirst("Authorization"))
            val payload =
                when {
                    statusCode != 200 -> "rejected"

                    exchange.requestURI.path.endsWith("/actions/cancel") -> """{"status":"ok"}"""

                    exchange.requestURI.path.contains(
                        "/orders/actions/limit",
                    ) && body.contains("\"stop\"") -> """{"orderNumber":"ord-stop-1"}"""

                    else -> responseBody
                }.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(statusCode, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }

        override fun close() {
            server.stop(0)
        }
    }
}
