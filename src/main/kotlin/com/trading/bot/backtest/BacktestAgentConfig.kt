package com.trading.bot.backtest

import com.trading.bot.infrastructure.llm.PromptRegistry
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
 * @property techMinSignalStrength минимальная уверенность тех-отчёта для входа в
 *  стратег-агент. Live держит 0.5 (жёсткий guardrail в [com.trading.bot.agent.StrategyAgent]);
 *  в бэктесте для сигнал-проверки можно занижать (0.0 = вход при любом выводе кроме
 *  INSUFFICIENT_DATA).
 * @property promptVersion версия LLM-шаблонов промптов (default/aggressive/conservative).
 *  В бэктесте research-режим использует aggressive, чтобы LLM мог дать BUY/SELL по
 *  одному сильному анализу.
 * @property confidenceThreshold порог уверенности стратега и арбитра — один на
 *  всю цепочку, как в live ([com.trading.bot.service.AdaptiveRiskService]
 *  передаёт одинаковое значение в `formulate` и `adjudicate`). Дефолт 0.60 =
 *  live-fallback без статистики (`stats == null`). В бэктесте нет истории сделок,
 *  поэтому адаптивный порог не вычисляется, а берётся из конфига.
 */
@Component
@ConfigurationProperties(prefix = "bt.agent")
class BacktestAgentConfig {
    var enabled: Boolean = false
    var sampleEvery: Int = 20
    var temperature: Double = 0.0
    var cacheNamespace: String = "backtest"
    var techMinSignalStrength: Double = 0.0
    var promptVersion: String = PromptRegistry.DEFAULT_VERSION
    var confidenceThreshold: Double = 0.60
}
