package com.trading.bot.event

import com.trading.bot.model.PositionDirection
import com.trading.bot.model.dto.ExecutionReport
import com.trading.bot.model.entity.Strategy
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime

/**
 * События домена торгового бота.
 *
 * Event-Driven слой: замена @Scheduled polling для критичных операций
 * (вход/выход, исполнение ордеров). Обработчики — @EventListener в сервисах.
 *
 * Поток:
 * PriceChangedEvent -> мониторинг открытых позиций (SL/TP/trailing)
 * StrategyGeneratedEvent -> публикация EntrySignalEvent при пригодном сигнале
 * EntrySignalEvent -> RiskEngine.assessEntry() + открытие позиции
 * ExecutionReportEvent -> фиксация фактического исполнения (closePrice, P&L, slippage)
 */
data class PriceChangedEvent(
    val ticker: String,
    val price: BigDecimal,
    val timestamp: Instant = Instant.now(),
)

data class StrategyGeneratedEvent(
    val strategy: Strategy,
)

data class EntrySignalEvent(
    val strategy: Strategy,
)

data class ExecutionReportEvent(
    val report: ExecutionReport,
)

/**
 * Позиция закрыта (по SL/TP/trailing/ликвидации). Источник — FuturesTradingBotService.
 * Обработчик — DailyLossCircuitBreaker: обновляет дневной P&L и проверяет daily loss limit.
 */
data class PositionClosedEvent(
    val positionId: Long,
    val ticker: String,
    val pnl: BigDecimal,
    val reason: String,
    val closedAt: LocalDateTime = LocalDateTime.now(),
    val cycleId: String? = null,
)

data class PositionOpenedEvent(
    val positionId: Long,
    val ticker: String,
    val quantity: Int,
    val direction: PositionDirection,
    val entryPrice: BigDecimal,
    val openedAt: LocalDateTime = LocalDateTime.now(),
    val cycleId: String? = null,
)

/**
 * Глобальная остановка торговли. reason: DAILY_LOSS_LIMIT | LEVERAGE_DISABLED | MANUAL
 */
data class TradingHaltedEvent(
    val reason: String,
    val timestamp: Instant = Instant.now(),
)
