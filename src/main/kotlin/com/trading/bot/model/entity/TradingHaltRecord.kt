package com.trading.bot.model.entity

import java.time.Instant

/**
 * Последняя глобальная остановка торговли (таблица trading_halt, одна строка).
 *
 * Персистится, чтобы причина останова (DAILY_LOSS_LIMIT / STATE_DESYNC / LEVERAGE_DISABLED /
 * MANUAL_DISABLE) переживала рестарт приложения. R2DBC-репозиторий — [com.trading.bot.repository.TradingHaltRepository].
 */
data class TradingHaltRecord(
    val id: Long? = null,
    val reason: String,
    val source: String,
    val detail: String = "",
    val haltedAt: Instant,
)
