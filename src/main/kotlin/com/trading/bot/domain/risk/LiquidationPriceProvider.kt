package com.trading.bot.domain.risk

import com.trading.bot.model.PositionDirection
import java.math.BigDecimal

/**
 * Источник ликвидационной цены фьючерсной позиции.
 *
 * Разделяет два класса источников:
 * - **Estimated** ([EstimatedLiquidationPriceProvider]) — собственная оценочная
 *   формула (буфер = маржа / pointValue), консервативный pre-trade guard;
 * - **Exchange** (реализация по мере доступности) — реальная ликвидационная цена
 *   биржи/брокера, если она предоставляется API (например расчётчик маржи FORTS).
 *
 * Риск-движок должен отдавать предпочтение биржевому источнику, когда он доступен
 * (мониторинг позиций использует значение из Position.liquidationPrice, которое
 * выставляется сайзером при входе). Собственная формула остаётся как консервативный
 * fallback.
 *
 * Чистые примитивы (priceStep/priceStepCost) вместо конфиг-классов — домен не
 * зависит от конфигурации (та же конвенция, что у FuturesStopResolver).
 */
interface LiquidationPriceProvider {
    /**
     * Оценочная/биржевая ликвидационная цена для LONG/SHORT.
     *
     * @param entryPrice цена входа; null — цена неизвестна → null
     * @param direction направление позиции; null → null
     * @param marginPerContract маржа на один контракт (руб)
     * @param priceStep шаг цены (например 0.01 для Si)
     * @param priceStepCost стоимость шага цены (например 10 ₽)
     * @return цена ликвидации либо null, если источник не может её оценить
     */
    fun liquidationPrice(
        entryPrice: BigDecimal?,
        direction: PositionDirection?,
        marginPerContract: BigDecimal,
        priceStep: BigDecimal,
        priceStepCost: BigDecimal,
    ): BigDecimal?
}
