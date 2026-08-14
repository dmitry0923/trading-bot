package com.trading.bot.service

import com.trading.bot.config.ExperimentConfig
import com.trading.bot.domain.signal.Signal
import com.trading.bot.domain.strategy.StrategyDecision
import com.trading.bot.event.PositionClosedEvent
import com.trading.bot.infrastructure.llm.PromptRegistry
import com.trading.bot.model.entity.ExperimentDecision
import com.trading.bot.repository.ExperimentDecisionRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Shadow Mode / Decision-level A/B эксперимент.
 *
 * Для каждого цикла пишутся две записи в [ExperimentDecisionRepository]:
 *  - CONTROL — решение текущего пайплайна (победитель StrategyRunner); исполняется
 *    (если не включён полный shadow);
 *  - VARIANT — экспериментальная рука (is_paper=true, никогда не исполняется):
 *    повторный вызов Арбитра через DiscretionaryStrategy.produceVariant с
 *    [ExperimentConfig.variantPromptVersion] (реальное A/B, extra LLM-вызов)
 *    либо теневая копия CONTROL (без доп. затрат).
 *
 * Исходы сравниваются при закрытии контрольной позиции ([onPositionClosed]):
 *  - контрольная рука получает фактический P&L;
 *  - вариантной руке считается гипотетический P&L:
 *    HOLD -> 0, противоположное направление -> -P&L, то же направление -> P&L * qty_ratio.
 *
 * Метрики: experiment.decision.logged{arm,action}, experiment.control.executed/shadowed,
 * experiment.variant.llm{mode=LLM|COPY}, experiment.outcome.marked{arm}.
 */
