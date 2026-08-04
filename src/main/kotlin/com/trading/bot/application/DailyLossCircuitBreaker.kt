package com.trading.bot.application

import com.trading.bot.domain.risk.FuturesRiskEngine
import com.trading.bot.event.PositionClosedEvent
import com.trading.bot.event.TradingEventPublisher
import com.trading.bot.event.TradingHaltedEvent
import io.micrometer.core.instrument.MeterRegistry
import mu.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Circuit breaker по дневному лимиту убытка.
 *
 * При закрытии позиции:
 *   1. Обновляет дневной P&L в FuturesRiskEngine (dailyPnL = sum(closed P&L за день)).
 *   2. Если dailyPnL <= -5 000 ₽ (10% депозита) → публикует TradingHaltedEvent.
 *
 * Слушатели:
 *   - FuturesTradingBotService перестаёт открывать новые позиции (isDailyLossLimitReached).
 *   - PositionMonitor продолжает закрывать открытые позиции по SL/TP, но новых не открывает.
 *   - В лог пишется алерт об остановке торговли.
 *
 * Метрика: circuit.daily_loss.triggered (Counter).
 */
@Component
class DailyLossCircuitBreaker(
    private val futuresRiskEngine: FuturesRiskEngine,
    private val eventPublisher: TradingEventPublisher,
    private val meterRegistry: MeterRegistry,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Обрабатывает закрытие позиции: обновляет дневной P&L и при достижении
     * лимита убытка публикует TradingHaltedEvent (остановка новых входов).
     *
     * @param event событие закрытия позиции с P&L сделки
     */
    @EventListener
    fun onPositionClosed(event: PositionClosedEvent) {
        // Акции учитываются RiskManagementService непосредственно при закрытии.
        // Этот breaker обслуживает только фьючерсный риск-движок.
        if (!event.ticker.equals("Si", ignoreCase = true)) return
        futuresRiskEngine.updateDailyPnL(event.pnl)
        if (futuresRiskEngine.isDailyLossLimitReached()) {
            logger.error {
                "Daily loss limit reached (dailyPnL=${futuresRiskEngine.getDailyPnL()} ₽). " +
                    "Trading halted. No new entries until next day."
            }
            eventPublisher.publishTradingHalted(TradingHaltedEvent(reason = "DAILY_LOSS_LIMIT"))
            meterRegistry.counter("circuit.daily_loss.triggered").increment()
        }
    }
}
