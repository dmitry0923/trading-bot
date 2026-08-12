package com.trading.bot.backtest

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * Конфигурация LLM-агентов в бэктесте (prefix = "bt.agent").
 *
 * При `enabled=true` сигнал в прогоне формирует конвейер живых агентов
 * (tech → fund → strategy → contrarian → arbitrator) вместо детерминированной
 * RSI+MACD+Bollinger-эвристики. Агентный режим включается профилем `backtest`
 * (application-backtest.yml).
 *
 * @property enabled включает агентный режим генерации сигналов.
 * @property sampleEvery оценка агентов каждые N баров (между сэмплами HOLD).
 * @property temperature температура генерации LLM (бэктесту нужна 0.0 — детерминизм).
 * @property cacheNamespace изолирует semantic cache от live-контура
 *  (исключает look-ahead bias и загрязнение live-кэша бэктест-ответами).
 */
@Component
@ConfigurationProperties(prefix = "bt.agent")
class BacktestAgentConfig {
    var enabled: Boolean = false
    var sampleEvery: Int = 20
    var temperature: Double = 0.0
    var cacheNamespace: String = "backtest"
}
