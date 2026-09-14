package com.trading.bot.service

import com.trading.bot.infrastructure.tracing.LlmTrace
import com.trading.bot.model.InstrumentType
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.StrategyAction
import com.trading.bot.model.entity.AgentLog
import com.trading.bot.model.entity.Position
import com.trading.bot.model.entity.Strategy
import com.trading.bot.model.entity.TradeEvent
import com.trading.bot.repository.AgentLogRepository
import com.trading.bot.repository.PositionRepository
import com.trading.bot.repository.StrategyRepository
import com.trading.bot.repository.TradeEventRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

/**
 * LineageService (Этап 2, прослеживаемость LLM-решений): реконструкция полной
 * цепочки по cycleId — agent_logs (все агенты + советник) → strategies → positions →
 * trade_events → (опционально) сырые LLM-трейсы. Покрытие: полная цепочка,
 * VETO-советник с разбором riskLevel, неполная цепочка с метриками, резолюция
 * trade_events по агрегатам позиций, includeTraces, валидация cycleId.
 */
class LineageServiceTest {
    private val agentLogRepository = Mockito.mock(AgentLogRepository::class.java)
    private val strategyRepository = Mockito.mock(StrategyRepository::class.java)
    private val positionRepository = Mockito.mock(PositionRepository::class.java)
    private val tradeEventRepository = Mockito.mock(TradeEventRepository::class.java)
    private val traceQueryService = Mockito.mock(TraceQueryService::class.java)
    private val meterRegistry = SimpleMeterRegistry()
    private val objectMapper = ObjectMapper()
    private val positionAggregateId = UUID.nameUUIDFromBytes("position:42".toByteArray())

    private fun service(): LineageService =
        LineageService(
            agentLogRepository,
            strategyRepository,
            positionRepository,
            tradeEventRepository,
            traceQueryService,
            objectMapper,
            meterRegistry,
        )

    private fun log(
        agent: String,
        action: String,
        at: LocalDateTime,
        strength: Double? = null,
        overrideReason: String? = null,
        rawOutput: String? = null,
    ) = AgentLog(
        cycleId = "cycle-1",
        agentName = agent,
        ticker = "CNYRUBF",
        action = action,
        signalStrength = strength,
        rawOutput = rawOutput,
        overrideReason = overrideReason,
        createdAt = at,
    )

    private fun strategistLog(at: LocalDateTime) = log("Agent-3-Strategist", "BUY", at, strength = 0.71)

    private fun advisorLog(
        at: LocalDateTime,
        action: String = "AGREE",
        strength: Double? = 0.05,
        overrideReason: String? = null,
        rawOutput: String? = null,
    ) = log("Agent-6-Advisor", action, at, strength = strength, overrideReason = overrideReason, rawOutput = rawOutput)

    private fun strategy(cycleId: String = "cycle-1") =
        Strategy(
            ticker = "CNYRUBF",
            action = StrategyAction.BUY,
            targetPrice = BigDecimal("111.00"),
            quantity = 1,
            signalStrength = 0.71,
            reasoning = "trend",
            cycleId = cycleId,
            validUntil = LocalDateTime.now().plusMinutes(1),
            strategyName = "DiscretionaryStrategy",
            rawJson = """{"strategy":"DiscretionaryStrategy","confidence":0.71}""",
        )

    private fun position(cycleId: String = "cycle-1") =
        Position(
            id = 42,
            ticker = "CNYRUBF",
            direction = PositionDirection.LONG,
            quantity = 1,
            entryPrice = BigDecimal("110.00"),
            cycleId = cycleId,
            instrumentType = InstrumentType.FUTURES,
            openedAt = LocalDateTime.now(),
        )

    private fun tradeEvent(aggregateId: UUID) =
        TradeEvent(
            aggregateId = aggregateId,
            eventType = "POSITION_OPENED",
            payload = """{"cycleId":"cycle-1","ticker":"CNYRUBF"}""",
            occurredAt = LocalDateTime.now(),
            sequenceNumber = 1,
        )

    private fun stubLogs(logs: List<AgentLog>) {
        runBlocking {
            Mockito
                .`when`(agentLogRepository.findByCycleId("cycle-1"))
                .thenReturn(logs)
        }
    }

    private fun stubEmptyPositions() {
        runBlocking {
            Mockito.`when`(positionRepository.findByCycleId("cycle-1")).thenReturn(emptyList())
        }
    }

    @Test
    fun `buildChain assembles full chain and marks complete when all components present`() {
        val t0 = LocalDateTime.of(2026, 9, 10, 10, 0)
        stubLogs(
            listOf(
                log("Agent-1-Technical", "BUY", t0),
                strategistLog(t0.plusSeconds(1)),
                advisorLog(t0.plusSeconds(2)),
            ),
        )
        runBlocking {
            Mockito.`when`(strategyRepository.findByCycleId("cycle-1")).thenReturn(listOf(strategy()))
            Mockito.`when`(positionRepository.findByCycleId("cycle-1")).thenReturn(listOf(position()))
            Mockito
                .`when`(tradeEventRepository.findByAggregateIds(listOf(positionAggregateId)))
                .thenReturn(listOf(tradeEvent(positionAggregateId)))
        }

        val chain = runBlocking { service().buildChain("cycle-1") }

        assertTrue(chain.complete)
        assertTrue(chain.missing.isEmpty())
        assertEquals("AGREE", chain.advisor.verdict)
        assertEquals(0.05, chain.advisor.confidenceAdjustment)
        assertFalse(chain.advisor.blocksEntry)
        assertEquals(3, chain.agentLogs.size)
        assertEquals(1, chain.strategies.size)
        assertEquals(1, chain.positions.size)
        assertEquals(1, chain.tradeEvents.size)
        assertTrue(chain.traces.isEmpty())
        Mockito.verifyNoInteractions(traceQueryService)
        assertEquals(1.0, meterRegistry.counter("lineage.chain.complete").count())
        assertEquals(1.0, meterRegistry.counter("lineage.chain.llm_visible", "visible", "true").count())
    }

