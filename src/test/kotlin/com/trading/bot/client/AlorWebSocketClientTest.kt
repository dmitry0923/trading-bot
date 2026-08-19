package com.trading.bot.client

import com.trading.bot.config.AlorConfig
import com.trading.bot.model.dto.OrderStatus
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigDecimal

/**
 * Unit-тесты парсинга WS-сообщений Alor (roadmap 13.17, P0):
 * [AlorWebSocketClient.parseExecution] и [AlorWebSocketClient.parseQuote] —
 * чистое парсинг JSON: статусы исполнений, приоритет Last/mid, fallback-поля,
 * служебные сообщения (null), время в сек/мс.
 */
class AlorWebSocketClientTest {
    private val client =
        AlorWebSocketClient(
            AlorConfig(),
            jacksonObjectMapper(),
            SimpleMeterRegistry(),
            mock<WebSocketManager>(),
        )

    @Test
    fun `parseExecution FILLED from data envelope (Simple format)`() {
        val report =
            client.parseExecution(
                """{"opcode":"OrdersGetAndSubscribeV2","guid":"g-1","data":{"id":"ord-1","status":"filled","filledQtyBatch":5,"qtyBatch":5,"avgFillPrice":"92000","ticker":"Si","side":"buy","filled":true}}""",
            )

        assertEquals("ord-1", report?.orderId)
        assertEquals(OrderStatus.FILLED, report?.status)
        assertEquals(5, report?.cumulativeFilledQty)
        assertEquals(5, report?.requestedQty)
        assertEquals(0, BigDecimal("92000").compareTo(report?.avgPrice))
        assertEquals("Si", report?.ticker)
        assertEquals("buy", report?.side)
    }

    @Test
    fun `parseExecution PARTIALLY_FILLED from data envelope`() {
        val report =
            client.parseExecution(
                """{"opcode":"OrdersGetAndSubscribeV2","data":{"id":"ord-1","status":"partiallyFilled","filledQtyBatch":2,"qtyBatch":5,"avgFillPrice":"92000"}}""",
            )

        assertEquals(OrderStatus.PARTIALLY_FILLED, report?.status)
        assertEquals(2, report?.cumulativeFilledQty)
        assertEquals(5, report?.requestedQty)
    }

    @Test
    fun `parseExecution FILLED when legacy root format (no data envelope)`() {
        val report =
            client.parseExecution(
                """{"opcode":"OrdersGetAndSubscribeV2","orderNumber":"ord-1","status":"Filled","filledQty":5,"quantity":5,"avgFillPrice":"92000","ticker":"Si","side":"buy"}""",
            )

        assertEquals("ord-1", report?.orderId)
        assertEquals(OrderStatus.FILLED, report?.status)
        assertEquals(5, report?.cumulativeFilledQty)
        assertEquals(0, BigDecimal("92000").compareTo(report?.avgPrice))
        assertEquals("Si", report?.ticker)
        assertEquals("buy", report?.side)
    }

    @Test
    fun `parseExecution PARTIALLY_FILLED when legacy root format`() {
        val report =
            client.parseExecution(
                """{"opcode":"OrdersGetAndSubscribeV2","orderNumber":"ord-1","status":"PartiallyFilled","filledQty":2,"quantity":5,"avgFillPrice":"92000"}""",
            )

        assertEquals(OrderStatus.PARTIALLY_FILLED, report?.status)
        assertEquals(2, report?.cumulativeFilledQty)
    }

    @Test
    fun `parseExecution CANCELED REJECTED NEW UNKNOWN statuses`() {
        assertEquals(OrderStatus.CANCELED, client.parseExecution("""{"orderNumber":"o1","status":"Cancelled"}""")?.status)
        assertEquals(OrderStatus.REJECTED, client.parseExecution("""{"orderNumber":"o1","status":"Rejected"}""")?.status)
        assertEquals(OrderStatus.NEW, client.parseExecution("""{"orderNumber":"o1","status":"Working"}""")?.status)
        assertEquals(OrderStatus.UNKNOWN, client.parseExecution("""{"orderNumber":"o1"}""")?.status)
    }

    @Test
    fun `parseExecution uses fallback field names (legacy root)`() {
        val byId = client.parseExecution("""{"id":"o-id","status":"Filled","filledQuantity":1,"filledPrice":"100.5"}""")
        assertEquals("o-id", byId?.orderId)
        assertEquals(1, byId?.cumulativeFilledQty)
        assertEquals(0, BigDecimal("100.5").compareTo(byId?.avgPrice))

        val byOrderNo = client.parseExecution("""{"orderNo":"o-no","status":"Filled","qty":1,"price":"99.0","symbol":"SBER"}""")
        assertEquals("o-no", byOrderNo?.orderId)
        assertEquals("SBER", byOrderNo?.ticker)
        assertEquals(0, BigDecimal("99.0").compareTo(byOrderNo?.avgPrice))
    }

