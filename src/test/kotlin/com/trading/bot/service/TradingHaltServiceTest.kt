package com.trading.bot.service

import com.trading.bot.repository.TradingHaltRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito

class TradingHaltServiceTest {
    private val repository = Mockito.mock(TradingHaltRepository::class.java)

    @Test
    fun `record updates in-memory last`() {
        val service = TradingHaltService(repository)

        service.record("DAILY_LOSS_LIMIT", "RISK_SYSTEM", "detail")

        val last = service.last()
        assertEquals("DAILY_LOSS_LIMIT", last?.reason)
        assertEquals("RISK_SYSTEM", last?.source)
        assertEquals("detail", last?.detail)
    }

    @Test
    fun `clear resets last`() {
        val service = TradingHaltService(repository)
        service.record("STATE_DESYNC", "STATE_RECONCILIATION")

        service.clear()

        assertNull(service.last())
    }

    @Test
    fun `empty repository returns null before any record`() {
        val service = TradingHaltService(repository)

        assertNull(service.last())
    }
}