    @Test
    fun `buildChain parses advisor veto and riskLevel from raw output`() {
        val t0 = LocalDateTime.of(2026, 9, 10, 10, 0)
        stubLogs(
            listOf(
                strategistLog(t0),
                advisorLog(
                    t0.plusSeconds(1),
                    action = "VETO",
                    strength = null,
                    overrideReason = LineageService.OVERRIDE_REASON_VETO,
                    rawOutput = """{"verdict":"VETO","riskLevel":"HIGH","reason":"volatility spike"}""",
                ),
            ),
        )
        runBlocking {
            Mockito.`when`(strategyRepository.findByCycleId("cycle-1")).thenReturn(listOf(strategy()))
        }
        stubEmptyPositions()

        val chain = runBlocking { service().buildChain("cycle-1") }

        assertEquals("VETO", chain.advisor.verdict)
        assertNull(chain.advisor.confidenceAdjustment)
        assertEquals("HIGH", chain.advisor.riskLevel)
        assertTrue(chain.advisor.blocksEntry)
        assertEquals(LineageService.OVERRIDE_REASON_VETO, chain.advisor.overrideReason)
    }

    @Test
    fun `buildChain marks cycle incomplete and records metrics when components missing`() {
        val t0 = LocalDateTime.of(2026, 9, 10, 10, 0)
        stubLogs(listOf(log("Agent-1-Technical", "BUY", t0)))
        runBlocking {
            Mockito.`when`(strategyRepository.findByCycleId("cycle-1")).thenReturn(emptyList())
        }
        stubEmptyPositions()

        val chain = runBlocking { service().buildChain("cycle-1") }

        assertFalse(chain.complete)
        assertEquals(listOf("strategy", "advisor", "strategist"), chain.missing)
        assertEquals(
            1.0,
            meterRegistry.counter("lineage.chain.incomplete", "missing", "strategy,advisor,strategist").count(),
        )
        assertEquals(1.0, meterRegistry.counter("lineage.chain.llm_visible", "visible", "false").count())
    }

    @Test
    fun `buildChain resolves trade events by position aggregate ids`() {
        val t0 = LocalDateTime.of(2026, 9, 10, 10, 0)
        stubLogs(listOf(strategistLog(t0), advisorLog(t0.plusSeconds(1))))
        runBlocking {
            Mockito.`when`(strategyRepository.findByCycleId("cycle-1")).thenReturn(listOf(strategy()))
            Mockito.`when`(positionRepository.findByCycleId("cycle-1")).thenReturn(listOf(position()))
        }
        val expectedAggregate = positionAggregateId
        runBlocking {
            Mockito
                .`when`(tradeEventRepository.findByAggregateIds(listOf(expectedAggregate)))
                .thenReturn(listOf(tradeEvent(expectedAggregate)))
        }

        val chain = runBlocking { service().buildChain("cycle-1") }

        assertEquals(1, chain.tradeEvents.size)
        assertEquals(expectedAggregate, chain.tradeEvents[0].aggregateId)
        runBlocking {
            Mockito
                .verify(tradeEventRepository)
                .findByAggregateIds(listOf(expectedAggregate))
        }
    }

    @Test
    fun `buildChain includes raw traces from object storage when requested`() {
        val t0 = LocalDateTime.of(2026, 9, 10, 10, 0)
        stubLogs(listOf(strategistLog(t0), advisorLog(t0.plusSeconds(1))))
        runBlocking {
            Mockito.`when`(strategyRepository.findByCycleId("cycle-1")).thenReturn(listOf(strategy()))
        }
        stubEmptyPositions()
        val traces =
            listOf(
                LlmTrace(
                    traceId = "cycle-1",
                    ticker = "CNYRUBF",
                    agent = "technical",
                    provider = "KIMI",
                    model = "kimi",
                    fingerprint = "f1",
                    systemPrompt = "sys",
                    userPrompt = "user",
                    responseContent = "{}",
                    tokensUsed = 10,
                    latencyMs = 100,
                    isFallback = false,
                    fromCache = false,
                    createdAt = Instant.now(),
                ),
            )
        runBlocking {
            Mockito
                .`when`(traceQueryService.listByCycleId("cycle-1", 50))
                .thenReturn(traces)
        }

        val chain = runBlocking { service().buildChain("cycle-1", includeTraces = true) }

        assertEquals(1, chain.traces.size)
        assertEquals("technical", chain.traces[0].agent)
        runBlocking {
            Mockito.verify(traceQueryService).listByCycleId("cycle-1", 50)
        }
    }

    @Test
    fun `buildChain rejects blank cycleId`() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { service().buildChain("   ") }
        }
    }
}
