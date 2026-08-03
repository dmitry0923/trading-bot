package com.trading.bot.model

import java.math.BigDecimal

/**
 * DTO для агрегированной статистики закрытых сделок.
 * Используется вместо native query projection для надёжности.
 */
data class TradeBreakdownDto(
    val ticker: String,
    val total: Long,
    val wins: Long,
    val avgWin: BigDecimal?,
    val avgLoss: BigDecimal?,
    val closeReason: String,
    val reasonCount: Long
)
