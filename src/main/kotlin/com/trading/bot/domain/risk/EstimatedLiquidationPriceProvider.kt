package com.trading.bot.domain.risk

import com.trading.bot.model.PositionDirection
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Оценочная ликвидационная цена (conservative pre-trade guard, НЕ биржевая).
 *
 * Формула:
 *   pointValue = priceStepCost / priceStep (для Si: 10 / 0.01 = 1000 ₽ на 1.0 цены)
 *   bufferPrice = marginPerContract / pointValue = GO / pointValue
 *     Si: 15000 / 1000 = 15 ₽ — при таком движении против позиции теряется
 *         вся маржа контракта (вариационная маржа ≈ GO).
 *   LONG  -> entryPrice - bufferPrice
 *   SHORT -> entryPrice + bufferPrice
 *
 * Плечо здесь НЕ участвует: пользовательское leverage не влияет ни на требуемую
 * биржей маржу, ни на дистанцию до ликвидации (полное GO).
 *
 * Упрощена (без maintenance margin, комиссий и режима позиции). Если брокер/биржа
 * предоставляет реальную ликвидационную цену — зарегистрировать альтернативную
 * реализацию [LiquidationPriceProvider] и предпочитать её.
 */
@Component
class EstimatedLiquidationPriceProvider : LiquidationPriceProvider {
    override fun liquidationPrice(
        entryPrice: BigDecimal?,
        direction: PositionDirection?,
        marginPerContract: BigDecimal,
        priceStep: BigDecimal,
        priceStepCost: BigDecimal,
    ): BigDecimal? {
        if (entryPrice == null || direction == null) return null
        val pointValue = priceStepCost.divide(priceStep, 6, RoundingMode.HALF_UP)
        if (pointValue <= BigDecimal.ZERO) return null

        val bufferPrice =
            marginPerContract
                .divide(pointValue, 6, RoundingMode.HALF_UP)

        return when (direction) {
            PositionDirection.LONG -> entryPrice.subtract(bufferPrice)
            PositionDirection.SHORT -> entryPrice.add(bufferPrice)
        }
    }
}
