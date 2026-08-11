package com.trading.bot.application

import com.trading.bot.event.PositionClosedEvent
import com.trading.bot.event.TradingEventPublisher
import com.trading.bot.event.TradingHaltedEvent
import com.trading.bot.service.DrawdownProtectionService
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Circuit breaker по дневному лимиту убытка.
 *
 * При закрытии позиции:
 *   1. Обновляет дневной P&L в едином источнике [DrawdownProtectionService]
 *      (синхронный аккумулятор, персистится в daily_risk_snapshot).
 *   2. Если dailyPnL <= -maxDailyLossPercent% AUM → публикует TradingHaltedEvent.
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
    private val drawdownProtection: DrawdownProtectionService,
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
        drawdownProtection.updateDailyPnl(event.pnl, event.accountId)
        if (drawdownProtection.isDailyLossLimitReached(event.accountId)) {
            logger.error {
                "Daily loss limit reached (dailyPnL=${drawdownProtection.getDailyPnl(event.accountId)} ₽). " +
                    "Trading halted. No new entries until next day."
            }
            eventPublisher.publishTradingHalted(TradingHaltedEvent(reason = "DAILY_LOSS_LIMIT"))
            meterRegistry.counter("circuit.daily_loss.triggered").increment()
        }
    }
}
