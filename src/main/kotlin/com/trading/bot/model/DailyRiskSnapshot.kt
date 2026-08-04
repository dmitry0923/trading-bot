package com.trading.bot.model

import java.math.BigDecimal
import java.time.LocalDate

/**
 * Дневной риск-снапшот (таблица daily_risk_snapshot).
 * Позволяет восстановить daily P&L и статус лимита после рестарта в течение торгового дня.
 */
data class DailyRiskSnapshot(
    val id: Long? = null,
    val tradeDate: LocalDate,
    val dailyPnl: BigDecimal,
    val limitReached: Boolean,
    val maxDrawdownToday: BigDecimal
)