@Service
class PaperTradingService(
    private val experimentConfig: ExperimentConfig,
    private val decisionRepository: ExperimentDecisionRepository,
    private val meterRegistry: MeterRegistry,
) {
    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun isExperimentEnabled(): Boolean = experimentConfig.enabled

    /** Полный shadow: контрольная рука тоже не исполняется. */
    fun isShadowExecution(): Boolean = experimentConfig.enabled && experimentConfig.shadowExecution

    /** Цикл участвует в эксперименте (rollout). */
    fun inExperiment(cycleId: String): Boolean = experimentConfig.inRollout(cycleId)

    /**
     * Записывает контрольное решение эксперимента.
     *
     * @param executed фактически исполнено (false — полный shadow)
     */
    suspend fun recordControlDecision(
        cycleId: String,
        ticker: String,
        timeframe: String,
        signal: Signal,
        rawJson: String?,
        executed: Boolean,
    ): ExperimentDecision {
        val decision =
            ExperimentDecision(
                cycleId = cycleId,
                experimentId = experimentConfig.experimentId,
                arm = "CONTROL",
                ticker = ticker,
                timeframe = timeframe,
                action = signal.action.name,
                targetPrice = signal.targetPrice,
                quantity = 0,
                stopLoss = null,
                takeProfit = null,
                signalStrength = signal.signalStrength,
                reasoning = signal.reasoning,
                isPaper = false,
                version = PromptRegistry.DEFAULT_VERSION,
                rawOutput = rawJson,
                executed = executed,
            )
        decisionRepository.save(decision)
        meterRegistry.counter("experiment.decision.logged", Tags.of("arm", "CONTROL", "action", signal.action.name)).increment()
        if (executed) {
            meterRegistry.counter("experiment.control.executed").increment()
        } else {
            meterRegistry.counter("experiment.control.shadowed").increment()
        }
        return decision
    }

    /** Версия промпта вариантной руки; null — вариант = теневая копия контроля. */
    fun variantVersion(): String? = experimentConfig.variantPromptVersion

    /**
     * Записывает вариантное (paper) решение эксперимента. Никогда не исполняется.
     *
     * @param variant решение вариантной руки: LLM-пересчёт Арбитра
     *                (DiscretionaryStrategy.produceVariant) либо теневая копия контроля
     * @param version версия промпта вариантной руки (null — теневая копия)
     */
    suspend fun recordVariantDecision(
        cycleId: String,
        ticker: String,
        timeframe: String,
        variant: StrategyDecision,
        version: String?,
    ): ExperimentDecision {
        val decision =
            ExperimentDecision(
                cycleId = cycleId,
                experimentId = experimentConfig.experimentId,
                arm = "VARIANT",
                ticker = ticker,
                timeframe = timeframe,
                action = variant.action.name,
                targetPrice = variant.targetPrice,
                quantity = 0,
                stopLoss = null,
                takeProfit = null,
                signalStrength = variant.signalStrength,
                reasoning = variant.reasoning,
                isPaper = true,
                version = version ?: "shadow-copy",
                executed = false,
            )
        decisionRepository.save(decision)
        meterRegistry.counter("experiment.decision.logged", Tags.of("arm", "VARIANT", "action", variant.action.name)).increment()
        meterRegistry.counter("experiment.variant.llm", Tags.of("mode", if (version != null) "LLM" else "COPY")).increment()
        logger.info {
            "Experiment ${experimentConfig.experimentId}: $ticker/$timeframe variant=${variant.action} " +
                "conf=${String.format("%.2f", variant.signalStrength)} (${if (version != null) "LLM v$version" else "shadow copy"})"
        }
        return decision
    }

    /**
     * При закрытии контрольной позиции фиксирует исход обеих рук.
     */
    @EventListener
    fun onPositionClosed(event: PositionClosedEvent) {
        val cycleId = event.cycleId ?: return
        scope.launch {
            try {
                markOutcome(cycleId, event.pnl)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to mark experiment outcome for cycle $cycleId" }
            }
        }
    }

    private suspend fun markOutcome(
        cycleId: String,
        pnl: BigDecimal,
    ) {
        val control = decisionRepository.findByCycleIdAndArm(cycleId, "CONTROL") ?: return
        decisionRepository.markResult(cycleId, "CONTROL", pnl)
        recordOutcomeMetrics("CONTROL", pnl)
        meterRegistry.counter("experiment.outcome.marked", Tags.of("arm", "CONTROL")).increment()

        val variant = decisionRepository.findByCycleIdAndArm(cycleId, "VARIANT") ?: return
        val variantPnl = evaluateVariantPnl(variant, control, pnl)
        decisionRepository.markResult(cycleId, "VARIANT", variantPnl)
        recordOutcomeMetrics("VARIANT", variantPnl)
        meterRegistry.counter("experiment.outcome.marked", Tags.of("arm", "VARIANT")).increment()
    }

    /**
     * Экспорт P&L в Prometheus для Grafana: profit/loss разбиваются на два счётчика
     * (P&L может быть отрицательным, поэтому нельзя использовать одну DistributionSummary
     * без риска потери отрицательных значений в гистограмме). Win = pnl > 0.
     */
    private fun recordOutcomeMetrics(
        arm: String,
        pnl: BigDecimal,
    ) {
        val tags = Tags.of("arm", arm)
        if (pnl >= BigDecimal.ZERO) {
            meterRegistry.counter("experiment.outcome.pnl_profit", tags).increment(pnl.toDouble())
        } else {
            meterRegistry.counter("experiment.outcome.pnl_loss", tags).increment(pnl.abs().toDouble())
        }
        if (pnl > BigDecimal.ZERO) {
            meterRegistry.counter("experiment.outcome.win", tags).increment()
        }
    }

    /**
     * Гипотетический P&L вариантной руки относительно фактического P&L контрольной:
     * - HOLD -> 0 (не входил, не терял);
     * - противоположное направление -> -P&L;
     * - то же направление -> P&L, масштабированный по соотношению объёмов.
     */
    private fun evaluateVariantPnl(
        variant: ExperimentDecision,
        control: ExperimentDecision,
        pnl: BigDecimal,
    ): BigDecimal {
        if (variant.action == "HOLD") return BigDecimal.ZERO
        if (variant.action != control.action) return pnl.negate()
        val controlQty = control.quantity
        val variantQty = variant.quantity
        if (controlQty > 0 && variantQty > 0 && controlQty != variantQty) {
            return pnl.multiply(BigDecimal(variantQty)).divide(BigDecimal(controlQty), 4, RoundingMode.HALF_UP)
        }
        return pnl
    }

    suspend fun recentDecisions(limit: Int): List<ExperimentDecision> = decisionRepository.findRecent(limit)

    fun status(): Map<String, Any?> =
        mapOf(
            "enabled" to experimentConfig.enabled,
            "experimentId" to experimentConfig.experimentId,
            "variantPromptVersion" to experimentConfig.variantPromptVersion,
            "shadowExecution" to experimentConfig.shadowExecution,
            "rolloutPercent" to experimentConfig.rolloutPercent,
        )
}
