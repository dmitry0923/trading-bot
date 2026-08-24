package com.trading.bot.domain.microstructure

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Чисто функциональный калькулятор микроструктуры (domain-layer, без I/O).
 *
 * Microprice — взвешенная оценка «справедливой цены» между Bid и Ask,
 * где веса определяются глубиной на лучших уровнях стакана:
 *
 *   microprice = (ask × bidSize + bid × askSize) / (bidSize + askSize)
 *
 * В отличие от mid-price = (bid+ask)/2 microprice смещается в сторону
 * того уровня, где больше ликвидности — это более точная оценка
 * «центра гравитации» стакана для краткосрочных входов.
 *
 * Диапазон: (bid, ask) всегда строго внутри спреда.
 * Если bidSize == askSize → microprice == mid-price.
 * Если bidSize >> askSize → microprice ближе к ask (больше покупателей).
 */
object MicropriceCalculator {
    private val SCALE = 8
    private val HALF_UP = RoundingMode.HALF_UP

    /**
     * Microprice из bid/ask и их размеров.
     *
     * @return microprice, или null при некорректных данных (нет bid/ask, sizes <= 0)
     */
    fun calculate(
        bid: BigDecimal?,
        ask: BigDecimal?,
        bidSize: Long?,
        askSize: Long?,
    ): BigDecimal? {
        if (bid == null || ask == null) return null
        if (bid <= BigDecimal.ZERO || ask <= BigDecimal.ZERO) return null
        if (bid >= ask) return null
        val bs = bidSize?.takeIf { it > 0 } ?: return null
        val askSz = askSize?.takeIf { it > 0 } ?: return null

        val totalSize = bs + askSz
        // ask × bidSize + bid × askSize
        val numerator =
            ask
                .multiply(BigDecimal(bs))
                .add(bid.multiply(BigDecimal(askSz)))
        return numerator.divide(BigDecimal(totalSize), SCALE, HALF_UP)
    }

    /**
     * Microprice偏离 mid-price 的方向和大小 (absolute deviation in price units).
     * Положительное = microprice выше mid (больше.bid liquidity → смещение вверх).
     *
     * @return отклонение от mid, или null если microprice не определён
     */
    fun deviation(
        bid: BigDecimal?,
        ask: BigDecimal?,
        bidSize: Long?,
        askSize: Long?,
    ): BigDecimal? {
        val mp = calculate(bid, ask, bidSize, askSize) ?: return null
        if (bid == null || ask == null) return null
        val mid = bid.add(ask).divide(BigDecimal(2), SCALE, HALF_UP)
        return mp.subtract(mid)
    }
}
