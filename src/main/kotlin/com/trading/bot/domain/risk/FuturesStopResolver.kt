package com.trading.bot.domain.risk

import com.trading.bot.config.RiskConfig
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * Единая политика дистанции стоп-лосса фьючерса в пунктах для live и backtest.
 *
 * Оба контура (live [com.trading.bot.application.decision.FuturesEntryProfile]
 * и [com.trading.bot.backtest.BacktestEngine]) только вычисляют ATR из своего
 * источника данных и делегируют сюда: политика (флаг включения, fallback на
 * [RiskConfig.defaultStopLossPoints], клампы в [RiskConfig.futuresAtrStopMinPoints]..max)
 *  существует в одном месте — контуры не могут разойтись.
 *
 *  Не хранит конфигурацию: она передаётся на каждый вызов, поэтому тестовый
 *  движок с кастомным [RiskConfig] не расходится с инстансом резолвера.
 */
@Component
class FuturesStopResolver {
    /**
     * @param atr уже посчитанный ATR (null — данные недоступны, fallback-дефолт)
     * @param priceStep шаг цены инструмента (из конфигурации инструментов live / backtest)
     * @param config риск-конфигурация вызывающего контура
     * @return дистанция стопа в пунктах
     */
    fun resolve(
        atr: BigDecimal?,
        priceStep: BigDecimal,
        config: RiskConfig,
    ): Int {
        if (!config.futuresAtrStopEnabled) return config.defaultStopLossPoints
        if (atr == null || priceStep.signum() <= 0) return config.defaultStopLossPoints
        return Atr.stopPoints(
            atr = atr,
            priceStep = priceStep,
            multiplier = config.futuresAtrStopMultiplier,
            minPoints = config.futuresAtrStopMinPoints,
            maxPoints = config.futuresAtrStopMaxPoints,
        ) ?: config.defaultStopLossPoints
    }
}
