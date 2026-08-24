package com.trading.bot.domain.microstructure

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Order Book Imbalance (OBI) — чисто функциональный калькулятор.
 *
 * OBI = (bidSize - askSize) / (bidSize + askSize)
 *
 * Диапазон: [-1.0, +1.0]
 *   +1.0 = весь стакан на стороне Bid (чистый buy pressure)
 *    0.0 = симметричный стакан
 *   -1.0 = весь стакан на стороне Ask (чистый sell pressure)
 *
 * Используется для:
 * - Оценки направления давления (entry timing): BUY при OBI > 0, SELL при OBI < 0
 * - Блокировки входа при сильном противодавлении (anti-trend OBI)
 * - Взвешивания размера позиции (confidence-aware sizing)
 */
object ObiCalculator {
    private val SCALE = 4
    private val HALF_UP = RoundingMode.HALF_UP

    /**
     * Order Book Imbalance.
     *
     * @return OBI в диапазоне [-1.0, +1.0], или null при некорректных данных
     */
    fun calculate(
        bidSize: Long?,
        askSize: Long?,
    ): BigDecimal? {
        val bs = bidSize?.takeIf { it >= 0 } ?: return null
        val as_ = askSize?.takeIf { it >= 0 } ?: return null
        val total = bs + as_
        if (total == 0L) return null
        val imbalance = BigDecimal(bs - as_).divide(BigDecimal(total), SCALE, HALF_UP)
        return imbalance.coerceIn(BigDecimal("-1.0"), BigDecimal("1.0"))
    }

    /**
     * OBI противоречит ли направлению входа?
     *
     * BUY-сигнал заблокирован при obi < -[threshold] (сильное sell pressure).
     * SELL-сигнал заблокирован при obi > [threshold] (сильное buy pressure).
     *
     * @param obi текущий OBI
     * @param side направление: "buy" или "sell"
     * @param threshold порог |OBI| для блокировки (0.0..1.0)
     * @return true если вход заблокирован из-за противодавления стакана
     */
    fun isOpposing(
        obi: BigDecimal?,
        side: String,
        threshold: BigDecimal,
    ): Boolean {
        if (obi == null) return false
        if (threshold <= BigDecimal.ZERO) return false
        return when (side.lowercase()) {
            "buy" -> obi < threshold.negate()
            "sell" -> obi > threshold
            else -> false
        }
    }

    /**
     * OBI → сигнал давления (нормализованный к [-1, +1]).
     * Обёртка для использования в scoring/weighting.
     */
    fun pressure(
        bidSize: Long?,
        askSize: Long?,
    ): Double? = calculate(bidSize, askSize)?.toDouble()
}
