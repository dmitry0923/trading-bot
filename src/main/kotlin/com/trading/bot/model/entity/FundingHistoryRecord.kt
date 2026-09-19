package com.trading.bot.model.entity

import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Фактический funding (MOEX SWAPRATE) за клиринговый день — источник исторического
 * ряда for backtest P&L и funding-veto (research). Единицы: RUB/контракт/клиринг
 * ([valueRubPerContract]) и сырое значение биржи ([rawValue], ставка за 1 ед.
 * базового актива до применения lot-множителя).
 */
data class FundingHistoryRecord(
    val id: Long? = null,
    val ticker: String,
    val clearingDate: LocalDate,
    val rawValue: BigDecimal,
    val valueRubPerContract: BigDecimal,
    val source: String = "MOEX",
    val createdAt: LocalDateTime = LocalDateTime.now(),
)
