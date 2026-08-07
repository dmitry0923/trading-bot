package com.trading.bot.application

import com.trading.bot.event.PositionClosedEvent
import com.trading.bot.event.TradingEventPublisher
import com.trading.bot.event.TradingHaltedEvent
import com.trading.bot.service.DrawdownProtectionService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal

/**
 * Проверка circuit breaker по дневному лимиту убытка.
 */
class DailyLossCircuitBreakerTest {
    private val drawdownProtection: DrawdownProtectionService = Mockito.mock(DrawdownProtectionService::class.java)
    private val meterRegistry = SimpleMeterRegistry()
    private val haltedEvents = mutableListOf<TradingHaltedEvent>()
    private val publisher =
        TradingEventPublisher { event -> if (event is TradingHaltedEvent) haltedEvents += event }

    private fun breaker() = DailyLossCircuitBreaker(drawdownProtection, publisher, meterRegistry)

    @Test
    fun `position closed updates daily pnl in drawdown protection`() {
        Mockito.`when`(drawdownProtection.isDailyLossLimitReached()).thenReturn(false)

        breaker().onPositionClosed(
            PositionClosedEvent(positionId = 1, ticker = "Si", pnl = BigDecimal("-1000"), reason = "STOP_LOSS"),
        )

        Mockito.verify(drawdownProtection).updateDailyPnl(BigDecimal("-1000"))
        assertTrue(haltedEvents.isEmpty())
        assertEquals(0.0, meterRegistry.counter("circuit.daily_loss.triggered").count(), 0.001)
    }

    @Test
    fun `daily loss limit reached publishes trading halted event`() {
        Mockito.`when`(drawdownProtection.isDailyLossLimitReached()).thenReturn(true)

        breaker().onPositionClosed(
            PositionClosedEvent(positionId = 1, ticker = "Si", pnl = BigDecimal("-14000"), reason = "LIQUIDATION_CRITICAL"),
        )

        Mockito.verify(drawdownProtection).updateDailyPnl(BigDecimal("-14000"))
        assertEquals(1, haltedEvents.size)
        assertEquals("DAILY_LOSS_LIMIT", haltedEvents.single().reason)
        assertEquals(1.0, meterRegistry.counter("circuit.daily_loss.triggered").count(), 0.001)
    }

    @Test
    fun `not triggered when daily loss not reached`() {
        Mockito.`when`(drawdownProtection.isDailyLossLimitReached()).thenReturn(false)

        breaker().onPositionClosed(
            PositionClosedEvent(positionId = 1, ticker = "Si", pnl = BigDecimal("-3000"), reason = "STOP_LOSS"),
        )

        Mockito.verify(drawdownProtection).updateDailyPnl(BigDecimal("-3000"))
        assertTrue(haltedEvents.isEmpty())
        assertEquals(0.0, meterRegistry.counter("circuit.daily_loss.triggered").count(), 0.001)
    }
}
