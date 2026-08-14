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
import com.trading.bot.model.entity.AgentLog
import com.trading.bot.repository.AgentLogRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigDecimal
import java.util.ArrayDeque

/**
 * Покрытие StrategyAgent (roadmap 13.17): guardrail «недостаточно данных → HOLD без LLM»,
 * парсинг LLM-ответа (клампинг signalStrength, fallback-экшены), fallback при недоступности
 * LLM, постобработка Guardrails (LOW_CONFIDENCE / PRICE_DEVIATION), логирование в agent_logs
 * и метрики agent.strategy.decision.
 *
 * LLM-клиент заменяется на [StubLlmClient] — реальную реализацию интерфейса
 * ResilientLlmClient.complete, потому что matcher-стабы suspend-методов не
 * совпадают в используемой версии mockito-kotlin (см. AgentResponseParsingTest).
 */
class StrategyAgentTest {
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

    /** Записывает сохранённые AgentLog без реальной БД и без матчер-стабов suspend. */
    private class RecordingLogRepo : AgentLogRepository(mock()) {
        val saved = mutableListOf<AgentLog>()

        override suspend fun save(log: AgentLog): AgentLog {
            saved += log
            return log
        }
    }

    private val snapshot = MarketSnapshot(ticker = "SBER", currentPrice = BigDecimal("100"))
    private val template =
        PromptTemplate(
            name = "strategy",
            version = "default",
            system = "system prompt",
            userTemplate = "user {{ticker}}",
        )
    private val promptRegistry: PromptRegistry =
        mock<PromptRegistry>().apply {
            whenever(getTemplate("strategy", "default")).thenReturn(template)
        }
    private val logRepo = RecordingLogRepo()

    private fun tech(
        conclusion: String = "BULLISH",
        signalStrength: Double = 0.8,
    ): TechnicalReport =
        TechnicalReport(
            trend = "UP",
            rsi = 60.0,
            atr = 1.0,
            macd = 0.5,
            conclusion = conclusion,
            signalStrength = signalStrength,
            reasoning = "tech reasoning",
        )

    private fun fund(): FundamentalReport =
        FundamentalReport(
            conclusion = "POSITIVE",
            signalStrength = 0.6,
            reasoning = "fund reasoning",
        )

    private fun agent(
        llm: ResilientLlmClient,
        meter: SimpleMeterRegistry,
    ): StrategyAgent =
        StrategyAgent(
            llmClient = llm,
            promptRegistry = promptRegistry,
            semanticCache = mock<SemanticCache>(),
            guardrails =
                Guardrails(
                    LlmConfig().apply { guardrailsMaxPriceDeviationPercent = 3.0 },
                    meter,
                ),
            agentLogRepository = logRepo,
            meterRegistry = meter,
            objectMapper = jacksonObjectMapper(),
        )

    @Test
    fun `holds without llm call when technical conclusion is insufficient`() {
        val meter = SimpleMeterRegistry()
        val draft =
            runBlocking {
                agent(StubLlmClient(emptyList()), meter)
                    .formulate("SBER", tech(conclusion = "INSUFFICIENT_DATA"), fund(), snapshot, "c1")
            }

        assertEquals(StrategyAction.HOLD, draft.action)
        assertEquals(BigDecimal("100"), draft.targetPrice)
        assertEquals(0.0, draft.signalStrength)
        assertTrue(draft.reasoning.contains("Insufficient technical data"))
        assertEquals(1, logRepo.saved.size)
        assertEquals("GUARDRAIL: INSUFFICIENT_TECH_DATA", logRepo.saved.single().overrideReason)
        assertEquals("{}", logRepo.saved.single().rawOutput)
        assertEquals("Agent-3-Strategist", logRepo.saved.single().agentName)
        assertEquals(1.0, meter.counter("agent.strategy.decision", "action", "HOLD").count())
    }

    @Test
    fun `holds without llm call when technical signal strength is below threshold`() {
        val draft =
            runBlocking {
                agent(StubLlmClient(emptyList()), SimpleMeterRegistry())
                    .formulate("SBER", tech(signalStrength = 0.4), fund(), snapshot, "c1")
            }

        assertEquals(StrategyAction.HOLD, draft.action)
        assertTrue(draft.reasoning.contains("Insufficient technical data"))
    }

    @Test
    fun `parses buy draft from llm response`() {
        val meter = SimpleMeterRegistry()
        val llm =
            StubLlmClient(
                listOf(
                    LlmResponse(
                        content = """{"action":"BUY","targetPrice":"101.5","signalStrength":0.85,"reasoning":"momentum"}""",
                    ),
                ),
            )

        val draft = runBlocking { agent(llm, meter).formulate("SBER", tech(), fund(), snapshot, "c1") }

        assertEquals(StrategyAction.BUY, draft.action)
        assertEquals(BigDecimal("101.5"), draft.targetPrice)
        assertEquals(0.85, draft.signalStrength)
        assertEquals("momentum", draft.reasoning)
        assertEquals(1.0, meter.counter("agent.strategy.decision", "action", "BUY").count())
        val log = logRepo.saved.single()
        assertEquals("BUY", log.action)
        assertTrue(log.rawOutput!!.contains("\"action\":\"BUY\""))
    }

