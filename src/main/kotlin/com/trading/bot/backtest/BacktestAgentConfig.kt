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
 * @property signalBudgetMs бюджет LLM-цепочки в мс (research, 0 = выключен).
 *  При превышении — fail-closed HOLD + `backtest.agent.timeout{cause=budget}`,
 *  как в live (`trading.llm-signal-budget-ms`, docs/17 §17.8 R2). По умолчанию 0,
 *  чтобы уже измеренные LLM-прогоны не изменились.
 * @property timeoutInjectionRate доля сэмплов (0.0…1.0), в которых таймаут LLM
 *  имитируется реальной задержкой внутри бюджета (research, 0.0 = выключено).
 *  Требует `signalBudgetMs > 0`. Нужен, чтобы доказать, что таймаут приводит
 *  к HOLD, а не к входу по детерминированному fallback'у, и измерить потерю
 *  сигналов; карта инъекций детерминирована по (ticker, индекс бара).
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
    var signalBudgetMs: Long = 0
    var timeoutInjectionRate: Double = 0.0
}
