package com.trading.bot.agent

/**
 * Вердикт LLM-veto поверх детерминированного сигнала (Вариант B, docs/24).
 *
 * Семантика:
 * - [ALLOW] — LLM согласен с входом, детерминированный сигнал проходит без изменений.
 * - [BLOCK] — LLM явно заблокировал вход (ArbitratorAgent вернул HOLD с реальным вердиктом).
 * - [HOLD]  — fail-closed: LLM недоступен, таймаут или ошибка вызова. По умолчанию
 *             блокирует вход (безопасное поведение при деградации инфраструктуры).
 *
 * Используется [com.trading.bot.backtest.AgentVetoBacktestSignalGenerator] для
 * маршрутизации результата veto-проверки в [com.trading.bot.model.StrategyAction].
 */
sealed class VetoResult {
    /** LLM согласен с входом. Детерминированный сигнал применяется без изменений. */
    data object ALLOW : VetoResult()

    /**
     * LLM заблокировал вход.
     *
     * @property reason причина блокировки (из [com.trading.bot.backtest.LlmVeto.Reason]).
     */
    data class BLOCK(
        val reason: String,
    ) : VetoResult()

    /**
     * Fail-closed: LLM недоступен или произошла ошибка.
     *
     * Вход блокируется во избежание торговли без актуального вето-суждения.
     *
     * @property cause техническая причина отказа для диагностики.
     */
    data class HOLD(
        val cause: String,
    ) : VetoResult()
}
