package com.trading.bot.application.funding

import java.math.BigDecimal
import java.time.LocalDateTime

/** Источник значения funding. */
enum class FundingSource {
    /** Фиксированное конфигурационное значение (SIM/backtest/fallback). */
    CONFIG,

    /** Актуальное значение из MOEX (LIVE-источник). */
    MOEX,
}

/** Единица raw-значения funding, которое вернул источник. */
enum class FundingUnit {
    /** Сырое значение в единицах источника (scale уточняется per instrument pre-LIVE). */
    RAW_UNKNOWN,

    /** RUB за 1 контракт за 1 клиринг — канон для P&L. */
    RUB_PER_CONTRACT_PER_CLEARING,
}

/**
 * Снапшот фьючерсного funding (P0-аудит): конкретное значение + единица raw + момент
 * съёма + источник. [valueRubPerContractPerClearing] — каноническая форма для P&L
 * (RUB за 1 контракт за 1 клиринг, см. [FundingCosts]).
 */
data class FundingSnapshot(
    val ticker: String,
    val rawValue: BigDecimal,
    val unit: FundingUnit,
    val valueRubPerContractPerClearing: BigDecimal,
    val source: FundingSource,
    val timestamp: LocalDateTime,
)