    @Test
    fun `parseExecution data envelope with brokerSymbol fallback`() {
        val report =
            client.parseExecution(
                """{"data":{"id":"ord-2","status":"filled","filledQtyBatch":1,"qtyBatch":1,"brokerSymbol":"MOEX:CNYRUB_TOM","avgFillPrice":"12.50"}}""",
            )

        assertEquals("ord-2", report?.orderId)
        assertEquals("CNYRUB_TOM", report?.ticker)
        assertEquals(1, report?.cumulativeFilledQty)
    }

    @Test
    fun `parseExecution data envelope CANCELED REJECTED statuses`() {
        assertEquals(
            OrderStatus.CANCELED,
            client.parseExecution("""{"data":{"id":"o1","status":"cancelled"}}""")?.status,
        )
        assertEquals(
            OrderStatus.REJECTED,
            client.parseExecution("""{"data":{"id":"o1","status":"rejected"}}""")?.status,
        )
        assertEquals(
            OrderStatus.NEW,
            client.parseExecution("""{"data":{"id":"o1","status":"working"}}""")?.status,
        )
        assertEquals(
            OrderStatus.UNKNOWN,
            client.parseExecution("""{"data":{"id":"o1"}}""")?.status,
        )
    }

    @Test
    fun `parseExecution returns null for service messages`() {
        assertNull(client.parseExecution("""{"opcode":"QuotesSubscribe","guid":"q-1"}"""))
        assertNull(client.parseExecution("""{"opcode":"SomethingElse"}"""))
    }

    @Test
    fun `parseExecution returns null when order id missing`() {
        assertNull(client.parseExecution("""{"status":"Filled","filledQty":1}"""))
        assertNull(client.parseExecution("""{"data":{"status":"filled"}}"""))
    }

    @Test
    fun `parseExecution returns null on invalid json`() {
        assertNull(client.parseExecution("""{invalid"""))
    }

    @Test
    fun `parseQuote prefers Last price`() {
        val tick =
            client.parseQuote(
                """{"guid":"q-SBER","quotes":[{"o":"Bid","price":280.0,"time":1700000000},{"o":"Last","price":280.5,"time":1700000000},{"o":"Offer","price":281.0,"time":1700000000}]}""",
            )

        assertEquals("SBER", tick?.ticker)
        assertEquals(0, BigDecimal("280.5").compareTo(tick?.price))
        assertEquals(1700000000L, tick?.exchangeTime?.epochSecond)
    }

    @Test
    fun `parseQuote uses mid when no Last`() {
        val tick =
            client.parseQuote(
                """{"guid":"q-SBER","quotes":[{"o":"Bid","price":100},{"o":"Offer","price":102}]}""",
            )

        assertEquals(0, BigDecimal("101").compareTo(tick?.price))
    }

    @Test
    fun `parseQuote falls back to bid or offer only`() {
        val bidOnly = client.parseQuote("""{"guid":"q-SBER","quotes":[{"o":"Bid","price":100}]}""")
        assertEquals(0, BigDecimal("100").compareTo(bidOnly?.price))

        val offerOnly = client.parseQuote("""{"guid":"q-SBER","quotes":[{"o":"Offer","price":102}]}""")
        assertEquals(0, BigDecimal("102").compareTo(offerOnly?.price))
    }

    @Test
    fun `parseQuote uses symbol fallback and millis time`() {
        val tick =
            client.parseQuote(
                """{"symbol":"GAZP","quotes":[{"o":"Last","price":250.5,"time":1700000000000}]}""",
            )

        assertEquals("GAZP", tick?.ticker)
        assertEquals(0, BigDecimal("250.5").compareTo(tick?.price))
        assertEquals(1700000000L, tick?.exchangeTime?.epochSecond)
    }

    @Test
    fun `parseQuote returns null without price or guid`() {
        assertNull(client.parseQuote("""{"guid":"q-X","quotes":[{"o":"Bid"}]}"""))
        assertNull(client.parseQuote("""{"symbol":"X","quotes":[]}"""))
        assertNull(client.parseQuote("""{"guid":"q-X"}"""))
        assertNull(client.parseQuote("""{"opcode":"OrdersGetAndSubscribeV2"}"""))
    }

    @Test
    fun `parseQuote returns null on invalid json`() {
        assertNull(client.parseQuote("""{"guid":"q-SBER" """))
    }
}
