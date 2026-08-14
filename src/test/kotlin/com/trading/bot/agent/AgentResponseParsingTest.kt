package com.trading.bot.agent

import com.trading.bot.config.LlmConfig
import com.trading.bot.config.TraceStorageConfig
import com.trading.bot.infrastructure.llm.Guardrails
import com.trading.bot.infrastructure.llm.LlmResponse
import com.trading.bot.infrastructure.llm.PromptRegistry
import com.trading.bot.infrastructure.llm.PromptTemplate
import com.trading.bot.infrastructure.llm.ResilientLlmClient
import com.trading.bot.infrastructure.llm.SemanticCache
import com.trading.bot.model.StrategyAction
import com.trading.bot.model.dto.FundamentalReport
import com.trading.bot.model.dto.MarketSnapshot
import com.trading.bot.model.dto.TechnicalReport
import com.trading.bot.model.dto.TradeStats
import com.trading.bot.model.entity.Candle
import com.trading.bot.repository.AgentLogRepository
import com.trading.bot.repository.StrategyAdjustmentRepository
import com.trading.bot.service.MacroContextService
import com.trading.bot.service.RedisCacheService
import com.trading.bot.service.TradeAnalysisService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * Regression-тесты парсинга LLM-ответов во всех агентах (roadmap 13.3.1).
 *
 * Покрывают деградированные ветки, которые живут только внутри приватных
 * parse-методов агентов: битый JSON, невалидный action/conclusion, выход
 * signalStrength за границы, fenced-блоки ```json, fallback-ответы и метрики
 * parse.error.
 *
 * LLM-клиент заменяется на [StubLlmClient] — реальную реализацию интерфейса
 * ResilientLlmClient.complete, потому что matcher-стабы suspend-методов не
 * совпадают в используемой версии mockito-kotlin (см. AgentBacktestSignalGeneratorTest).
 */
class AgentResponseParsingTest {
    private val objectMapper = jacksonObjectMapper()

    private class StubLlmClient(
        responses: List<LlmResponse>,
    ) : ResilientLlmClient(
            llmConfig = LlmConfig(),
            semanticCache = mock(),
            objectMapper = jacksonObjectMapper(),
            meterRegistry = SimpleMeterRegistry(),
            circuitBreakerRegistry = mock(),
            rateLimiterRegistry = mock(),
            retryRegistry = mock(),
            settingsService = mock(),
            traceStorage = mock(),
            traceStorageConfig = TraceStorageConfig(),
        ) {
        private val queue = ArrayDeque(responses)

        override suspend fun complete(
            agent: String,
            ticker: String,
            prompt: PromptTemplate,
            variables: Map<String, Any>,
            fingerprint: String?,
            temperature: Double,
            cacheNamespace: String?,
        ): LlmResponse = queue.removeFirst()
    }

    private val snapshot =
        MarketSnapshot(
            ticker = "SBER",
            currentPrice = BigDecimal("100"),
            volume = 1000L,
        )

    private fun candles(
        n: Int,
        price: BigDecimal = BigDecimal("100"),
    ): List<Candle> =
        (0 until n).map { i ->
            Candle(
                ticker = "SBER",
                timeframe = "MINUTE_10",
                openPrice = price,
                highPrice = price,
                lowPrice = price,
                closePrice = price,
                volume = 1000L,
                time = LocalDateTime.of(2026, 1, 5, 10, 0).plusMinutes(10L * i),
            )
        }

    private fun passthrough(signal: Guardrails.Signal) =
        Guardrails.GuardedSignal(signal, overridden = false, overrideReason = null, appliedRules = emptyList())

    // ===== TechnicalAnalysisAgent =====

    private val techMeter = SimpleMeterRegistry()

    private fun techAgent(llm: ResilientLlmClient): TechnicalAnalysisAgent =
        TechnicalAnalysisAgent(llm, mock(), mock(), mock(), techMeter, objectMapper)

    @Test
    fun `technical agent parses bullish json into enhanced report`() {
        val llm = StubLlmClient(listOf(LlmResponse(content = """{"conclusion":"BEARISH","signalStrength":0.9,"reasoning":"oversold"}""")))

        val report = runBlocking { techAgent(llm).analyze("SBER", candles(30), snapshot, "c1") }

        assertEquals("BEARISH", report.conclusion)
        assertEquals(0.9, report.signalStrength)
        assertEquals("oversold", report.reasoning)
        assertEquals(
            1.0,
            techMeter
                .find("agent.technical.decision")
                .tag("action", "BEARISH")
                .counter()!!
                .count(),
        )
    }

    @Test
    fun `technical agent coerces signalStrength into zero to one`() {
        val llm = StubLlmClient(listOf(LlmResponse(content = """{"conclusion":"BULLISH","signalStrength":5.0}""")))

        val report = runBlocking { techAgent(llm).analyze("SBER", candles(30), snapshot, "c1") }

        assertEquals(1.0, report.signalStrength)
    }

    @Test
    fun `technical agent sanitizes unknown conclusion to neutral`() {
        val llm = StubLlmClient(listOf(LlmResponse(content = """{"conclusion":"WEIRD","signalStrength":0.8}""")))

        val report = runBlocking { techAgent(llm).analyze("SBER", candles(30), snapshot, "c1") }

        assertEquals("NEUTRAL", report.conclusion)
    }

    @Test
    fun `technical agent falls back to baseline on malformed json`() {
        val llm = StubLlmClient(listOf(LlmResponse(content = "not-json{")))

        val report = runBlocking { techAgent(llm).analyze("SBER", candles(30), snapshot, "c1") }

        // Константные свечи дают детерминированный baseline (RSI=100 -> BEARISH)
        assertEquals("BEARISH", report.conclusion)
        assertEquals(0.55, report.signalStrength)
    }

    @Test
    fun `technical agent returns baseline when llm unavailable`() {
        val llm = StubLlmClient(listOf(LlmResponse.fallback("NO_API_KEY")))

        val report = runBlocking { techAgent(llm).analyze("SBER", candles(30), snapshot, "c1") }

        assertEquals("BEARISH", report.conclusion)
        assertEquals(0.55, report.signalStrength)
        assertTrue(report.reasoning.startsWith("RSI="))
    }

    @Test
    fun `technical agent returns insufficient data without llm call`() {
        val llm = mock<ResilientLlmClient>()

        val report = runBlocking { techAgent(llm).analyze("SBER", candles(10), snapshot, "c1") }

        assertEquals("INSUFFICIENT_DATA", report.conclusion)
        runBlocking {
            verify(llm, never()).complete(any(), any(), any(), any(), any(), any(), any())
        }
    }

    // ===== FundamentalAnalysisAgent =====

    private val fundMacro: MacroContextService = mock()
    private val fundMeter = SimpleMeterRegistry()

    private fun fundAgent(llm: ResilientLlmClient): FundamentalAnalysisAgent =
        FundamentalAnalysisAgent(llm, mock(), fundMacro, mock(), mock(), fundMeter, objectMapper)

    private suspend fun stubMacro() {
        whenever(
            fundMacro.fetch(),
        ).thenReturn(MacroContextService.MacroContext(BigDecimal("16.0"), BigDecimal("75.0"), BigDecimal("90.0")))
    }

    @Test
    fun `fundamental agent parses bullish json`() {
        val llm = StubLlmClient(listOf(LlmResponse(content = """{"conclusion":"BULLISH","signalStrength":0.75,"reasoning":"rate cut"}""")))

        runBlocking { stubMacro() }

        val report = runBlocking { fundAgent(llm).analyze("SBER", "c1") }

        assertEquals("BULLISH", report.conclusion)
        assertEquals(0.75, report.signalStrength)
        assertEquals("rate cut", report.reasoning)
    }

    @Test
    fun `fundamental agent sanitizes unknown conclusion`() {
        val llm = StubLlmClient(listOf(LlmResponse(content = """{"conclusion":"moon","signalStrength":0.5}""")))

        runBlocking { stubMacro() }

        val report = runBlocking { fundAgent(llm).analyze("SBER", "c1") }

        assertEquals("NEUTRAL", report.conclusion)
    }

    @Test
    fun `fundamental agent falls back to neutral when llm unavailable`() {
        val llm = StubLlmClient(listOf(LlmResponse.fallback("TIMEOUT")))

        runBlocking { stubMacro() }

        val report = runBlocking { fundAgent(llm).analyze("SBER", "c1") }

        assertEquals("NEUTRAL", report.conclusion)
        assertEquals(0.0, report.signalStrength)
    }

    @Test
    fun `fundamental agent falls back on malformed json`() {
        val llm = StubLlmClient(listOf(LlmResponse(content = "{broken")))

        runBlocking { stubMacro() }

        val report = runBlocking { fundAgent(llm).analyze("SBER", "c1") }

        assertEquals("NEUTRAL", report.conclusion)
        assertEquals("Parse error", report.reasoning)
    }

    // ===== StrategyAgent =====

    private val stratMeter = SimpleMeterRegistry()

    private fun stratAgent(
        llm: ResilientLlmClient,
        guardrails: Guardrails = mock(),
    ): StrategyAgent = StrategyAgent(llm, mock(), mock(), guardrails, mock(), stratMeter, objectMapper)

    private val techReport =
        TechnicalReport(
            trend = "UP",
            rsi = 55.0,
            atr = 1.0,
            conclusion = "BULLISH",
            signalStrength = 0.8,
            reasoning = "uptrend",
        )

    private val fundReport = FundamentalReport(conclusion = "NEUTRAL", signalStrength = 0.5, reasoning = "macro ok")

    @Test
    fun `strategy agent holds without llm when tech data insufficient`() {
        val llm = mock<ResilientLlmClient>()

        val draft =
            runBlocking {
                stratAgent(llm).formulate(
                    "SBER",
                    techReport.copy(conclusion = "INSUFFICIENT_DATA"),
                    fundReport,
                    snapshot,
                    "c1",
                )
            }

        assertEquals(StrategyAction.HOLD, draft.action)
        assertEquals(0.0, draft.signalStrength)
        runBlocking {
            verify(llm, never()).complete(any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `strategy agent holds without llm when tech signalStrength low`() {
        val llm = mock<ResilientLlmClient>()

        val draft =
            runBlocking {
                stratAgent(llm).formulate(
                    "SBER",
                    techReport.copy(signalStrength = 0.3),
                    fundReport,
                    snapshot,
                    "c1",
                )
            }

        assertEquals(StrategyAction.HOLD, draft.action)
    }

    @Test
    fun `strategy agent parses buy draft and coerces signalStrength`() {
        val llm =
            StubLlmClient(listOf(LlmResponse(content = """{"action":"BUY","targetPrice":"102.5","signalStrength":1.7,"reasoning":"go"}""")))
        val guardrails: Guardrails = mock()
        whenever(guardrails.apply(any(), any(), any(), any())).thenAnswer {
            passthrough(it.getArgument(0))
        }

        val draft = runBlocking { stratAgent(llm, guardrails).formulate("SBER", techReport, fundReport, snapshot, "c1") }

        assertEquals(StrategyAction.BUY, draft.action)
        assertEquals(BigDecimal("102.5"), draft.targetPrice)
        assertEquals(1.0, draft.signalStrength)
    }

    @Test
    fun `strategy agent falls back to snapshot price when target missing`() {
        val llm = StubLlmClient(listOf(LlmResponse(content = """{"action":"BUY","signalStrength":0.7}""")))
        val guardrails: Guardrails = mock()
        whenever(guardrails.apply(any(), any(), any(), any())).thenAnswer {
            passthrough(it.getArgument(0))
        }

        val draft = runBlocking { stratAgent(llm, guardrails).formulate("SBER", techReport, fundReport, snapshot, "c1") }

        assertEquals(BigDecimal("100"), draft.targetPrice)
    }

    @Test
    fun `strategy agent maps unknown action to hold`() {
        val llm = StubLlmClient(listOf(LlmResponse(content = """{"action":"YOLO","signalStrength":0.7}""")))
        val guardrails: Guardrails = mock()
        whenever(guardrails.apply(any(), any(), any(), any())).thenAnswer {
            passthrough(it.getArgument(0))
        }

        val draft = runBlocking { stratAgent(llm, guardrails).formulate("SBER", techReport, fundReport, snapshot, "c1") }

        assertEquals(StrategyAction.HOLD, draft.action)
    }

    @Test
    fun `strategy agent handles malformed json with hold and error metric`() {
        val llm = StubLlmClient(listOf(LlmResponse(content = "definitely-not-json")))

        val draft = runBlocking { stratAgent(llm).formulate("SBER", techReport, fundReport, snapshot, "c1") }

        assertEquals(StrategyAction.HOLD, draft.action)
        assertEquals(
            1.0,
            stratMeter
                .find("strategy.agent.parse.error")
                .tag("ticker", "SBER")
                .counter()!!
                .count(),
        )
    }

    @Test
    fun `strategy agent returns hold when llm unavailable`() {
        val llm = StubLlmClient(listOf(LlmResponse.fallback("NO_API_KEY")))

        val draft = runBlocking { stratAgent(llm).formulate("SBER", techReport, fundReport, snapshot, "c1") }

        assertEquals(StrategyAction.HOLD, draft.action)
    }

    @Test
    fun `strategy agent applies guardrail override on top of parsed draft`() {
        val llm = StubLlmClient(listOf(LlmResponse(content = """{"action":"BUY","targetPrice":"102.5","signalStrength":0.7}""")))
        val guardrails: Guardrails = mock()
        val overridden =
            Guardrails.GuardedSignal(
                signal = Guardrails.Signal(StrategyAction.HOLD, snapshot.currentPrice, 0.0),
                overridden = true,
                overrideReason = "GUARDRAIL: LOW_CONFIDENCE",
                appliedRules = listOf("signalStrength < threshold -> HOLD"),
            )
        whenever(guardrails.apply(any(), any(), any(), any())).thenReturn(overridden)

        val draft =
            runBlocking {
                StrategyAgent(llm, mock(), mock(), guardrails, mock(), stratMeter, objectMapper)
                    .formulate("SBER", techReport, fundReport, snapshot, "c1")
            }

        assertEquals(StrategyAction.HOLD, draft.action)
        assertTrue(draft.reasoning.contains("[GUARDRAIL:") && draft.reasoning.contains("LOW_CONFIDENCE"))
    }

    // ===== ContrarianAgent =====

    private val contrMeter = SimpleMeterRegistry()

    private fun contrAgent(llm: ResilientLlmClient): ContrarianAgent =
        ContrarianAgent(llm, mock(), mock(), mock(), contrMeter, objectMapper)

    private val buyDraft = StrategyAgent.Draft(StrategyAction.BUY, BigDecimal("102"), 0.7, "buy")
    private val holdDraft = StrategyAgent.Draft(StrategyAction.HOLD, BigDecimal("100"), 0.7, "hold")

    @Test
    fun `contrarian agent skips llm for hold draft`() {
        val llm = mock<ResilientLlmClient>()

        val report =
            runBlocking {
                contrAgent(llm).challenge(holdDraft, techReport, fundReport, snapshot, "c1")
            }

        assertEquals(true, report.isValid)
        assertEquals("LOW", report.riskLevel)
        assertEquals(1.0, report.signalStrength)
        runBlocking {
            verify(llm, never()).complete(any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `contrarian agent parses challenge and sanitizes risk level`() {
        val llm =
            StubLlmClient(
                listOf(
                    LlmResponse(
                        content =
                            """{"isValid":false,"riskLevel":"EXTREME","critique":"risky","signalStrength":0.9}""",
                    ),
                ),
            )

        val report = runBlocking { contrAgent(llm).challenge(buyDraft, techReport, fundReport, snapshot, "c1") }

        assertEquals(false, report.isValid)
        assertEquals("LOW", report.riskLevel)
        assertEquals("risky", report.critique)
        assertEquals(0.9, report.signalStrength)
    }

    @Test
    fun `contrarian agent allows trade on parse error`() {
        val llm = StubLlmClient(listOf(LlmResponse(content = "{bad")))

        val report = runBlocking { contrAgent(llm).challenge(buyDraft, techReport, fundReport, snapshot, "c1") }

        assertEquals(true, report.isValid)
        assertEquals("LOW", report.riskLevel)
        assertEquals("Parse error", report.critique)
        assertEquals(0.5, report.signalStrength)
    }

    @Test
    fun `contrarian agent allows trade when llm unavailable`() {
        val llm = StubLlmClient(listOf(LlmResponse.fallback("TIMEOUT")))

        val report = runBlocking { contrAgent(llm).challenge(buyDraft, techReport, fundReport, snapshot, "c1") }

        assertEquals(true, report.isValid)
        assertEquals("LOW", report.riskLevel)
        assertEquals(0.5, report.signalStrength)
    }

    // ===== ArbitratorAgent =====

    private val arbMeter = SimpleMeterRegistry()

    private fun arbAgent(
        llm: ResilientLlmClient,
        guardrails: Guardrails = mock(),
    ): ArbitratorAgent = ArbitratorAgent(llm, mock(), guardrails, mock(), mock(), arbMeter, objectMapper)

    private val lowRiskChallenge =
        ContrarianAgent.ChallengeReport(isValid = true, riskLevel = "LOW", critique = "ok", signalStrength = 0.8)

    @Test
    fun `arbitrator blocks on critical challenge without llm`() {
        val llm = mock<ResilientLlmClient>()
        val critical = ContrarianAgent.ChallengeReport(isValid = false, riskLevel = "CRITICAL", critique = "danger", signalStrength = 1.0)

        val decision = runBlocking { arbAgent(llm).adjudicate(buyDraft, critical, techReport, fundReport, snapshot, "c1") }

        assertEquals(StrategyAction.HOLD, decision.action)
        assertEquals("DETERMINISTIC: CRITICAL_CHALLENGE", decision.overrideReason)
        runBlocking {
            verify(llm, never()).complete(any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `arbitrator holds on strategist hold draft`() {
        val llm = mock<ResilientLlmClient>()

        val decision = runBlocking { arbAgent(llm).adjudicate(holdDraft, lowRiskChallenge, techReport, fundReport, snapshot, "c1") }

        assertEquals(StrategyAction.HOLD, decision.action)
    }

    @Test
    fun `arbitrator holds on low draft signalStrength`() {
        val llm = mock<ResilientLlmClient>()
        val weakDraft = buyDraft.copy(signalStrength = 0.4)

        val decision = runBlocking { arbAgent(llm).adjudicate(weakDraft, lowRiskChallenge, techReport, fundReport, snapshot, "c1") }

        assertEquals(StrategyAction.HOLD, decision.action)
        assertEquals("DETERMINISTIC: LOW_DRAFT_CONFIDENCE", decision.overrideReason)
    }

    @Test
    fun `arbitrator parses fenced json decision`() {
        val fenced =
            """```json
{"action":"BUY","targetPrice":"102.5","signalStrength":0.8,"reasoning":"go long"}
```"""
        val llm = StubLlmClient(listOf(LlmResponse(content = fenced)))
        val guardrails: Guardrails = mock()
        whenever(guardrails.apply(any(), any(), any(), any())).thenAnswer {
            passthrough(it.getArgument(0))
        }

        val decision =
            runBlocking {
                ArbitratorAgent(llm, mock(), guardrails, mock(), mock(), arbMeter, objectMapper)
                    .adjudicate(buyDraft, lowRiskChallenge, techReport, fundReport, snapshot, "c1")
            }

        assertEquals(StrategyAction.BUY, decision.action)
        assertEquals(BigDecimal("102.5"), decision.targetPrice)
        assertEquals(0.8, decision.signalStrength)
    }

    @Test
    fun `arbitrator falls back to draft target when target missing`() {
        val llm = StubLlmClient(listOf(LlmResponse(content = """{"action":"BUY","signalStrength":0.8}""")))
        val guardrails: Guardrails = mock()
        whenever(guardrails.apply(any(), any(), any(), any())).thenAnswer {
            passthrough(it.getArgument(0))
        }

        val decision =
            runBlocking {
                ArbitratorAgent(llm, mock(), guardrails, mock(), mock(), arbMeter, objectMapper)
                    .adjudicate(buyDraft, lowRiskChallenge, techReport, fundReport, snapshot, "c1")
            }

        assertEquals(BigDecimal("102"), decision.targetPrice)
    }

    @Test
    fun `arbitrator maps unknown action to hold`() {
        val llm = StubLlmClient(listOf(LlmResponse(content = """{"action":"MOON","signalStrength":0.9}""")))
        val guardrails: Guardrails = mock()
        whenever(guardrails.apply(any(), any(), any(), any())).thenAnswer {
            passthrough(it.getArgument(0))
        }

        val decision =
            runBlocking {
                ArbitratorAgent(llm, mock(), guardrails, mock(), mock(), arbMeter, objectMapper)
                    .adjudicate(buyDraft, lowRiskChallenge, techReport, fundReport, snapshot, "c1")
            }

        assertEquals(StrategyAction.HOLD, decision.action)
    }

    @Test
    fun `arbitrator holds on malformed json and records error metric`() {
        val llm = StubLlmClient(listOf(LlmResponse(content = "!!!not-json!!!")))
        val guardrails: Guardrails = mock()
        whenever(guardrails.apply(any(), any(), any(), any())).thenAnswer {
            passthrough(it.getArgument(0))
        }

        val decision =
            runBlocking { arbAgent(llm, guardrails).adjudicate(buyDraft, lowRiskChallenge, techReport, fundReport, snapshot, "c1") }

        assertEquals(StrategyAction.HOLD, decision.action)
        assertEquals(1.0, arbMeter.find("agent.arbitrator.parse.error").counter()!!.count())
    }

    @Test
    fun `arbitrator applies post-processing guardrail`() {
        val llm = StubLlmClient(listOf(LlmResponse(content = """{"action":"BUY","targetPrice":"150.0","signalStrength":0.8}""")))
        val guardrails: Guardrails = mock()
        val overridden =
            Guardrails.GuardedSignal(
                signal = Guardrails.Signal(StrategyAction.HOLD, snapshot.currentPrice, 0.0),
                overridden = true,
                overrideReason = "GUARDRAIL: PRICE_DEVIATION",
                appliedRules = listOf("price deviation -> adjust"),
            )
        whenever(guardrails.apply(any(), any(), any(), any())).thenReturn(overridden)

        val decision =
            runBlocking {
                ArbitratorAgent(llm, mock(), guardrails, mock(), mock(), arbMeter, objectMapper)
                    .adjudicate(buyDraft, lowRiskChallenge, techReport, fundReport, snapshot, "c1")
            }

        assertEquals(StrategyAction.HOLD, decision.action)
        assertTrue(decision.reasoning.contains("[GUARDRAIL:") && decision.reasoning.contains("PRICE_DEVIATION"))
    }

    // ===== PerformanceFeedbackAgent =====

    private val perfAnalysis: TradeAnalysisService = mock()
    private val perfRedis: RedisCacheService = mock()
    private val perfMeter = SimpleMeterRegistry()

    private fun perfAgent(llm: ResilientLlmClient): PerformanceFeedbackAgent =
        PerformanceFeedbackAgent(llm, perfAnalysis, mock(), mock(), perfRedis, mock(), perfMeter, objectMapper)

    private fun stats(
        totalTrades: Int = 10,
        winRate: Double = 0.5,
        slHitRate: Double = 0.5,
        maxConsecutiveLosses: Int = 1,
    ): TradeStats =
        TradeStats(
            ticker = "SBER",
            totalTrades = totalTrades,
            winningTrades = (totalTrades * winRate).toInt(),
            losingTrades = totalTrades - (totalTrades * winRate).toInt(),
            winRate = winRate,
            avgWin = BigDecimal("100"),
            avgLoss = BigDecimal("80"),
            profitFactor = 1.5,
            maxConsecutiveLosses = maxConsecutiveLosses,
            avgHoldTimeMinutes = 300,
            slHitRate = slHitRate,
            tpHitRate = 0.3,
            strategyCloseRate = 0.2,
            bestEntryHour = 10,
            worstEntryHour = 18,
            blindSpots = emptyList(),
        )

    @Test
    fun `rule based feedback pauses after three consecutive losses`() {
        val feedback = perfAgent(mock()).ruleBasedFeedback("SBER", stats(maxConsecutiveLosses = 3))

        assertEquals(true, feedback.shouldPauseTrading)
        assertTrue(feedback.rawJson.contains("\"shouldPauseTrading\":true"))
    }

    @Test
    fun `rule based feedback raises confidence adjustment on low win rate`() {
        val feedback = perfAgent(mock()).ruleBasedFeedback("SBER", stats(winRate = 0.30))

        assertEquals(0.15, feedback.confidenceAdjustment)
    }

    @Test
    fun `rule based feedback widens stop loss on high sl hit rate`() {
        val feedback = perfAgent(mock()).ruleBasedFeedback("SBER", stats(slHitRate = 0.70))

        assertEquals(0.20, feedback.slAdjustmentPercent)
    }

    @Test
    fun `rule based feedback is neutral for healthy stats`() {
        val feedback = perfAgent(mock()).ruleBasedFeedback("SBER", stats())

        assertEquals(0.0, feedback.confidenceAdjustment)
        assertEquals(0.0, feedback.slAdjustmentPercent)
        assertEquals(0.0, feedback.tpAdjustmentPercent)
        assertEquals(false, feedback.shouldPauseTrading)
    }

    @Test
    fun `feedback parse clamps hallucinated adjustments`() {
        runBlocking {
            whenever(perfAnalysis.analyzeLastNDays(14)).thenReturn(mapOf("SBER" to stats()))
        }
        whenever(perfRedis.getFeedback(any(), any())).thenReturn(
            """{"confidenceAdjustment":0.99,"slAdjustmentPercent":-5.0,"tpAdjustmentPercent":3.0,
                |"contextPrompt":"tighter","techAgentNote":"t","shouldPauseTrading":true}
            """.trimMargin(),
        )

        val feedback = runBlocking { perfAgent(mock()).generateFeedback("SBER") }

        assertEquals(0.20, feedback.confidenceAdjustment)
        assertEquals(-0.30, feedback.slAdjustmentPercent)
        assertEquals(0.30, feedback.tpAdjustmentPercent)
        assertEquals(true, feedback.shouldPauseTrading)
    }

    @Test
    fun `feedback parse falls back to rules on malformed cached json`() {
        runBlocking {
            whenever(perfAnalysis.analyzeLastNDays(14)).thenReturn(mapOf("SBER" to stats()))
        }
        whenever(perfRedis.getFeedback(any(), any())).thenReturn("not-json{")

        val feedback = runBlocking { perfAgent(mock()).generateFeedback("SBER") }

        assertEquals(0.0, feedback.confidenceAdjustment)
        assertEquals(0.0, feedback.slAdjustmentPercent)
        assertEquals(false, feedback.shouldPauseTrading)
        assertEquals(true, feedback.agentSpecificNotes["reason"]?.startsWith("RULE_BASED"))
    }
}
