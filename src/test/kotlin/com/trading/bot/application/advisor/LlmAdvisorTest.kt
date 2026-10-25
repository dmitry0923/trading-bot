package com.trading.bot.application.advisor

import com.trading.bot.config.TradingConfig
import com.trading.bot.domain.advisor.AdvisorRiskLevel
import com.trading.bot.domain.advisor.AdvisorVerdictType
import com.trading.bot.domain.strategy.StrategyContext
import com.trading.bot.domain.strategy.StrategyDecision
import com.trading.bot.infrastructure.llm.LlmResponse
import com.trading.bot.infrastructure.llm.PromptRegistry
import com.trading.bot.infrastructure.llm.PromptTemplate
import com.trading.bot.infrastructure.llm.ResilientLlmClient
import com.trading.bot.infrastructure.llm.SemanticCache
import com.trading.bot.model.StrategyAction
import com.trading.bot.model.dto.MarketSnapshot
import com.trading.bot.model.entity.AgentLog
import com.trading.bot.repository.AgentLogRepository
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.invocation.InvocationOnMock
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigDecimal
import java.time.Instant

/**
 * Unit-тесты [LlmAdvisor] (advisory layer, C-001): fail-open при недоступности
 * LLM и parse error, guardrail на поправку уверенности, понижение VETO
 * без CRITICAL-риска и блокировка входа только при CRITICAL.
 *
 * `ResilientLlmClient` замокан через custom-answer (не через матчеры): suspend-метод
 * с default-аргументами нестабильно стабится `Mockito.when(...)` — answer по имени
 * метода детерминирован и не зависит от набора матчеров.
 *
 * Ветка `withTimeout(advisorBudgetMs)` не покрыта: `withTimeout` не прерывает
 * non-cooperative ответ мока (kotlinx-таймаут кооперативен), а suspendable-answer
 * требует matcher-стабизации, которая для этого метода не срабатывает.
 */
class LlmAdvisorTest {
    /** Поведение `complete` для текущего теста (по умолчанию — «нет ответа», null). */
    private var llmAnswer: (InvocationOnMock) -> Any? = { null }

    private val llmClient: ResilientLlmClient =
        Mockito.mock(ResilientLlmClient::class.java) { invocation ->
            if (invocation.method.name == "complete") {
                llmAnswer(invocation)
            } else {
                Mockito.RETURNS_DEFAULTS.answer(invocation)
            }
        }

    private val promptRegistry = Mockito.mock(PromptRegistry::class.java)
    private val semanticCache = Mockito.mock(SemanticCache::class.java)
    private val agentLogRepository: AgentLogRepository =
        Mockito.mock(AgentLogRepository::class.java) { invocation ->
            if (invocation.method.name == "save") {
                invocation.getArgument<AgentLog>(0)
            } else {
                Mockito.RETURNS_DEFAULTS.answer(invocation)
            }
        }
    private val meterRegistry = SimpleMeterRegistry()
    private val objectMapper = jacksonObjectMapper()

    private val template =
        PromptTemplate(
            name = "advisor",
            version = PromptRegistry.DEFAULT_VERSION,
            system = "system",
            userTemplate = "user {{ticker}}",
        )

    private val advisor =
        advisorWith(llmClient = llmClient, budgetMs = 1_000)

    private val context =
        StrategyContext(
            ticker = "SBER",
            snapshot = MarketSnapshot(ticker = "SBER", currentPrice = BigDecimal("100"), timestamp = Instant.now()),
            candles = emptyList(),
            indicators = null,
            cycleId = "cycle-1",
        )

    private val buyDecision =
        StrategyDecision(
            action = StrategyAction.BUY,
            targetPrice = BigDecimal("100"),
            signalStrength = 0.7,
            reasoning = "test signal",
        )

    @BeforeEach
    fun stubCollaborators() {
        Mockito.`when`(promptRegistry.getTemplate("advisor", PromptRegistry.DEFAULT_VERSION)).thenReturn(template)
    }

    @Test
    fun `hold decision skips the llm call entirely`() {
        val verdict =
            runBlocking {
                advisor.advise(context, StrategyDecision.hold(BigDecimal("100"), "no entry"), 0.6)
            }

        assertEquals(AdvisorVerdictType.NEUTRAL, verdict.verdict)
        assertFalse(verdict.blocksEntry)
        Mockito.verifyNoInteractions(llmClient)
        assertEquals(1.0, meterRegistry.counter("advisor.skipped", Tags.of("ticker", "SBER", "reason", "HOLD")).count())
    }

    @Test
    fun `fallback response from llm is neutral and never blocks`() {
        stubLlm(LlmResponse.fallback("NO_API_KEY"))

        val verdict = runBlocking { advisor.advise(context, buyDecision, 0.6) }

        assertEquals(AdvisorVerdictType.NEUTRAL, verdict.verdict)
        assertTrue(verdict.explanation.contains("LLM_UNAVAILABLE"), verdict.explanation)
        assertFalse(verdict.blocksEntry)
    }

