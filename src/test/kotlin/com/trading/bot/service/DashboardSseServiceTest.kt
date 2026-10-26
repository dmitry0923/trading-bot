package com.trading.bot.service

import com.trading.bot.event.PositionClosedEvent
import com.trading.bot.event.PriceChangedEvent
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigDecimal

/**
 * Рассылка дашборда не должна занимать поток котировок.
 *
 * События домена публикуются синхронно из WS-flow цен; `DashboardService.build` делает
 * запросы к БД и `analyzeLastNDays`. Раньше это выполнялось на потоке котировок, буфер
 * `DROP_OLDEST` терял тики и SL/TP/trailing не видели цен (скан-7 P1). Плюс антидребезг
 * терял последнее событие: закрытые позиции оставались «открытыми» у клиентов (скан-7 P2).
 */
class DashboardSseServiceTest {
    private val dashboardService: DashboardService = mock()
    private val meterRegistry = SimpleMeterRegistry()
    private val service = DashboardSseService(dashboardService, jacksonObjectMapper(), meterRegistry)

    @AfterEach
    fun stop() {
        service.shutdown()
    }

    private fun broadcastCount(): Double = meterRegistry.find("dashboard.sse.broadcasts").counters().sumOf { it.count() }

    private fun awaitBroadcasts(
        min: Double,
        timeoutMs: Long = 5_000,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (broadcastCount() >= min) return true
            Thread.sleep(20)
        }
        return false
    }

    @Test
    fun `price event returns immediately while the snapshot is being built`() {
        runBlocking {
            whenever(dashboardService.build(anyOrNull())).thenAnswer {
                Thread.sleep(400)
                mapOf("aum" to 1)
            }
        }
        service.subscribe()
        awaitBroadcasts(1.0)

        val started = System.currentTimeMillis()
        service.onPriceChanged(PriceChangedEvent("SBER", BigDecimal("100")))
        val elapsed = System.currentTimeMillis() - started

        assertTrue(elapsed < 200, "broadcast must run off the caller thread, took ${elapsed}ms")
    }

    @Test
    fun `throttled events are not lost - a trailing broadcast is scheduled`() {
        runBlocking { whenever(dashboardService.build(anyOrNull())).thenAnswer { mapOf("aum" to 1) } }
        service.subscribe()

        // Первое событие рассылается сразу, следующие два попадают в окно антидребезга:
        // последнее обязано дойти до клиентов догоняющим broadcast.
        service.onPositionClosed(PositionClosedEvent(1L, "SBER", BigDecimal("10"), "TAKE_PROFIT", cycleId = "c1"))
        assertTrue(awaitBroadcasts(1.0), "first broadcast expected")
        service.onPositionClosed(PositionClosedEvent(2L, "GAZP", BigDecimal("-5"), "STOP_LOSS", cycleId = "c2"))
        service.onPositionClosed(PositionClosedEvent(3L, "LKOH", BigDecimal("-7"), "STOP_LOSS", cycleId = "c3"))

        assertTrue(awaitBroadcasts(2.0), "trailing broadcast must happen, got ${broadcastCount()}")
    }
}
