package com.trading.bot.service

import com.trading.bot.agent.ArbitratorAgent
import com.trading.bot.agent.ContrarianAgent
import com.trading.bot.agent.StrategyAgent
import com.trading.bot.config.ExperimentConfig
import com.trading.bot.event.PositionClosedEvent
import com.trading.bot.infrastructure.llm.PromptRegistry
import com.trading.bot.model.dto.FundamentalReport
import com.trading.bot.model.dto.MarketSnapshot
import com.trading.bot.model.dto.RiskContext
import com.trading.bot.model.dto.TechnicalReport
import com.trading.bot.model.entity.ExperimentDecision
import com.trading.bot.model.entity.Strategy
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
 *  - CONTROL — решение текущего пайплайна; исполняется (если не включён полный shadow);
 *  - VARIANT — экспериментальная рука (is_paper=true, никогда не исполняется):
 *    повторный вызов Арбитра с [ExperimentConfig.variantPromptVersion] (реальное A/B,
 *    extra LLM-вызов) либо теневая копия CONTROL (без доп. затрат).
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
    private val arbitratorAgent: ArbitratorAgent,
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
        strategy: Strategy,
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
                action = strategy.action.name,
                targetPrice = strategy.targetPrice,
                quantity = strategy.quantity,
                stopLoss = strategy.stopLoss,
                takeProfit = strategy.takeProfit,
                confidence = strategy.confidence,
                reasoning = strategy.reasoning,
                isPaper = false,
                version = PromptRegistry.DEFAULT_VERSION,
                rawOutput = rawJson,
                executed = executed,
            )
        decisionRepository.save(decision)
        meterRegistry.counter("experiment.decision.logged", Tags.of("arm", "CONTROL", "action", strategy.action.name)).increment()
        if (executed) {
            meterRegistry.counter("experiment.control.executed").increment()
        } else {
            meterRegistry.counter("experiment.control.shadowed").increment()
        }
        return decision
    }

    /**
     * Вычисляет и записывает вариантное (paper) решение. Никогда не исполняется.
     */
    suspend fun produceVariantDecision(
        cycleId: String,
        ticker: String,
        timeframe: String,
        draft: StrategyAgent.Draft,
        challenge: ContrarianAgent.ChallengeReport,
        tech: TechnicalReport,
        fund: FundamentalReport,
        snapshot: MarketSnapshot,
        control: Strategy,
        contextPrompt: String?,
        adaptiveConfidence: Double,
        riskContext: RiskContext,
    ): ExperimentDecision {
        val version = experimentConfig.variantPromptVersion
        val usedLlm = version != null
        val variantFinal: ArbitratorAgent.Final =
            if (usedLlm) {
                // Реальное A/B: тот же вход, другой промпт Арбитра. Semantic cache
                // обходится, чтобы вариантная рука не получила ответ контрольной.
                arbitratorAgent.adjudicate(
                    draft = draft,
                    challenge = challenge,
                    tech = tech,
                    fund = fund,
                    snapshot = snapshot,
                    cycleId = cycleId,
                    contextPrompt = contextPrompt,
                    adaptiveConfidence = adaptiveConfidence,
                    riskContext = riskContext,
                    version = version,
                    bypassCache = true,
                )
            } else {
                // Без variantPromptVersion вариант = тень контроля (копия решения).
                ArbitratorAgent.Final(
                    action = control.action,
                    targetPrice = control.targetPrice,
                    quantity = control.quantity,
                    stopLoss = control.stopLoss,
                    takeProfit = control.takeProfit,
                    trailingStop = control.trailingStop,
                    confidence = control.confidence,
                    reasoning = control.reasoning,
                )
            }

        val decision =
            ExperimentDecision(
                cycleId = cycleId,
                experimentId = experimentConfig.experimentId,
                arm = "VARIANT",
                ticker = ticker,
                timeframe = timeframe,
                action = variantFinal.action.name,
                targetPrice = variantFinal.targetPrice,
                quantity = variantFinal.quantity,
                stopLoss = variantFinal.stopLoss,
                takeProfit = variantFinal.takeProfit,
                confidence = variantFinal.confidence,
                reasoning = variantFinal.reasoning,
                isPaper = true,
                version = version ?: "shadow-copy",
                executed = false,
            )
        decisionRepository.save(decision)
        meterRegistry.counter("experiment.decision.logged", Tags.of("arm", "VARIANT", "action", variantFinal.action.name)).increment()
        meterRegistry.counter("experiment.variant.llm", Tags.of("mode", if (usedLlm) "LLM" else "COPY")).increment()
        logger.info {
            "Experiment ${experimentConfig.experimentId}: $ticker/$timeframe variant=${variantFinal.action} " +
                "conf=${String.format("%.2f", variantFinal.confidence)} (${if (usedLlm) "LLM v$version" else "shadow copy"})"
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
