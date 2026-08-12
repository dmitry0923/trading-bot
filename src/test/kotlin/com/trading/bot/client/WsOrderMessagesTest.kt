package com.trading.bot.client

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigDecimal

/**
 * Unit-тесты чистых функций WS-протокола ордеров ([WsOrderMessages]):
 * сборка команд (opcode + payload) и сопоставление входящих сообщений
 * с ожидаемым исходом (Confirmed/Rejected/NotMatch).
 */
class WsOrderMessagesTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `subscribe builds OrdersGetAndSubscribeV2 command`() {
        val json = WsOrderMessages.subscribe("tok", "P1", "MOEX", "guid-1")
        val j = mapper.readTree(json)
        assertEquals("OrdersGetAndSubscribeV2", j.path("opcode").asString())
        assertEquals("guid-1", j.path("guid").asString())
        assertEquals("tok", j.path("token").asString())
        assertEquals("P1", j.path("portfolio").asString())
        assertEquals("MOEX", j.path("exchange").asString())
        assertEquals("Simple", j.path("format").asString())
    }

    @Test
    fun `placeLimit builds AlorOrders command with id as idempotency key`() {
        val json = WsOrderMessages.placeLimit("tok", "P1", "SBER", "MOEX", "buy", 10, BigDecimal("250.5"), "idem-1", "guid-2")
        val j = mapper.readTree(json)
        assertEquals("AlorOrders", j.path("opcode").asString())
        assertEquals("guid-2", j.path("guid").asString())
        assertEquals("tok", j.path("token").asString())
        assertEquals("P1", j.path("portfolio").asString())
        assertEquals("SBER", j.path("ticker").asString())
        assertEquals("MOEX", j.path("exchange").asString())
        assertEquals("buy", j.path("side").asString())
        assertEquals("limit", j.path("type").asString())
        assertEquals(10, j.path("quantity").asInt())
        assertEquals("250.5", j.path("price").asString())
        assertEquals("idem-1", j.path("id").asString())
    }

    @Test
    fun `placeConditional builds stop command with stopEndUnixTime zero`() {
        val json =
            WsOrderMessages.placeConditional("tok", "P1", "Si", "MOEX", "sell", "stop", 1, BigDecimal("120000"), "idem-2", "guid-3")
        val j = mapper.readTree(json)
        assertEquals("AlorOrders", j.path("opcode").asString())
        assertEquals("stop", j.path("type").asString())
        assertEquals("120000", j.path("stopPrice").asString())
        assertEquals(0, j.path("stopEndUnixTime").asInt())
        assertEquals("idem-2", j.path("id").asString())
    }

    @Test
    fun `cancel builds AlorCancelOrder command`() {
        val json = WsOrderMessages.cancel("tok", "P1", "MOEX", "ord-1", "idem-3", "guid-4")
        val j = mapper.readTree(json)
        assertEquals("AlorCancelOrder", j.path("opcode").asString())
        assertEquals("ord-1", j.path("orderId").asString())
        assertEquals("idem-3", j.path("id").asString())
        assertEquals("guid-4", j.path("guid").asString())
    }

    @Test
    fun `matchPlace confirms by idempotency key`() {
        val result = WsOrderMessages.matchPlace("""{"id":"idem-1","orderNumber":"12345","status":"working"}""", "idem-1", "guid")
        val confirmed = assertInstanceOf(WsOrderMessages.MatchResult.Confirmed::class.java, result)
        assertEquals("12345", confirmed.orderNumber)
    }

    @Test
    fun `matchPlace confirms via requestId ack`() {
        val result = WsOrderMessages.matchPlace("""{"requestId":"guid-x","orderNumber":"999"}""", "idem-1", "guid-x")
        assertInstanceOf(WsOrderMessages.MatchResult.Confirmed::class.java, result)
    }

    @Test
    fun `matchPlace rejects on rejected status`() {
        val result = WsOrderMessages.matchPlace("""{"id":"idem-1","status":"rejected","error":"not enough money"}""", "idem-1", "guid")
        val rejected = assertInstanceOf(WsOrderMessages.MatchResult.Rejected::class.java, result)
        assertEquals("not enough money", rejected.reason)
    }

    @Test
    fun `matchPlace returns NotMatch for unrelated message`() {
        val result = WsOrderMessages.matchPlace("""{"id":"other","orderNumber":"777"}""", "idem-1", "guid")
        assertSame(WsOrderMessages.MatchResult.NotMatch, result)
    }

    @Test
    fun `matchPlace waits when orderNumber not yet assigned`() {
        val result = WsOrderMessages.matchPlace("""{"id":"idem-1","status":"working"}""", "idem-1", "guid")
        assertSame(WsOrderMessages.MatchResult.NotMatch, result)
    }

    @Test
    fun `matchCancel confirms on cancelled status`() {
        val result = WsOrderMessages.matchCancel("""{"orderNumber":"ord-1","status":"cancelled"}""", "ord-1", "guid")
        assertInstanceOf(WsOrderMessages.MatchResult.Confirmed::class.java, result)
    }

    @Test
    fun `matchCancel confirms via requestId success ack`() {
        val result = WsOrderMessages.matchCancel("""{"requestId":"guid-c","requestStatus":"success"}""", "ord-1", "guid-c")
        assertInstanceOf(WsOrderMessages.MatchResult.Confirmed::class.java, result)
    }

    @Test
    fun `matchCancel rejects on error`() {
        val result =
            WsOrderMessages.matchCancel(
                """{"requestId":"guid-c","requestStatus":"error","error":"not found"}""",
                "ord-1",
                "guid-c",
            )
        val rejected = assertInstanceOf(WsOrderMessages.MatchResult.Rejected::class.java, result)
        assertEquals("not found", rejected.reason)
    }

    @Test
    fun `matchCancel returns NotMatch for unrelated order`() {
        val result = WsOrderMessages.matchCancel("""{"orderNumber":"ord-2","status":"working"}""", "ord-1", "guid")
        assertSame(WsOrderMessages.MatchResult.NotMatch, result)
    }

    @Test
    fun `matchCancel waits when order matched but status not final`() {
        val result = WsOrderMessages.matchCancel("""{"orderNumber":"ord-1","status":"working"}""", "ord-1", "guid")
        assertSame(WsOrderMessages.MatchResult.NotMatch, result)
    }

    @Test
    fun `matchPlace handles malformed json as NotMatch`() {
        assertSame(WsOrderMessages.MatchResult.NotMatch, WsOrderMessages.matchPlace("not json", "idem-1", "guid"))
        assertSame(WsOrderMessages.MatchResult.NotMatch, WsOrderMessages.matchPlace("", "idem-1", "guid"))
    }
}
