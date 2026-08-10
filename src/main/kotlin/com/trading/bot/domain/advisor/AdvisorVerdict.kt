package com.trading.bot.domain.advisor

/**
 * Вердикт LLM-советника (advisory layer).
 *
 * Советник НЕ формирует направление сделки: направление (BUY/SELL/HOLD) всегда
 * определяют детерминированные стратегии ([com.trading.bot.application.strategy]).
 * Советник может только:
 *  - AGREE — подтвердить направление (с корректировкой уверенности `confidenceAdjustment`);
 *  - NEUTRAL — не выразить мнение (сигнал идёт без изменений);
 *  - VETO — детерминированно заблокировать вход (риск-уровень CRITICAL).
 *
 * Вместе с вердиктом советник даёт объяснение и альтернативные сценарии —
 * информация для человека/логгирования, не для исполнительного контура.
 */
enum class AdvisorVerdictType {
    AGREE,
    NEUTRAL,
    VETO,
}

/** Уровень риска по мнению советника. Только CRITICAL ведёт к VETO. */
enum class AdvisorRiskLevel {
    LOW,
    NORMAL,
    HIGH,
    CRITICAL,
}

/**
 * Результат совета LLM по уже принятому детерминированному решению.
 *
 * @param verdict тип вердикта (AGREE/NEUTRAL/VETO)
 * @param confidenceAdjustment аддитивная поправка к уверенности (clamped в
 *                             [com.trading.bot.application.advisor.LlmAdvisor])
 * @param explanation обоснование совета (для логов/reasoning)
 * @param alternativeScenarios альтернативные сценарии (для отчётности)
 * @param riskLevel уровень риска, при CRITICAL вход блокируется
 */
data class AdvisorVerdict(
    val verdict: AdvisorVerdictType,
    val confidenceAdjustment: Double = 0.0,
    val explanation: String = "",
    val alternativeScenarios: List<String> = emptyList(),
    val riskLevel: AdvisorRiskLevel = AdvisorRiskLevel.LOW,
) {
    /** Блокирует ли вердикт вход в позицию. */
    val blocksEntry: Boolean
        get() = verdict == AdvisorVerdictType.VETO || riskLevel == AdvisorRiskLevel.CRITICAL

    companion object {
        /** Нейтральный вердикт (советник недоступен или сигнала нет). */
        val NEUTRAL: AdvisorVerdict = AdvisorVerdict(AdvisorVerdictType.NEUTRAL)

        /** Fail-open вердикт при недоступности LLM: сигнал идёт без изменений. */
        fun fallback(reason: String): AdvisorVerdict =
            AdvisorVerdict(
                verdict = AdvisorVerdictType.NEUTRAL,
                explanation = "Advisor unavailable: $reason",
            )

        /** Детерминированный VETO (CRITICAL-уровень риска). */
        fun veto(reason: String): AdvisorVerdict =
            AdvisorVerdict(
                verdict = AdvisorVerdictType.VETO,
                explanation = reason,
                riskLevel = AdvisorRiskLevel.CRITICAL,
            )
    }
}