    @Test
    fun `agree verdict passes through with clamped confidence adjustment`() {
        stubLlmContent(
            """{"verdict":"AGREE","riskLevel":"NORMAL","confidenceAdjustment":0.9,""" +
                """"explanation":"ok","alternativeScenarios":["a","b"]}""",
        )

        val verdict = runBlocking { advisor.advise(context, buyDecision, 0.6) }

        assertEquals(AdvisorVerdictType.AGREE, verdict.verdict)
        assertEquals(0.15, verdict.confidenceAdjustment, "adjustment must be clamped to +0.15")
        assertEquals(listOf("a", "b"), verdict.alternativeScenarios)
        assertEquals(AdvisorRiskLevel.NORMAL, verdict.riskLevel)
        assertFalse(verdict.blocksEntry)
    }

    @Test
    fun `negative adjustment is clamped to minus 0-30`() {
        stubLlmContent("""{"verdict":"AGREE","riskLevel":"LOW","confidenceAdjustment":-3.0,"explanation":"weak"}""")

        val verdict = runBlocking { advisor.advise(context, buyDecision, 0.6) }

        assertEquals(-0.30, verdict.confidenceAdjustment)
    }

    @Test
    fun `veto without critical risk is downgraded to neutral`() {
        stubLlmContent("""{"verdict":"VETO","riskLevel":"HIGH","confidenceAdjustment":-0.1,"explanation":"too risky"}""")

        val verdict = runBlocking { advisor.advise(context, buyDecision, 0.6) }

        assertEquals(AdvisorVerdictType.NEUTRAL, verdict.verdict)
        assertFalse(verdict.blocksEntry, "LLM cannot block without deterministic CRITICAL")
        assertTrue(verdict.explanation.contains("VETO_DOWNGRADED"), verdict.explanation)
        assertEquals(
            1.0,
            meterRegistry.counter("advisor.guardrail.downgrade", Tags.of("reason", "VETO_WITHOUT_CRITICAL")).count(),
        )
    }

    @Test
    fun `critical risk always blocks entry even for agree verdict`() {
        stubLlmContent("""{"verdict":"AGREE","riskLevel":"CRITICAL","confidenceAdjustment":0.1,"explanation":""}""")

        val verdict = runBlocking { advisor.advise(context, buyDecision, 0.6) }

        assertEquals(AdvisorVerdictType.VETO, verdict.verdict)
        assertTrue(verdict.blocksEntry)
        assertEquals(AdvisorRiskLevel.CRITICAL, verdict.riskLevel)
        assertTrue(verdict.explanation.contains("CRITICAL risk level"), verdict.explanation)
        assertTrue(verdict.explanation.contains("CRITICAL_RISK"), verdict.explanation)
    }

    @Test
    fun `malformed json is fail-open parse error`() {
        stubLlmContent("not-a-json")

        val verdict = runBlocking { advisor.advise(context, buyDecision, 0.6) }

        assertEquals(AdvisorVerdictType.NEUTRAL, verdict.verdict)
        assertFalse(verdict.blocksEntry)
        assertTrue(verdict.explanation.contains("PARSE_ERROR"), verdict.explanation)
        assertEquals(1.0, meterRegistry.counter("advisor.parse.error").count())
    }

    @Test
    fun `unknown verdict and risk tokens fall back to neutral and low`() {
        stubLlmContent("""{"verdict":"MAYBE","riskLevel":"EXTREME","confidenceAdjustment":0.05,"explanation":"unclear"}""")

        val verdict = runBlocking { advisor.advise(context, buyDecision, 0.6) }

        assertEquals(AdvisorVerdictType.NEUTRAL, verdict.verdict)
        assertEquals(AdvisorRiskLevel.LOW, verdict.riskLevel)
        assertEquals(0.05, verdict.confidenceAdjustment)
        assertEquals(
            1.0,
            meterRegistry
                .counter("advisor.decision", Tags.of("ticker", "SBER", "verdict", "NEUTRAL", "riskLevel", "LOW"))
                .count(),
        )
    }

    @Test
    fun `fenced json content is parsed`() {
        stubLlmContent("```json\n{\"verdict\":\"AGREE\",\"riskLevel\":\"LOW\",\"confidenceAdjustment\":0.05}\n```")

        val verdict = runBlocking { advisor.advise(context, buyDecision, 0.6) }

        assertEquals(AdvisorVerdictType.AGREE, verdict.verdict)
        assertEquals(0.05, verdict.confidenceAdjustment)
    }

    private fun advisorWith(
        llmClient: ResilientLlmClient,
        budgetMs: Long,
    ): LlmAdvisor =
        LlmAdvisor(
            llmClient = llmClient,
            promptRegistry = promptRegistry,
            semanticCache = semanticCache,
            agentLogRepository = agentLogRepository,
            meterRegistry = meterRegistry,
            objectMapper = objectMapper,
            tradingConfig = TradingConfig().apply { advisorBudgetMs = budgetMs },
        )

    private fun stubLlmContent(content: String) {
        stubLlm(LlmResponse(content = content, tokensUsed = 12, model = "mock-model"))
    }

    private fun stubLlm(response: LlmResponse) {
        llmAnswer = { response }
    }
}