    @Test
    fun `coerces out of range signal strength and defaults unknown action to hold`() {
        val llm =
            StubLlmClient(
                listOf(
                    LlmResponse(
                        content = """{"action":"MOON","targetPrice":"bad","signalStrength":3.0}""",
                    ),
                ),
            )

        val draft = runBlocking { agent(llm, SimpleMeterRegistry()).formulate("SBER", tech(), fund(), snapshot, "c1") }

        assertEquals(StrategyAction.HOLD, draft.action)
        assertEquals(BigDecimal("100"), draft.targetPrice)
        assertEquals(1.0, draft.signalStrength)
    }

    @Test
    fun `holds when llm unavailable via fallback response`() {
        val llm =
            StubLlmClient(
                listOf(
                    LlmResponse(
                        content = """{"conclusion":"NEUTRAL","signalStrength":0.0}""",
                        isFallback = true,
                        storageKey = "s3://key-1",
                    ),
                ),
            )

        val draft = runBlocking { agent(llm, SimpleMeterRegistry()).formulate("SBER", tech(), fund(), snapshot, "c1") }

        assertEquals(StrategyAction.HOLD, draft.action)
        assertEquals("LLM unavailable", draft.reasoning)
        val log = logRepo.saved.single()
        assertEquals("s3://key-1", log.storageKey)
        assertEquals(false, log.isCached)
    }

    @Test
    fun `holds on unparsable llm content and records parse error metric`() {
        val meter = SimpleMeterRegistry()
        val llm = StubLlmClient(listOf(LlmResponse(content = "not-json{")))

        val draft = runBlocking { agent(llm, meter).formulate("SBER", tech(), fund(), snapshot, "c1") }

        assertEquals(StrategyAction.HOLD, draft.action)
        assertTrue(draft.reasoning.contains("Parse error"))
        assertEquals(1.0, meter.counter("strategy.agent.parse.error", "ticker", "SBER").count())
        assertEquals("not-json{", logRepo.saved.single().rawOutput)
    }

    @Test
    fun `low confidence buy is overridden to hold by guardrail`() {
        val meter = SimpleMeterRegistry()
        val llm =
            StubLlmClient(
                listOf(
                    LlmResponse(
                        content = """{"action":"BUY","targetPrice":"100","signalStrength":0.4,"reasoning":"weak"}""",
                    ),
                ),
            )

        val draft = runBlocking { agent(llm, meter).formulate("SBER", tech(), fund(), snapshot, "c1") }

        assertEquals(StrategyAction.HOLD, draft.action)
        assertTrue(draft.reasoning.contains("GUARDRAIL: LOW_CONFIDENCE"), "reasoning='${draft.reasoning}'")
        assertEquals("GUARDRAIL: LOW_CONFIDENCE", logRepo.saved.single().overrideReason)
        assertEquals(1.0, meter.counter("agent.strategy.decision", "action", "HOLD").count())
    }

    @Test
    fun `target price far from market is corrected by guardrail`() {
        val llm =
            StubLlmClient(
                listOf(
                    LlmResponse(
                        content = """{"action":"BUY","targetPrice":"110","signalStrength":0.8,"reasoning":"gap"}""",
                    ),
                ),
            )

        val draft = runBlocking { agent(llm, SimpleMeterRegistry()).formulate("SBER", tech(), fund(), snapshot, "c1") }

        assertEquals(StrategyAction.BUY, draft.action)
        assertEquals(BigDecimal("100"), draft.targetPrice)
        assertTrue(draft.reasoning.contains("GUARDRAIL: PRICE_DEVIATION"), "reasoning='${draft.reasoning}'")
        assertEquals("GUARDRAIL: PRICE_DEVIATION", logRepo.saved.single().overrideReason)
    }

    @Test
    fun `high confidence draft passes through unchanged`() {
        val llm =
            StubLlmClient(
                listOf(
                    LlmResponse(
                        content = """{"action":"BUY","targetPrice":"100","signalStrength":0.8,"reasoning":"ok"}""",
                    ),
                ),
            )

        val draft = runBlocking { agent(llm, SimpleMeterRegistry()).formulate("SBER", tech(), fund(), snapshot, "c1") }

        assertEquals(StrategyAction.BUY, draft.action)
        assertEquals(BigDecimal("100"), draft.targetPrice)
        assertEquals(0.8, draft.signalStrength)
        assertEquals("ok", draft.reasoning)
        assertEquals(null, logRepo.saved.single().overrideReason)
    }

    @Test
    fun `records cache and token metadata in the agent log`() {
        val llm =
            StubLlmClient(
                listOf(
                    LlmResponse(
                        content = """{"action":"SELL","targetPrice":"99","signalStrength":0.7,"reasoning":"top"}""",
                        isFallback = false,
                        fromCache = true,
                        tokensUsed = 42,
                        storageKey = "key-cached",
                    ),
                ),
            )

        val draft = runBlocking { agent(llm, SimpleMeterRegistry()).formulate("SBER", tech(), fund(), snapshot, "c1") }

        assertEquals(StrategyAction.SELL, draft.action)
        val log = logRepo.saved.single()
        assertEquals(true, log.isCached)
        assertEquals(42, log.tokensUsed)
        assertEquals("key-cached", log.storageKey)
        assertEquals("c1", log.cycleId)
        assertEquals("SBER", log.ticker)
    }
}
