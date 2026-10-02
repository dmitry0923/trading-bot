package com.trading.bot.domain.microstructure

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Признаки микроструктуры для одного наблюдения стакана (domain-layer, без I/O).
 *
 * Существует как **единственный источник формул** для forward-L1
 * ([com.trading.bot.service.MicrostructureRecorder]) и исторической
 * реконструкции из архивов MOEX ([com.trading.bot.marketdata.MoexOrderLogParser]).
 * Это нужно, чтобы санитарная сверка «история против live» была честной: любое
 * расхождение в формуле выдало бы себя за расхождение данных, а не кода.
 *
 * Все формулы возвращают `null` при некорректных входных данных (fail-closed,
 * а не «0»): нулевой спред или нулевой mid — это не «нейтрально», это
 * отсутствие наблюдения.
 */
object MicrostructureFeatures {
    /** Знаков для округления: 8 знаков достаточно для bps от цены ~10-100. */
    const val SCALE = 8

    private val HALF_UP = RoundingMode.HALF_UP
    private val TWO = BigDecimal(2)
    private val BPS = BigDecimal(10_000)

    /**
     * Спред в базисных пунктах от mid: `(ask - bid) / mid * 10000`.
     *
     * @return null, если нет обеих сторон или mid <= 0
     */
    fun spreadBps(
        bid: BigDecimal?,
        ask: BigDecimal?,
    ): BigDecimal? {
        if (bid == null || ask == null) return null
        val mid = mid(bid, ask) ?: return null
        return ask.subtract(bid).divide(mid, SCALE, HALF_UP).multiply(BPS)
    }

    /**
     * Отклонение microprice от mid в базисных пунктах.
     *
     * Положительное = microprice выше mid (перевес ликвидности на стороне Bid),
     * отрицательное = ниже mid.
     *
     * @return null, если microprice не определён (нет стороны, non-positive
     *   размер, либо bid >= ask — пересечённый/пустой стакан)
     */
    fun deviationBps(
        bid: BigDecimal?,
        ask: BigDecimal?,
        bidSize: Long?,
        askSize: Long?,
    ): BigDecimal? {
        val mid = mid(bid, ask) ?: return null
        val microprice = MicropriceCalculator.calculate(bid, ask, bidSize, askSize) ?: return null
        return microprice.subtract(mid).divide(mid, SCALE, HALF_UP).multiply(BPS)
    }

    /** mid-price, либо null при отсутствии стороны или mid <= 0. */
    private fun mid(
        bid: BigDecimal?,
        ask: BigDecimal?,
    ): BigDecimal? {
        if (bid == null || ask == null) return null
        val mid = bid.add(ask).divide(TWO, SCALE, HALF_UP)
        return if (mid.signum() > 0) mid else null
    }
}
