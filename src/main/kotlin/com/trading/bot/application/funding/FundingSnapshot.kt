package com.trading.bot.application.funding

import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/** Источник значения funding. */
enum class FundingSource {
    /** Фиксированное конфигурационное значение (SIM/backtest). */
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
 * Снапшот фьючерсного funding (P0-аудит): конкретное значение за ОДИН клиринг
 * ([clearingDate]) + единица raw + момент съёма + источник.
 * [valueRubPerContractPerClearing] — каноническая форма для P&L
 * (RUB за 1 контракт за 1 клиринг, см. [FundingCosts]).
 *
 * P&L позиции вычитает СУММУ значений за каждый пережитый клиринг (по датам из
 * [FundingCosts.clearingDates]), а не «текущее значение × число клирингов» —
 * серия снапшотов по [clearingDate] накапливается в
 * [com.trading.bot.application.funding.FundingSnapshotService].
 */
data class FundingSnapshot(
    val ticker: String,
    val clearingDate: LocalDate,
    val rawValue: BigDecimal,
    val unit: FundingUnit,
    val valueRubPerContractPerClearing: BigDecimal,
    val source: FundingSource,
    val timestamp: LocalDateTime,
)
