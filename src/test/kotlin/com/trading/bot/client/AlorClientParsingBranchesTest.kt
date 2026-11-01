package com.trading.bot.client

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.trading.bot.config.AlorConfig
import com.trading.bot.config.TradingConfig
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.ratelimiter.RateLimiterRegistry
import io.github.resilience4j.retry.RetryRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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
 * [AlorClient] — разбор ответов, не покрытых основным тестом: пустые объёмы
 * стакана, mid/bid fallback, выбор стороны маркет-ордера, сверка ордера по
 * idempotency key (несовпадения/fallback-поля), verifyOrder (batch/fallback
 * количества, avgFillPrice), positions/trades из массива и из обёртки, отказы
 * эндпоинтов.
 */
class AlorClientParsingBranchesTest {
    private val orderTransport = mock<RoutedOrderTransport>()

    private fun buildClient(
        server: FakeServer,
        registry: SimpleMeterRegistry = SimpleMeterRegistry(),
        decoratorsEnabled: Boolean = false,
    ): AlorClient {
        val tradingConfig = TradingConfig().apply { mode = "LIVE" }
        val alorConfig =
            AlorConfig().apply {
                apiUrl = server.baseUrl
                token = "test-token"
                portfolio = "P1"
                retryEnabled = decoratorsEnabled
                rateLimiterEnabled = decoratorsEnabled
                circuitBreakerEnabled = decoratorsEnabled
            }
        return AlorClient(
            tradingConfig,
            alorConfig,
            jacksonObjectMapper(),
            registry,
            AlorTokenProvider(alorConfig, jacksonObjectMapper()),
            orderTransport,
            RetryRegistry.ofDefaults(),
            RateLimiterRegistry.ofDefaults(),
            CircuitBreakerRegistry.ofDefaults(),
        )
    }

    @Test
    fun `resilience decorators keep market data delivery working`() =
        runBlocking {
            FakeServer().use { server ->
                server.quotesBody = """{"bid":"99.9","ask":"100.1","last":"100.0"}"""

                val snapshot = buildClient(server, decoratorsEnabled = true).getMarketSnapshot("SBER")

                assertEquals(0, BigDecimal("100.0").compareTo(snapshot!!.currentPrice))
            }
        }

    @Test
    fun `zero quote sizes are unknown and missing last price falls back to bid or mid`() =
        runBlocking {
            FakeServer().use { server ->
                server.quotesBody =
                    """{"bid":"99.9","ask":"100.1","bidVolume":0,"askVolume":0}"""

                val snapshot = buildClient(server).getMarketSnapshot("SBER")!!

                assertNull(snapshot.bidSize)
                assertNull(snapshot.askSize)
                assertEquals(0, BigDecimal("100.0").compareTo(snapshot.currentPrice))
                assertNull(snapshot.obi)

                server.quotesBody = """{"bid":"99.9"}"""
                val bidOnly = buildClient(server).getMarketSnapshot("SBER")!!
                assertEquals(0, BigDecimal("99.9").compareTo(bidOnly.currentPrice))
                assertNull(bidOnly.ask)
            }
        }

    @Test
    fun `quote without any usable price is reported instead of returning a zero`() =
        runBlocking {
            FakeServer().use { server ->
                server.quotesBody = """{"lastPrice":"0","bid":"-1","ask":""}"""
                val registry = SimpleMeterRegistry()

                assertNull(buildClient(server, registry).getMarketSnapshot("SBER"))
                assertEquals(
                    1.0,
                    registry.counter("alor.quotes.error", "ticker", "SBER", "reason", "NO_PRICE").count(),
                )
            }
        }

