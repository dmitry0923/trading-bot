package com.trading.bot.service

import com.trading.bot.repository.TradingHaltRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito

class TradingHaltServiceTest {
    private val repository = Mockito.mock(TradingHaltRepository::class.java)

    @Test
    fun `record updates in-memory last`() {
        val service = TradingHaltService(repository)

        runBlocking { service.record("DAILY_LOSS_LIMIT", "RISK_SYSTEM", "detail") }

        val last = service.last()
        assertEquals("DAILY_LOSS_LIMIT", last?.reason)
        assertEquals("RISK_SYSTEM", last?.source)
        assertEquals("detail", last?.detail)
    }

    @Test
    fun `clear resets last`() {
        val service = TradingHaltService(repository)
        runBlocking { service.record("STATE_DESYNC", "STATE_RECONCILIATION") }

        runBlocking { service.clear() }

        assertNull(service.last())
    }

    @Test
    fun `empty repository returns null before any record`() {
        val service = TradingHaltService(repository)

        assertNull(service.last())
    }

    @Test
    fun `stale daily loss halt is ignored on the next trading day`() {
        // Иначе первый пробитый дневной лимит блокировал бы входы навсегда,
        // переживая и смену дня, и рестарт (скан-15 P1).
        val service = TradingHaltService(repository)
        val yesterdayNoon =
            java.time.LocalDate
                .now(java.time.ZoneId.of("Europe/Moscow"))
                .minusDays(1)
                .atTime(12, 0)
                .atZone(java.time.ZoneId.of("Europe/Moscow"))
                .toInstant()

        runBlocking {
            service.record(TradingHaltService.DAILY_LOSS_LIMIT_REASON, "RISK_SYSTEM", haltedAt = yesterdayNoon)
        }

        assertNull(service.last(), "дневной halt вчерашнего дня не должен блокировать сегодняшние входы")
    }

    @Test
    fun `daily loss halt recorded today still blocks`() {
        val service = TradingHaltService(repository)
        runBlocking { service.record(TradingHaltService.DAILY_LOSS_LIMIT_REASON, "RISK_SYSTEM") }

        assertEquals(TradingHaltService.DAILY_LOSS_LIMIT_REASON, service.last()?.reason)
    }

    @Test
    fun `clear keeps emergency stop halt`() {
        // EMERGENCY_STOP снимается только resume(); обычный тумблер enable не должен его стирать.
        val service = TradingHaltService(repository)
        runBlocking { service.record(TradingHaltService.EMERGENCY_STOP_REASON, "EMERGENCY_STOP") }

        runBlocking { service.clear(keepReasons = setOf(TradingHaltService.EMERGENCY_STOP_REASON)) }

        assertEquals(TradingHaltService.EMERGENCY_STOP_REASON, service.last()?.reason)
        runBlocking { Mockito.verify(repository, Mockito.never()).deleteAll() }
    }
}
