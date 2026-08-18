package com.trading.bot.domain.risk

import com.trading.bot.model.entity.Candle
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Детерминированный детектор вырожденных рыночных случаев (roadmap 13.3.5).
 *
 * Чисто функциональный (без состояния и I/O) — единая математика для
 * пре-входного guard'а ([com.trading.bot.service.DegenerateCaseGuard]) и
 * исполняющего слоя ([com.trading.bot.client.AlorClient.placeMarketOrder]).
 *
 *   - WIDE_SPREAD: спред (ask-bid)/ask в % выше порога [isWideSpread].
 *     Fail-open при отсутствии/некорректности котировок (спред = 0 — не блокируем).
 *   - PRICE_GAP: открывающий гэп |open - prevClose|/prevClose в % выше порога
 *     [isGap] (недостаточно свечей — не блокируем на уровне детектора;
 *     проверка количества свечей делается в [com.trading.bot.service.DegenerateCaseGuard]).
 *   - DEPOSITARY_PAUSE: [consecutiveZeroVolumeBars] подряд идущих свечей с нулевым
 *     объёмом (депозитарная/торговая пауза) — [isDepositaryPause].
 */
object DegenerateCaseDetector {
    /**
     * Спред в долях цены: (ask - bid) / ask, scale 6, HALF_UP.
     *
     * Fail-open: отсутствующие bid/ask заменяются на [currentPrice];
     * bid <= 0, ask <= 0 или bid >= ask → спред = 0 (не блокируем).
     */
    fun spreadPercent(
        bid: BigDecimal?,
        ask: BigDecimal?,
        currentPrice: BigDecimal,
    ): BigDecimal {
        val b = bid ?: currentPrice
        val a = ask ?: currentPrice
        if (b <= BigDecimal.ZERO || a <= BigDecimal.ZERO || b >= a) return BigDecimal.ZERO
        return a.subtract(b).divide(a, 6, RoundingMode.HALF_UP)
    }

    /**
     * Превышает ли спред порог [maxSpreadPercent] в %. Порог <= 0 — проверка отключена.
     */
    fun isWideSpread(
        bid: BigDecimal?,
        ask: BigDecimal?,
        currentPrice: BigDecimal,
        maxSpreadPercent: BigDecimal,
    ): Boolean {
        if (maxSpreadPercent <= BigDecimal.ZERO) return false
        return spreadPercent(bid, ask, currentPrice)
            .compareTo(maxSpreadPercent.divide(BigDecimal("100"), 6, RoundingMode.HALF_UP)) > 0
    }

    /**
     * Есть ли открывающий гэп на последней свече: |open - prevClose|/prevClose в %
     * выше порога [maxGapPercent]. Нужно >= 2 свечей и prevClose > 0.
     * Порог <= 0 — проверка отключена.
     */
    fun isGap(
        candles: List<Candle>,
        maxGapPercent: BigDecimal,
    ): Boolean {
        if (maxGapPercent <= BigDecimal.ZERO || candles.size < 2) return false
        val last = candles.last()
        val prevClose = candles[candles.size - 2].closePrice
        if (prevClose <= BigDecimal.ZERO) return false
        val gap =
            last.openPrice
                .subtract(prevClose)
                .abs()
                .divide(prevClose, 6, RoundingMode.HALF_UP)
        return gap.compareTo(maxGapPercent.divide(BigDecimal("100"), 6, RoundingMode.HALF_UP)) > 0
    }

    /**
     * Депозитарная пауза: последние [consecutiveZeroVolumeBars] свечей имеют нулевой объём.
     * Нужно не меньше свечей, чем порог. Порог <= 0 — проверка отключена.
     */
    fun isDepositaryPause(
        candles: List<Candle>,
        consecutiveZeroVolumeBars: Int,
    ): Boolean {
        if (consecutiveZeroVolumeBars <= 0 || candles.size < consecutiveZeroVolumeBars) return false
        return candles.takeLast(consecutiveZeroVolumeBars).all { it.volume == 0L }
    }
}