    @Test
    fun `market order picks bid for sell and current price when the side is unknown`() =
        runBlocking {
            FakeServer().use { server ->
                server.quotesBody = """{"lastPrice":"100","bid":"99.9","ask":"100.1"}"""
                whenever(orderTransport.placeLimit(any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn("ord-1")
                val client = buildClient(server)

                client.placeMarketOrder("SBER", "sell", 1, "idem-sell")
                client.placeMarketOrder("SBER", "unknown-side", 1, "idem-other")

                org.mockito.kotlin
                    .verify(orderTransport)
                    .placeLimit("SBER", "sell", 1, BigDecimal("99.9"), "idem-sell", "P1", OrderPurpose.ENTRY)
                org.mockito.kotlin
                    .verify(orderTransport)
                    .placeLimit("SBER", "unknown-side", 1, BigDecimal("100"), "idem-other", "P1", OrderPurpose.ENTRY)
            }
        }

    @Test
    fun `market order without a snapshot does not reach the transport`() =
        runBlocking {
            FakeServer().use { server ->
                server.quotesBody = """{"lastPrice":"0"}"""

                assertNull(buildClient(server).placeMarketOrder("SBER", "buy", 1, "idem-none"))
                org.mockito.kotlin.verifyNoInteractions(orderTransport)
            }
        }

    @Test
    fun `reconciliation skips mismatched rows and uses fallback fields`() =
        runBlocking {
            FakeServer().use { server ->
                server.ordersBody =
                    """
                    [{"id":"other","ticker":"SBER","side":"buy"},
                     {"id":"idem-1","ticker":"GAZP","side":"buy"},
                     {"id":"idem-1","ticker":"SBER","side":"sell"},
                     {"id":"idem-1","ticker":"SBER","side":"buy","orderNumber":"ord-7",
                      "filledQty":0,"filledQuantity":3,"avgFillPrice":"101.5"}]
                    """.trimIndent()

                val result =
                    buildClient(server).reconcileOrderByIdempotencyKey("idem-1", "SBER", "buy")

                assertEquals(AlorClient.OrderReconciliation.Found("ord-7", 3, BigDecimal("101.5")), result)
            }
        }

    @Test
    fun `reconciliation reports not found and unknown on failure`() =
        runBlocking {
            FakeServer().use { server ->
                server.ordersBody = """[{"id":"other"}]"""
                val client = buildClient(server)
                assertEquals(
                    AlorClient.OrderReconciliation.NotFound,
                    client.reconcileOrderByIdempotencyKey("idem-2", "SBER", "buy"),
                )

                server.status = 500
                assertEquals(
                    AlorClient.OrderReconciliation.Unknown,
                    client.reconcileOrderByIdempotencyKey("idem-2", "SBER", "buy"),
                )
            }
        }

    @Test
    fun `verifyOrder prefers batch fill and avg fill price`() =
        runBlocking {
            FakeServer().use { server ->
                val client = buildClient(server)

                server.orderByIdBody =
                    """{"status":"FILLED","filledQtyBatch":2,"filledQty":1,"avgFillPrice":"100.5","filledPrice":"99"}"""
                val execution = client.verifyOrder("ord-1")!!
                assertEquals(2, execution.filledQuantity)
                assertEquals(0, BigDecimal("100.5").compareTo(execution.avgPrice!!))

                server.orderByIdBody = """{"status":"FILLED","filledQty":1,"filledPrice":"99"}"""
                val fallback = client.verifyOrder("ord-1")!!
                assertEquals(1, fallback.filledQuantity)
                assertEquals(0, BigDecimal("99").compareTo(fallback.avgPrice!!))

                server.orderByIdBody = """{"status":"NEW"}"""
                assertEquals(0, client.verifyOrder("ord-1")!!.filledQuantity)
            }
        }

    @Test
    fun `verifyOrder returns null when the exchange is unreachable`() =
        runBlocking {
            FakeServer().use { server ->
                server.status = 503

                assertNull(buildClient(server).verifyOrder("ord-1"))
            }
        }

    @Test
    fun `positions are accepted from an array and from a wrapper and blank tickers skipped`() =
        runBlocking {
            FakeServer().use { server ->
                server.positionsBody =
                    """[{"ticker":"SBER","qty":5,"averagePrice":"100","time":"2026-09-01T10:00:00Z"}]"""
                val array = buildClient(server).getPositions()
                assertEquals(1, (array as AlorClient.ReconcileResult.Ok).items.size)

                server.positionsBody =
                    """
                    {"positions":[{"symbol":"GAZP","quantity":-2,"avgFillPrice":"200"},
                                  {"ticker":"","symbol":""}]}
                    """.trimIndent()
                val wrapped = buildClient(server).getPositions()
                val items = (wrapped as AlorClient.ReconcileResult.Ok).items
                assertEquals(1, items.size)
                assertEquals("GAZP", items.single().ticker)
                assertEquals(-2L, items.single().qty)
                assertEquals(0, BigDecimal("200").compareTo(items.single().avgPrice!!))
            }
        }

    @Test
    fun `positions failure is reported as failed with a metric`() =
        runBlocking {
            FakeServer().use { server ->
                server.status = 500
                val registry = SimpleMeterRegistry()

                assertEquals(AlorClient.ReconcileResult.Failed, buildClient(server, registry).getPositions())
                assertEquals(
                    1.0,
                    registry.counter("alor.reconcile.fetch_error", "kind", "positions").count(),
                )
            }
        }

    @Test
    fun `trades are accepted from an array and from a wrapper and incomplete rows skipped`() =
        runBlocking {
            FakeServer().use { server ->
                server.tradesBody =
                    """
                    [{"id":"t1","ticker":"SBER","price":"100","quantity":2,"side":"buy","orderNumber":"o-1"}]
                    """.trimIndent()
                val array = buildClient(server).getRecentTrades()
                val first = (array as AlorClient.ReconcileResult.Ok).items.single()
                assertEquals("t1", first.id)
                assertEquals("o-1", first.orderId)

                server.tradesBody =
                    """
                    {"trades":[{"tradeId":"t2","symbol":"GAZP","price":"200","qty":1},
                               {"id":"","tradeId":"","price":"1","ticker":"SBER"},
                               {"id":"t3","ticker":"SBER"},
                               {"id":"t4","ticker":"","symbol":"","price":"1"}]}
                    """.trimIndent()
                val wrapped = buildClient(server).getRecentTrades()
                val items = (wrapped as AlorClient.ReconcileResult.Ok).items
                assertEquals(1, items.size)
                assertEquals("t2", items.single().id)
                assertEquals(1, items.single().quantity)
                assertNull(items.single().side)
            }
        }

    @Test
    fun `trades failure is reported as failed with a metric`() =
        runBlocking {
            FakeServer().use { server ->
                server.status = 500
                val registry = SimpleMeterRegistry()

                assertEquals(AlorClient.ReconcileResult.Failed, buildClient(server, registry).getRecentTrades())
                assertTrue(registry.counter("alor.reconcile.fetch_error", "kind", "trades").count() >= 1.0)
            }
        }

    @Test
    fun `getLastPrice returns the quote or null without a usable price`() =
        runBlocking {
            FakeServer().use { server ->
                server.quotesBody = """{"lastPrice":"101.5"}"""
                assertEquals(0, BigDecimal("101.5").compareTo(buildClient(server).getLastPrice("SBER")!!))

                server.quotesBody = """{"lastPrice":"0"}"""
                assertNull(buildClient(server).getLastPrice("SBER"))
            }
        }

    @Test
    fun `conditional orders and cancel delegate to the transport with defaults`() =
        runBlocking {
            FakeServer().use { server ->
                whenever(
                    orderTransport.placeConditional(any(), any(), any(), any(), any(), any(), any(), any()),
                ).thenReturn("cond-1")
                whenever(orderTransport.cancel(any(), any(), any())).thenReturn(AlorClient.CancelResult.CONFIRMED)
                val client = buildClient(server)

                assertEquals(
                    "cond-1",
                    client.placeStopOrder("SBER", "sell", 1, BigDecimal("99"), "idem-sl"),
                )
                assertEquals(
                    "cond-1",
                    client.placeTakeProfitOrder("SBER", "sell", 1, BigDecimal("101"), "idem-tp"),
                )
                assertEquals(AlorClient.CancelResult.CONFIRMED, client.cancelOrder("ord-1", "idem-cancel"))

                org.mockito.kotlin
                    .verify(orderTransport)
                    .placeConditional(
                        "stop",
                        "SBER",
                        "sell",
                        1,
                        BigDecimal("99"),
                        "idem-sl",
                        "P1",
                        OrderPurpose.SL,
                    )
                org.mockito.kotlin
                    .verify(orderTransport)
                    .placeConditional(
                        "take-profit",
                        "SBER",
                        "sell",
                        1,
                        BigDecimal("101"),
                        "idem-tp",
                        "P1",
                        OrderPurpose.TP,
                    )
                org.mockito.kotlin
                    .verify(orderTransport)
                    .cancel("ord-1", "idem-cancel", "P1")
            }
        }

    @Test
    fun `positive quote sizes are preserved`() =
        runBlocking {
            FakeServer().use { server ->
                server.quotesBody = """{"lastPrice":"100","bid":"99.9","ask":"100.1","bidVolume":10,"askVolume":20}"""

                val snapshot = buildClient(server).getMarketSnapshot("SBER")!!

                assertEquals(10L, snapshot.bidSize)
                assertEquals(20L, snapshot.askSize)
            }
        }

    @Test
    fun `market buy uses the ask and falls back to the last price without a book`() =
        runBlocking {
            FakeServer().use { server ->
                server.quotesBody = """{"lastPrice":"100","ask":"100.2"}"""
                whenever(orderTransport.placeLimit(any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn("ord-buy")
                val client = buildClient(server)

                client.placeMarketOrder("SBER", "buy", 1, "idem-buy")

                org.mockito.kotlin
                    .verify(orderTransport)
                    .placeLimit("SBER", "buy", 1, BigDecimal("100.2"), "idem-buy", "P1", OrderPurpose.ENTRY)

                server.quotesBody = """{"lastPrice":"100"}"""
                client.placeMarketOrder("SBER", "sell", 1, "idem-nobook")
                org.mockito.kotlin
                    .verify(orderTransport)
                    .placeLimit("SBER", "sell", 1, BigDecimal("100"), "idem-nobook", "P1", OrderPurpose.ENTRY)
            }
        }

    @Test
    fun `verified order with an expected price records slippage`() =
        runBlocking {
            FakeServer().use { server ->
                server.orderByIdBody = """{"status":"FILLED","filledQty":2,"avgFillPrice":"101"}"""
                val registry = SimpleMeterRegistry()
                val client = buildClient(server, registry)

                val execution = client.verifyOrder("ord-1", expectedPrice = BigDecimal("100"))!!

                assertEquals(2, execution.filledQuantity)
                assertTrue(
                    registry
                        .find("trade.slippage.rub")
                        .meters()
                        .isNotEmpty(),
                    "слиппедж должен публиковаться",
                )
            }
        }

    @Test
    fun `market order without an id does not meter a placement`() =
        runBlocking {
            FakeServer().use { server ->
                whenever(orderTransport.placeLimit(any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(null)
                val registry = SimpleMeterRegistry()

                assertNull(buildClient(server, registry).placeMarketOrder("SBER", "buy", 1, "idem-null"))
                assertNull(registry.find("alor.order.placed").counter())
            }
        }

    @Test
    fun `verifyOrder with an expected price but no fill skips slippage`() =
        runBlocking {
            FakeServer().use { server ->
                server.orderByIdBody = """{"status":"NEW"}"""
                val registry = SimpleMeterRegistry()

                val execution = buildClient(server, registry).verifyOrder("ord-1", expectedPrice = BigDecimal("100"))!!

                assertNull(execution.avgPrice)
                assertTrue(registry.find("trade.slippage.rub").meters().isEmpty())
            }
        }

    @Test
    fun `reconciliation accepts a blank ticker side and falls back to the id`() =
        runBlocking {
            FakeServer().use { server ->
                server.ordersBody = """[{"id":"idem-blank","ticker":"","side":"","filledQty":0}]"""

                val found = buildClient(server).reconcileOrderByIdempotencyKey("idem-blank", "SBER", "buy")

                assertTrue(found is AlorClient.OrderReconciliation.Found)
                assertEquals("idem-blank", (found as AlorClient.OrderReconciliation.Found).orderNumber)
                assertEquals(0, found.filledQty)
            }
        }

    @Test
    fun `forced market buy with a wide spread uses the ask`(): Unit =
        runBlocking {
            FakeServer().use { server ->
                server.quotesBody = """{"lastPrice":"100","bid":"90","ask":"110"}"""
                whenever(orderTransport.placeLimit(any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn("ord-forced")

                buildClient(server).placeMarketOrder("SBER", "buy", 1, "idem-forced", forceMarket = true)

                org.mockito.kotlin
                    .verify(orderTransport)
                    .placeLimit("SBER", "buy", 1, BigDecimal("110"), "idem-forced", "P1", OrderPurpose.ENTRY)
            }
        }

    @Test
    fun `market buy without an ask uses the last price`(): Unit =
        runBlocking {
            FakeServer().use { server ->
                server.quotesBody = """{"lastPrice":"100","bid":"99"}"""
                whenever(orderTransport.placeLimit(any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn("ord-buy-nullask")

                buildClient(server).placeMarketOrder("SBER", "buy", 1, "idem-buy-nullask", forceMarket = true)

                org.mockito.kotlin
                    .verify(orderTransport)
                    .placeLimit("SBER", "buy", 1, BigDecimal("100"), "idem-buy-nullask", "P1", OrderPurpose.ENTRY)
            }
        }

    private class FakeServer : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())

        var status: Int = 200
        var quotesBody: String = """{"lastPrice":"100","bid":"99.9","ask":"100.1"}"""
        var ordersBody: String = "[]"
        var positionsBody: String = "[]"
        var tradesBody: String = "[]"
        var orderByIdBody: String = """{"status":"FILLED","filledQty":1,"filledPrice":"99.5"}"""

        val baseUrl: String
            get() = "http://127.0.0.1:${server.address.port}"

        init {
            server.executor = Executors.newCachedThreadPool()
            server.createContext("/") { handle(it) }
            server.start()
        }

        private fun handle(exchange: HttpExchange) {
            val path = exchange.requestURI.path
            requests.add(path)
            val body =
                when {
                    status != 200 -> "error"
                    path.endsWith("/quotes") -> quotesBody
                    path.contains("/orders/") -> orderByIdBody
                    path.contains("/positions") -> positionsBody
                    path.contains("/trades") -> tradesBody
                    path.contains("/orders") -> ordersBody
                    else -> "{}"
                }
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        override fun close() {
            server.stop(0)
        }
    }
}
