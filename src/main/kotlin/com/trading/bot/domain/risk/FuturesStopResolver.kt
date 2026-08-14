package com.trading.bot.domain.risk

import java.math.BigDecimal

/**
 * Единая политика дистанции стоп-лосса фьючерса в пунктах для live и backtest.
 *
 * Оба контура (live [com.trading.bot.application.decision.FuturesEntryProfile]
 * и [com.trading.bot.backtest.BacktestEngine]) только вычисляют ATR из своего
 * источника данных и делегируют сюда: политика (флаг включения, fallback на
 * [FuturesAtrStopPolicy.defaultStopLossPoints], клампы
 * в [FuturesAtrStopPolicy.minPoints]..max) существует в одном месте — контуры
 * не могут разойтись.
 *
 * Не хранит конфигурацию: политика передаётся на каждый вызов, поэтому тестовый
 * движок с кастомной [FuturesAtrStopPolicy] не расходится с инстансом резолвера.
 */
class FuturesStopResolver {
    /**
     * @param atr уже посчитанный ATR (null — данные недоступны, fallback-дефолт)
     * @param priceStep шаг цены инструмента (из конфигурации инструментов live / backtest)
     * @param policy политика ATR-стопа вызывающего контура (маппинг из RiskConfig)
     * @return дистанция стопа в пунктах
     */
    fun resolve(
        atr: BigDecimal?,
        priceStep: BigDecimal,
        policy: FuturesAtrStopPolicy,
    ): Int {
        if (!policy.enabled) return policy.defaultStopLossPoints
        if (atr == null || priceStep.signum() <= 0) return policy.defaultStopLossPoints
        return Atr.stopPoints(
            atr = atr,
            priceStep = priceStep,
            multiplier = policy.multiplier,
            minPoints = policy.minPoints,
            maxPoints = policy.maxPoints,
        ) ?: policy.defaultStopLossPoints
    }
}
