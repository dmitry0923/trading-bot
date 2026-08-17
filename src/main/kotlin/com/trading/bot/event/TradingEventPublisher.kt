package com.trading.bot.event

import com.trading.bot.domain.signal.Signal
import com.trading.bot.model.dto.ExecutionReport
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * Публикация событий домена через ApplicationEventPublisher (синхронно,
 * чтобы обработчики гарантированно получили событие).
 */
@Component
class TradingEventPublisher(
    private val publisher: ApplicationEventPublisher,
) {
    fun publishPriceChanged(
        ticker: String,
        price: BigDecimal,
    ) {
        publisher.publishEvent(PriceChangedEvent(ticker, price))
    }

    fun publishStrategyGenerated(signal: Signal) {
        publisher.publishEvent(StrategyGeneratedEvent(signal))
    }

    fun publishExecutionReport(report: ExecutionReport) {
        publisher.publishEvent(ExecutionReportEvent(report))
    }

    fun publishPositionOpened(position: com.trading.bot.model.entity.Position) {
        publisher.publishEvent(
            PositionOpenedEvent(
                positionId = position.id ?: -1L,
                ticker = position.ticker,
                quantity = position.quantity,
                direction = position.direction,
                entryPrice = position.entryPrice,
                cycleId = position.cycleId,
                accountId = position.accountId,
            ),
        )
    }

    fun publishPositionClosed(position: com.trading.bot.model.entity.Position) {
        publisher.publishEvent(
            PositionClosedEvent(
                positionId = position.id ?: -1L,
                ticker = position.ticker,
                pnl = position.pnl ?: BigDecimal.ZERO,
                reason = position.closeReason?.code ?: "CLOSED",
                cycleId = position.cycleId,
                accountId = position.accountId,
            ),
        )
    }

    fun publishTradingHalted(event: TradingHaltedEvent) {
        publisher.publishEvent(event)
    }
}
