package com.trading.bot.model.dto

import java.math.BigDecimal

/**
 * DTO разбивки сделок по причинам закрытия (для аналитики).
 *
 * @param ticker тикер инструмента
 * @param total всего сделок
 * @param wins число прибыльных сделок
 * @param avgWin средняя прибыль
 * @param avgLoss средний убыток
 * @param closeReason причина закрытия (STOP_LOSS / TAKE_PROFIT / ...)
 * @param reasonCount сколько сделок закрыто по этой причине
 */
data class TradeBreakdownDto(
    val ticker: String,
    val total: Long,
    val wins: Long,
    val avgWin: BigDecimal?,
    val avgLoss: BigDecimal?,
    val closeReason: String,
    val reasonCount: Long,
)
