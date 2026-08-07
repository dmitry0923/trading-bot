package com.trading.bot.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * Конфигурация Shadow Mode / Decision-level A/B эксперимента, prefix = "experiment".
 *
 * Значения могут переопределяться через BotSettings (UI / POST /api/v1/settings),
 * см. [com.trading.bot.service.SettingsService].
 *
 * Механика (одно решение = две "руки"):
 *  - CONTROL — текущий пайплайн агентов; при [shadowExecution]=false исполняется на
 *    реальные деньги, при =true только записывается в эксперимент.
 *  - VARIANT — экспериментальная рука: либо повторный вызов Арбитра с промптом
 *    [variantPromptVersion] (реальное A/B на уровне решения, extra LLM-вызов),
 *    либо теневая копия CONTROL (без доп. затрат).
 *  Обе руки пишутся в experiment_decisions; сравнение результатов — после закрытия
 *  контрольной позиции (P&L контрольной vs гипотетической P&L вариантной руки).
 *
 * @property enabled включает эксперимент (запись обеих рук)
 * @property experimentId идентификатор эксперимента (для различения серий)
 * @property variantPromptVersion версия промпта Арбитра для вариантной руки;
 *                                null — вариант = теневая копия контроля (без LLM)
 * @property shadowExecution полный shadow: контрольная рука НЕ исполняется
 *                           (реальных ордеров нет, только запись решений)
 */
@Component
@ConfigurationProperties(prefix = "experiment")
class ExperimentConfig {
    var enabled: Boolean = false
    var experimentId: String = "default"
    var variantPromptVersion: String? = null
    var shadowExecution: Boolean = false
    var rolloutPercent: Int = 100

    /**
     * Решение участвует в эксперименте, если его cycleId попадает в rolloutPercent.
     * Стабильный хэш cycleId, чтобы один цикл не мигрировал между группами.
     */
    fun inRollout(cycleId: String): Boolean {
        if (!enabled) return false
        if (rolloutPercent >= 100) return true
        if (rolloutPercent <= 0) return false
        val bucket = (cycleId.hashCode() and Int.MAX_VALUE) % 100
        return bucket < rolloutPercent
    }
}
