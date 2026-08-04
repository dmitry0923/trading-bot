package com.trading.bot.event

import com.trading.bot.model.ExecutionReport
import com.trading.bot.model.Strategy
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * Публикация событий домена через ApplicationEventPublisher (синхронно,
 * чтобы обработчики гарантированно получили событие).
 */
@Component
class TradingEventPublisher(
    private val publisher: ApplicationEventPublisher
) {
    fun publishPriceChanged(ticker: String, price: BigDecimal) {
        publisher.publishEvent(PriceChangedEvent(ticker, price))
    }

    fun publishStrategyGenerated(strategy: Strategy) {
        publisher.publishEvent(StrategyGeneratedEvent(strategy))
    }

    fun publishEntrySignal(strategy: Strategy) {
        publisher.publishEvent(EntrySignalEvent(strategy))
    }

    fun publishExecutionReport(report: ExecutionReport) {
        publisher.publishEvent(ExecutionReportEvent(report))
    }

    fun publishPositionOpened(position: com.trading.bot.model.Position) {
        publisher.publishEvent(
            PositionOpenedEvent(
                positionId = position.id ?: -1L,
                ticker = position.ticker,
                quantity = position.quantity,
                direction = position.direction,
                entryPrice = position.entryPrice
            )
        )
    }

    fun publishPositionClosed(position: com.trading.bot.model.Position) {
        publisher.publishEvent(
            PositionClosedEvent(
                positionId = position.id ?: -1L,
                ticker = position.ticker,
                pnl = position.pnl ?: java.math.BigDecimal.ZERO,
                reason = position.closeReason ?: "CLOSED"
            )
        )
    }

    fun publishTradingHalted(event: TradingHaltedEvent) {
        publisher.publishEvent(event)
    }
}
