package com.trading.bot.model.entity

import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * Снапшот макро-контекста (roadmap v2.4, раздел 13.11.2).
 *
 * Периодический слепок ставки ЦБ, нефти Brent и курса USD/RUB, собранный
 * [com.trading.bot.service.MacroSnapshotCollector]. Исторические снапшоты
 * позволяют экспорту ML-датасета использовать макро-значения, актуальные
 * на момент ВХОДА в позицию (без lookahead), вместо текущих.
 */
data class MacroSnapshot(
    val id: Long? = null,
    val capturedAt: LocalDateTime,
    val cbrRate: BigDecimal,
    val brentPrice: BigDecimal,
    val usdRub: BigDecimal,
)
