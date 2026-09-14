package com.trading.bot.service

import com.trading.bot.infrastructure.tracing.LlmTrace
import com.trading.bot.model.entity.AgentLog
import com.trading.bot.model.entity.Position
import com.trading.bot.model.entity.Strategy
import com.trading.bot.model.entity.TradeEvent
import com.trading.bot.repository.AgentLogRepository
import com.trading.bot.repository.PositionRepository
import com.trading.bot.repository.StrategyRepository
import com.trading.bot.repository.TradeEventRepository
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * Вердикт LLM-советника, выделенный из agent_logs (agent_name = [LineageService.ADVISOR_AGENT]).
 */
data class LineageAdvisorDecision(
    val verdict: String?,
    val confidenceAdjustment: Double?,
    val riskLevel: String?,
    val explanation: String?,
    val blocksEntry: Boolean,
    val overrideReason: String?,
    val storageKey: String?,
)

/**
 * Полная цепочка принятия решения по циклу (trace_id = cycleId):
 * agent_logs (tech→fund→strategy→contrarian→arbitrator + советник) → strategies →
 * positions → trade_events → (опционально) сырые LLM-трейсы из S3.
 *
 * [complete] = ориентир полноты прослеживаемости: есть стратегия цикла,
 * вердикт советника и запись LLM-стратега (маркер, что LLM-цепочка реально
 * участвовала). [missing] — что именно отсутствует.
 */
data class LineageChain(
    val cycleId: String,
    val advisor: LineageAdvisorDecision,
    val agentLogs: List<AgentLog>,
    val strategies: List<Strategy>,
    val positions: List<Position>,
    val tradeEvents: List<TradeEvent>,
    val traces: List<LlmTrace>,
    val complete: Boolean,
    val missing: List<String>,
)

/**
 * Прослеживаемость LLM-решений (Этап 2, lineage): реконструкция полной цепочки
 * принятия решения по [cycleId] — от промптов агентов до сделки.
 *
 * - agent_logs связаны с циклом через cycle_id, каждая строка несёт storage_key
 *   (полный трейс промпт/ответ в S3/MinIO);
 * - strategies.rawJson содержит решения ВСЕХ стратегий цикла (включая LLM-цепочку);
 * - positions.cycle_id линкует сделку на сигнал цикла, trade_events — её аудит-трейл.
 *
 * Метрики:
 * - `lineage.chain.complete` / `lineage.chain.incomplete{missing=...}` — полнота
 *   прослеживаемости по циклу;
 * - `lineage.chain.llm_visible{visible=true|false}` — участвовала ли LLM-цепочка
 *   (запись Agent-3-Strategist) в цикле.
 */
@Service
class LineageService(
    private val agentLogRepository: AgentLogRepository,
    private val strategyRepository: StrategyRepository,
    private val positionRepository: PositionRepository,
    private val tradeEventRepository: TradeEventRepository,
    private val traceQueryService: TraceQueryService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) {
    suspend fun buildChain(
        cycleId: String,
        includeTraces: Boolean = false,
        tracesLimit: Int = 50,
    ): LineageChain {
        require(cycleId.isNotBlank()) { "cycleId must not be blank" }

        val logs = agentLogRepository.findByCycleId(cycleId)
        val strategies = strategyRepository.findByCycleId(cycleId)
        val positions = positionRepository.findByCycleId(cycleId)
        val advisor = toAdvisorDecision(logs)
        val tradeEvents = findTradeEvents(positions)
        val traces =
            if (includeTraces) {
                traceQueryService.listByCycleId(cycleId, tracesLimit)
            } else {
                emptyList()
            }

        val missing = missingOf(strategies, logs, advisor)
        val complete = missing.isEmpty()
        recordCompleteness(logs, complete, missing)

        return LineageChain(
            cycleId = cycleId,
            advisor = advisor,
            agentLogs = logs.sortedBy { it.createdAt },
            strategies = strategies.sortedBy { it.createdAt },
            positions = positions,
            tradeEvents = tradeEvents,
            traces = traces,
            complete = complete,
            missing = missing,
        )
    }

    private suspend fun findTradeEvents(positions: List<Position>): List<TradeEvent> {
        if (positions.isEmpty()) return emptyList()
        val aggregateIds =
            positions.mapNotNull { position ->
                position.id?.let { id -> UUID.nameUUIDFromBytes("position:$id".toByteArray()) }
            }
        return tradeEventRepository.findByAggregateIds(aggregateIds)
    }

    private fun toAdvisorDecision(logs: List<AgentLog>): LineageAdvisorDecision {
        val row =
            logs.lastOrNull { it.agentName == ADVISOR_AGENT }
                ?: return LineageAdvisorDecision(
                    verdict = null,
                    confidenceAdjustment = null,
                    riskLevel = null,
                    explanation = null,
                    blocksEntry = false,
                    overrideReason = null,
                    storageKey = null,
                )
        return LineageAdvisorDecision(
            verdict = row.action,
            confidenceAdjustment = row.signalStrength,
            riskLevel = parseRiskLevel(row.rawOutput),
            explanation = row.reasoning,
            blocksEntry = row.overrideReason == OVERRIDE_REASON_VETO,
            overrideReason = row.overrideReason,
            storageKey = row.storageKey,
        )
    }

    private fun parseRiskLevel(rawOutput: String?): String? {
        if (rawOutput.isNullOrBlank()) return null
        return try {
            val node = objectMapper.readTree(rawOutput)
            if (node.isObject) {
                node.path("riskLevel").asString().takeIf { it.isNotBlank() }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun missingOf(
        strategies: List<Strategy>,
        logs: List<AgentLog>,
        advisor: LineageAdvisorDecision,
    ): List<String> =
        buildList {
            if (strategies.isEmpty()) add("strategy")
            if (advisor.verdict == null) add("advisor")
            if (logs.none { it.agentName == STRATEGIST_AGENT }) add("strategist")
        }

    private fun recordCompleteness(
        logs: List<AgentLog>,
        complete: Boolean,
        missing: List<String>,
    ) {
        val llmVisible = logs.any { it.agentName == STRATEGIST_AGENT }
        meterRegistry
            .counter("lineage.chain.llm_visible", Tags.of("visible", llmVisible.toString()))
            .increment()
        if (complete) {
            meterRegistry.counter("lineage.chain.complete").increment()
        } else {
            meterRegistry
                .counter(
                    "lineage.chain.incomplete",
                    Tags.of("missing", missing.joinToString(",")),
                ).increment()
        }
    }

    companion object {
        const val ADVISOR_AGENT = "Agent-6-Advisor"
        const val STRATEGIST_AGENT = "Agent-3-Strategist"
        const val OVERRIDE_REASON_VETO = "ADVISOR_VETO"
    }
}
