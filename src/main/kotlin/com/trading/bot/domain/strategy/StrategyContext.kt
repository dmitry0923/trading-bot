package com.trading.bot.domain.strategy

import com.trading.bot.domain.technical.IndicatorCalculator
import com.trading.bot.model.dto.MarketSnapshot
import com.trading.bot.model.entity.Candle
import java.math.BigDecimal

/**
 * Контекст стратегического этапа: рыночные данные, доступные любой стратегии.
 *
 * Несёт ТОЛЬКО входные данные (свечи, снапшот, индикаторы) и идентификаторы
 * цикла. [relatedQuote] — цена связанного инструмента (пара для арбитража),
 * если она настроена; иначе null. Выход стратегии — [StrategyDecision] (чистое
 * направление BUY/SELL/HOLD, цена и уверенность); риск-параметры
 * (quantity/SL/TP/trailing) стратегия не вычисляет — их считают
 * RiskEngine/PositionSizer/OrderBuilder ниже по пайплайну.
 */
data class StrategyContext(
    val ticker: String,
    val snapshot: MarketSnapshot,
    val candles: List<Candle>,
    val indicators: IndicatorCalculator.Indicators?,
    val cycleId: String,
    val contextPrompt: String? = null,
    val relatedQuote: BigDecimal? = null,
)
