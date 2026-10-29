package com.trading.bot.event

import com.trading.bot.domain.signal.Signal
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.StrategyAction
import com.trading.bot.model.dto.ExecutionReport
import com.trading.bot.model.dto.OrderStatus
import com.trading.bot.model.entity.Position
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.argumentCaptor
import java.math.BigDecimal

/**
 * Unit-тесты [TradingEventPublisher]: публикация событий и подстановки по умолчанию
 * для незаполненных полей (id → -1, pnl → 0, reason → CLOSED).
 *
 * Пакет `event` имел 35,7 % покрытия ветвей — подстановки не проверялись вовсе (скан-19).
 */
class TradingEventPublisherTest {
    private val publisher = Mockito.mock(org.springframework.context.ApplicationEventPublisher::class.java)
    private val tradingEventPublisher = TradingEventPublisher(publisher)

    private fun position(
        id: Long? = 7L,
        pnl: BigDecimal? = BigDecimal("12.50"),
    ) = Position(
        id = id,
        ticker = "CNYRUBF",
        direction = PositionDirection.LONG,
        quantity = 2,
        entryPrice = BigDecimal("92.0"),
        pnl = pnl,
        status = PositionStatus.CLOSED,
        accountId = 3L,
        cycleId = "cycle-1",
    )

    @Test
    fun `price changed event carries ticker and price`() {
        tradingEventPublisher.publishPriceChanged("SBER", BigDecimal("250.5"))

        val captor = argumentCaptor<PriceChangedEvent>()
        Mockito.verify(publisher).publishEvent(captor.capture())
        assertEquals("SBER", captor.firstValue.ticker)
        assertEquals(0, BigDecimal("250.5").compareTo(captor.firstValue.price))
    }

    @Test
    fun `position opened uses placeholder id when position has no id`() {
        tradingEventPublisher.publishPositionOpened(position(id = null))

        val captor = argumentCaptor<PositionOpenedEvent>()
        Mockito.verify(publisher).publishEvent(captor.capture())
        assertEquals(-1L, captor.firstValue.positionId)
        assertEquals("CNYRUBF", captor.firstValue.ticker)
        assertEquals(2, captor.firstValue.quantity)
        assertEquals(3L, captor.firstValue.accountId)
    }

    @Test
    fun `position closed without pnl publishes zero and keeps closed reason`() {
        // fail-open для дневного лимита: событие уходит с нулём, но с явным reason.
        tradingEventPublisher.publishPositionClosed(position(pnl = null))

        val captor = argumentCaptor<PositionClosedEvent>()
        Mockito.verify(publisher).publishEvent(captor.capture())
        assertEquals(0, BigDecimal.ZERO.compareTo(captor.firstValue.pnl))
        assertEquals("CLOSED", captor.firstValue.reason)
    }

    @Test
    fun `position closed with pnl keeps value and cycle`() {
        tradingEventPublisher.publishPositionClosed(position())

        val captor = argumentCaptor<PositionClosedEvent>()
        Mockito.verify(publisher).publishEvent(captor.capture())
        assertEquals(0, BigDecimal("12.50").compareTo(captor.firstValue.pnl))
        assertEquals("cycle-1", captor.firstValue.cycleId)
        assertEquals(7L, captor.firstValue.positionId)
    }

    @Test
    fun `strategy and execution report events are forwarded as is`() {
        val signal =
            Signal(
                ticker = "SBER",
                action = StrategyAction.BUY,
                targetPrice = BigDecimal("250"),
                signalStrength = 0.7,
                reasoning = "test",
                timeframe = "MINUTE_10",
                cycleId = "cycle-1",
            )
        tradingEventPublisher.publishStrategyGenerated(signal)
        tradingEventPublisher.publishExecutionReport(
            ExecutionReport(
                orderId = "o-1",
                status = OrderStatus.FILLED,
                cumulativeFilledQty = 1,
                avgPrice = BigDecimal("250"),
            ),
        )

        val captor = argumentCaptor<Any>()
        Mockito.verify(publisher, Mockito.times(2)).publishEvent(captor.capture())
        assertEquals(true, captor.allValues.any { it is StrategyGeneratedEvent })
        assertEquals(true, captor.allValues.any { it is ExecutionReportEvent })
    }
}
