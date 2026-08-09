package com.trading.bot.domain.risk

import com.trading.bot.model.PositionDirection
import com.trading.bot.model.StrategyAction
import com.trading.bot.model.entity.Position
import java.math.BigDecimal

/**
 * Входные данные для риск-проверки перед открытием позиции.
 *
 * @param ticker инструмент
 * @param action направление стратегии (BUY/SELL)
 * @param entryPrice цена входа (последняя известная цена)
 * @param direction направление позиции (LONG/SHORT)
 * @param portfolioMoney текущий депозит в рублях
 * @param currentGo текущее гарантийное обеспечение (для фьючерсов)
 * @param atr средний истинный диапазон в единицах цены (для ATR%-фильтра)
 * @param openPositions текущие открытые позиции
 */
data class EntryRequest(
    val ticker: String,
    val action: StrategyAction,
    val entryPrice: BigDecimal,
    val direction: PositionDirection,
    val portfolioMoney: BigDecimal,
    val currentGo: BigDecimal,
    val atr: BigDecimal? = null,
    val openPositions: List<Position> = emptyList(),
)

/**
 * Риск-этап пайплайна: принимает решение Да/Нет о входе.
 *
 * Реализации должны быть чистыми (без Spring, без БД, без вычисления
 * SL/TP/размера позиции) — эти задачи выполняют PositionSizer и OrderBuilder.
 */
interface RiskEngine {
    suspend fun canEnter(request: EntryRequest): RiskVerdict
}
