package com.trading.bot.client

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Дополнительные ветки сопоставления WS-сообщений ([WsOrderMessages]): отказы через
 * `requestStatus`/`status` без поля `error`, fallback `orderNo`, битый JSON у отмены.
 * Базовые сценарии — в [WsOrderMessagesTest].
 */
class WsOrderMessagesBranchesTest {
    @Test
    fun `matchPlace rejects on requestStatus error without error text`() {
        val result = WsOrderMessages.matchPlace("""{"requestId":"guid-1","requestStatus":"error"}""", "idem-1", "guid-1")

        val rejected = assertInstanceOf(WsOrderMessages.MatchResult.Rejected::class.java, result)
        assertTrue(rejected.reason.contains("rejected"), rejected.reason)
    }

    @Test
    fun `matchPlace rejects on rejected status without error text`() {
        val result = WsOrderMessages.matchPlace("""{"id":"idem-1","status":"Rejected"}""", "idem-1", "guid")

        assertInstanceOf(WsOrderMessages.MatchResult.Rejected::class.java, result)
    }

    @Test
    fun `matchPlace confirms through orderNo fallback field`() {
        val result = WsOrderMessages.matchPlace("""{"id":"idem-1","orderNo":"ord-no"}""", "idem-1", "guid")

        val confirmed = assertInstanceOf(WsOrderMessages.MatchResult.Confirmed::class.java, result)
        assertEquals("ord-no", confirmed.orderNumber)
    }

    @Test
    fun `matchCancel confirms through orderNo fallback field`() {
        val result = WsOrderMessages.matchCancel("""{"orderNo":"ord-1","status":"canceled"}""", "ord-1", "guid")

        assertInstanceOf(WsOrderMessages.MatchResult.Confirmed::class.java, result)
    }

    @Test
    fun `matchCancel rejects on rejected status and on requestStatus rejected`() {
        val byStatus = WsOrderMessages.matchCancel("""{"orderNumber":"ord-1","status":"rejected"}""", "ord-1", "guid")
        assertInstanceOf(WsOrderMessages.MatchResult.Rejected::class.java, byStatus)

        val byRequest = WsOrderMessages.matchCancel("""{"requestId":"guid-1","requestStatus":"rejected"}""", "ord-1", "guid-1")
        assertInstanceOf(WsOrderMessages.MatchResult.Rejected::class.java, byRequest)
    }

    @Test
    fun `matchCancel treats malformed json as not matching`() {
        assertSame(WsOrderMessages.MatchResult.NotMatch, WsOrderMessages.matchCancel("{broken", "ord-1", "guid"))
        assertSame(WsOrderMessages.MatchResult.NotMatch, WsOrderMessages.matchCancel("", "ord-1", "guid"))
    }

    @Test
    fun `matchPlace unwraps a data envelope without root identifiers`() {
        val result =
            WsOrderMessages.matchPlace("""{"data":{"id":"idem-1","orderNumber":"ord-data"}}""", "idem-1", "guid")

        val confirmed = assertInstanceOf(WsOrderMessages.MatchResult.Confirmed::class.java, result)
        assertEquals("ord-data", confirmed.orderNumber)
    }

    @Test
    fun `matchPlace keeps root identifiers instead of unwrapping data`() {
        val result =
            WsOrderMessages.matchPlace(
                """{"id":"idem-1","orderNumber":"root-ord","data":{"id":"other","orderNumber":"data-ord"}}""",
                "idem-1",
                "guid",
            )

        val confirmed = assertInstanceOf(WsOrderMessages.MatchResult.Confirmed::class.java, result)
        assertEquals("root-ord", confirmed.orderNumber)
    }

    @Test
    fun `matchCancel unwraps a data envelope and accepts requestStatus cancel`() {
        val nested =
            WsOrderMessages.matchCancel("""{"data":{"orderNumber":"ord-1","status":"cancelled"}}""", "ord-1", "guid")
        assertInstanceOf(WsOrderMessages.MatchResult.Confirmed::class.java, nested)

        val byRequest = WsOrderMessages.matchCancel("""{"requestId":"guid-1","requestStatus":"canceled"}""", "ord-1", "guid-1")
        assertInstanceOf(WsOrderMessages.MatchResult.Confirmed::class.java, byRequest)
    }

    @Test
    fun `matchCancel rejects on an error field without status`() {
        val result = WsOrderMessages.matchCancel("""{"orderNumber":"ord-1","error":"denied"}""", "ord-1", "guid")

        val rejected = assertInstanceOf(WsOrderMessages.MatchResult.Rejected::class.java, result)
        assertEquals("denied", rejected.reason)
    }
}
