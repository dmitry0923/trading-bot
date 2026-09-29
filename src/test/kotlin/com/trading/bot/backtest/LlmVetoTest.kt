package com.trading.bot.backtest

import com.trading.bot.agent.ArbitratorAgent
import com.trading.bot.agent.ContrarianAgent
import com.trading.bot.config.BacktestConfig
import com.trading.bot.domain.technical.IndicatorCalculator
import com.trading.bot.infrastructure.llm.PromptRegistry
import com.trading.bot.model.StrategyAction
import com.trading.bot.model.dto.MarketSnapshot
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Юнит-тесты research-LLM-veto (docs/20 §10).
 *
 * Проверяется ровно та семантика, которая делает эксперимент интерпретируемым:
 * блокирует ТОЛЬКО `final.action == HOLD`, направление/цену/силу арбитра игнорирует,
 * отказ LLM решает флаг `blockOnUnknown`, CRITICAL challenge блокирует без арбитра,
 * вердикт мемоизируется (WFA переигрывает одни и те же бары в каждом фолде/сетке).
 *
 * Стабы — real-value (не matcher'ы): как и в [AgentBacktestSignalGeneratorTest],
 * matcher-стаб на suspend-методах агентов в этой версии mockito-kotlin не совпадает,
 * а real-value стаб ещё и фиксирует точную передачу `bypassCache = true`/`0.0`.
 */
class LlmVetoTest {
    private val contrarianAgent = Mockito.mock(ContrarianAgent::class.java)
    private val arbitratorAgent = Mockito.mock(ArbitratorAgent::class.java)
    private val registry = SimpleMeterRegistry()

    private val settings =
        LlmVetoSettings(
            enabled = true,
            budgetMs = 5_000,
            blockOnUnknown = true,
            promptVersion = PromptRegistry.DEFAULT_VERSION,
            sampleEvery = 0,
            temperature = 0.0,
            cacheNamespace = "backtest-veto",
        )

    private fun veto(settings: LlmVetoSettings = this.settings) = LlmVeto(contrarianAgent, arbitratorAgent, settings, registry)

    private val barTime = LocalDateTime.of(2026, 9, 28, 11, 0)
    private val snapshot =
        MarketSnapshot(
            ticker = "CNYRUBF",
            currentPrice = BigDecimal("92.500"),
            volume = 1_000L,
            timestamp = barTime.atZone(ZoneId.systemDefault()).toInstant(),
        )
    private val indicators =
        IndicatorCalculator.Indicators(
            rsi = 48.5,
            atr = 0.42,
            macdLine = 0.11,
            macdSignal = 0.09,
            macdHistogram = 0.02,
            bbUpper = BigDecimal("93.100"),
            bbMiddle = BigDecimal("92.500"),
            bbLower = BigDecimal("91.900"),
            trend = "FLAT",
            conclusion = "NEUTRAL",
        )
    private val challenge = ContrarianAgent.ChallengeReport(isValid = true, riskLevel = "LOW", critique = "ok", signalStrength = 0.6)
    private val holdFinal = ArbitratorAgent.Final(StrategyAction.HOLD, BigDecimal("0"), 0.0, "no edge", null)
    private val buyFinal = ArbitratorAgent.Final(StrategyAction.BUY, BigDecimal("999"), 0.95, "go long", null)

    private val draft =
        com.trading.bot.agent.StrategyAgent.Draft(
            action = StrategyAction.BUY,
            targetPrice = BigDecimal("92.500"),
            signalStrength = 0.7,
            reasoning = LlmVeto.DRAFT_REASONING,
        )
    private val tech =
        com.trading.bot.model.dto.TechnicalReport(
            trend = "FLAT",
            rsi = 48.5,
            atr = 0.42,
            macd = 0.02,
            bbUpper = BigDecimal("93.100"),
            bbLower = BigDecimal("91.900"),
            conclusion = "NEUTRAL",
            signalStrength = 0.7,
            reasoning = LlmVeto.TECH_REASONING,
        )
    private val fund =
        com.trading.bot.model.dto.FundamentalReport(
            conclusion = "NEUTRAL",
            signalStrength = 0.0,
            reasoning = LlmVeto.FUND_REASONING,
        )

    private fun draftOf(action: StrategyAction) =
        com.trading.bot.agent.StrategyAgent.Draft(
            action = action,
            targetPrice = BigDecimal("92.500"),
            signalStrength = 0.7,
            reasoning = LlmVeto.DRAFT_REASONING,
        )

    private suspend fun stubChallenge(
        report: ContrarianAgent.ChallengeReport,
        action: StrategyAction = StrategyAction.BUY,
    ) {
        whenever(
            contrarianAgent.challenge(
                draft = draftOf(action),
                tech = tech,
                fund = fund,
                snapshot = snapshot,
                cycleId = "c1",
                version = PromptRegistry.DEFAULT_VERSION,
                temperature = 0.0,
                cacheNamespace = "backtest-veto",
                techDelta = null,
                bypassCache = true,
            ),
        ).thenReturn(report)
    }

    private suspend fun stubFinal(
        final: ArbitratorAgent.Final,
        action: StrategyAction = StrategyAction.BUY,
    ) {
        whenever(
            arbitratorAgent.adjudicate(
                draft = draftOf(action),
                challenge = challenge,
                tech = tech,
                fund = fund,
                snapshot = snapshot,
                cycleId = "c1",
                contextPrompt = null,
                adaptiveConfidence = 0.0,
                version = PromptRegistry.DEFAULT_VERSION,
                bypassCache = true,
                temperature = 0.0,
                cacheNamespace = "backtest-veto",
            ),
        ).thenReturn(final)
    }

    private suspend fun run(action: StrategyAction = StrategyAction.BUY) =
        veto().veto(
            ticker = "CNYRUBF",
            action = action,
            strength = 0.7,
            snapshot = snapshot,
            indicators = indicators,
            cycleId = "c1",
            barTime = barTime,
        )

    @Test
    fun `arbitrator HOLD blocks the deterministic entry`() {
        runBlocking {
            stubChallenge(challenge)
            stubFinal(holdFinal)
        }

        val verdict = runBlocking { run() }

        assertEquals(false, verdict.allow)
        assertEquals(LlmVeto.Reason.ARB_HOLD, verdict.reason)
        assertEquals(1.0, registry.counter("bt_llm_veto_blocked_total", Tags.of("ticker", "CNYRUBF", "reason", "ARB_HOLD")).count())
        assertEquals(1.0, registry.counter("bt_llm_veto_candidates_total", Tags.of("ticker", "CNYRUBF")).count())
    }

    @Test
    fun `arbitrator direction price and strength are ignored - only HOLD vetoes`() {
        runBlocking {
            stubChallenge(challenge, StrategyAction.SELL)
            stubFinal(buyFinal, StrategyAction.SELL)
        }

        val verdict = runBlocking { run(StrategyAction.SELL) }

        assertTrue(verdict.allow, "SELL-детерминированный вход арбитр не вправе переписать в покупку")
        assertEquals(LlmVeto.Reason.ALLOWED, verdict.reason)
        assertEquals(1.0, registry.counter("bt_llm_veto_allowed_total", Tags.of("ticker", "CNYRUBF", "reason", "ALLOWED")).count())
    }

    @Test
    fun `HOLD action skips LLM entirely`() {
        val verdict = runBlocking { run(StrategyAction.HOLD) }

        assertTrue(verdict.allow)
        Mockito.verifyNoInteractions(contrarianAgent, arbitratorAgent)
        assertEquals(0.0, registry.find("bt_llm_veto_candidates_total").counters().sumOf { it.count() })
    }

    @Test
    fun `CRITICAL challenge blocks without calling the arbitrator`() {
        val critical = challenge.copy(riskLevel = "CRITICAL")
        runBlocking { stubChallenge(critical) }

        val verdict = runBlocking { run() }

        assertEquals(false, verdict.allow)
        assertEquals(LlmVeto.Reason.CHALLENGE_CRITICAL, verdict.reason)
        Mockito.verifyNoInteractions(arbitratorAgent)
    }

    @Test
    fun `LLM failure is fail-closed when blockOnUnknown is true`() {
        val unavailable = challenge.copy(llmAvailable = false)
        runBlocking { stubChallenge(unavailable) }

        val verdict = runBlocking { run() }

        assertEquals(false, verdict.allow)
        assertEquals(LlmVeto.Reason.LLM_FAILURE, verdict.reason)
        Mockito.verifyNoInteractions(arbitratorAgent)
    }

    @Test
    fun `LLM failure passes the candidate when blockOnUnknown is false`() {
        val unavailable = challenge.copy(llmAvailable = false)
        runBlocking { stubChallenge(unavailable) }
        val lenient = veto(settings.copy(blockOnUnknown = false))

        val verdict =
            runBlocking {
                lenient.veto(
                    ticker = "CNYRUBF",
                    action = StrategyAction.BUY,
                    strength = 0.7,
                    snapshot = snapshot,
                    indicators = indicators,
                    cycleId = "c1",
                    barTime = barTime,
                )
            }

        assertTrue(verdict.allow, "blockOnUnknown=false — отказ LLM не должен блокировать")
        assertEquals(LlmVeto.Reason.LLM_FAILURE_PASSED, verdict.reason)
    }

    @Test
    fun `arbitrator refusal is treated as LLM failure, not as a veto`() {
        // overrideReason != null ⇒ это отказ (LLM_UNAVAILABLE/SCHEMA_REJECTED/PARSE_ERROR),
        // а не вердикт HOLD: при blockOnUnknown=false кандидат проходит.
        val refused = holdFinal.copy(overrideReason = "SCHEMA_REJECTED")
        runBlocking {
            stubChallenge(challenge)
            stubFinal(refused)
        }
        val lenient = veto(settings.copy(blockOnUnknown = false))

        val verdict =
            runBlocking {
                lenient.veto(
                    ticker = "CNYRUBF",
                    action = StrategyAction.BUY,
                    strength = 0.7,
                    snapshot = snapshot,
                    indicators = indicators,
                    cycleId = "c1",
                    barTime = barTime,
                )
            }

        assertTrue(verdict.allow)
        assertEquals(LlmVeto.Reason.LLM_FAILURE_PASSED, verdict.reason)
    }

    @Test
    fun `budget timeout is fail-closed and counted as BUDGET_TIMEOUT`() {
        // Тот же TimeoutCancellationException, что бросает withTimeout при исчерпании
        // бюджета; конструктор internal в Kotlin, поэтому ставим его рефлексией.
        val timeout =
            Class
                .forName("kotlinx.coroutines.TimeoutCancellationException")
                .getConstructor(String::class.java)
                .newInstance("injected") as Throwable
        runBlocking {
            whenever(
                contrarianAgent.challenge(
                    draft = draft,
                    tech = tech,
                    fund = fund,
                    snapshot = snapshot,
                    cycleId = "c1",
                    version = PromptRegistry.DEFAULT_VERSION,
                    temperature = 0.0,
                    cacheNamespace = "backtest-veto",
                    techDelta = null,
                    bypassCache = true,
                ),
            ).thenThrow(timeout)
        }

        val verdict = runBlocking { run() }

        assertEquals(false, verdict.allow)
        assertEquals(LlmVeto.Reason.BUDGET_TIMEOUT, verdict.reason)
        assertEquals(1.0, registry.counter("bt_llm_veto_blocked_total", Tags.of("ticker", "CNYRUBF", "reason", "BUDGET_TIMEOUT")).count())
    }

    @Test
    fun `agent exception does not fail the run and is counted as ERROR`() {
        runBlocking {
            whenever(
                contrarianAgent.challenge(
                    draft = draft,
                    tech = tech,
                    fund = fund,
                    snapshot = snapshot,
                    cycleId = "c1",
                    version = PromptRegistry.DEFAULT_VERSION,
                    temperature = 0.0,
                    cacheNamespace = "backtest-veto",
                    techDelta = null,
                    bypassCache = true,
                ),
            ).thenThrow(IllegalStateException("llm boom"))
        }

        val verdict = runBlocking { run() }

        assertEquals(false, verdict.allow)
        assertEquals(LlmVeto.Reason.ERROR, verdict.reason)
    }

    @Test
    fun `missing indicators is fail-closed without LLM call`() {
        val verdict =
            runBlocking {
                veto().veto(
                    ticker = "CNYRUBF",
                    action = StrategyAction.BUY,
                    strength = 0.7,
                    snapshot = snapshot,
                    indicators = null,
                    cycleId = "c1",
                    barTime = barTime,
                )
            }

        assertEquals(false, verdict.allow)
        assertEquals(LlmVeto.Reason.NO_INDICATORS, verdict.reason)
        Mockito.verifyNoInteractions(contrarianAgent, arbitratorAgent)
    }

    @Test
    fun `verdict is memoized per bar so WFA replays do not re-call the LLM`() {
        runBlocking {
            stubChallenge(challenge)
            stubFinal(holdFinal)
        }
        val shared = veto()

        val first =
            runBlocking {
                shared.veto(
                    ticker = "CNYRUBF",
                    action = StrategyAction.BUY,
                    strength = 0.7,
                    snapshot = snapshot,
                    indicators = indicators,
                    cycleId = "c1",
                    barTime = barTime,
                )
            }
        // Другой cycleId (другой фолд/итерация сетки SL/TP) и другой strength —
        // вердикт обязан остаться тем же, вызовов LLM не добавляем.
        val second =
            runBlocking {
                shared.veto(
                    ticker = "CNYRUBF",
                    action = StrategyAction.BUY,
                    strength = 0.9,
                    snapshot = snapshot,
                    indicators = indicators,
                    cycleId = "c2",
                    barTime = barTime,
                )
            }

        assertEquals(first, second)
        runBlocking {
            Mockito
                .verify(
                    contrarianAgent,
                    Mockito.times(1),
                ).challenge(draft, tech, fund, snapshot, "c1", PromptRegistry.DEFAULT_VERSION, 0.0, "backtest-veto", null, true)
        }
        assertEquals(1.0, registry.counter("bt_llm_veto_cache_hits_total", Tags.of("ticker", "CNYRUBF")).count())
    }

    @Test
    fun `sampleEvery keeps every N-th candidate and memoizes skipped ones`() {
        val sampled = veto(settings.copy(sampleEvery = 3))
        runBlocking {
            stubChallenge(challenge)
            stubFinal(buyFinal)
        }

        val verdicts =
            runBlocking {
                (0 until 4).map { i ->
                    sampled.veto(
                        ticker = "CNYRUBF",
                        action = StrategyAction.BUY,
                        strength = 0.7,
                        snapshot = snapshot,
                        indicators = indicators,
                        cycleId = "c1",
                        barTime = barTime.plusMinutes(10L * i),
                    )
                }
            }

        assertEquals(listOf("ALLOWED", "SAMPLED_OUT", "SAMPLED_OUT", "ALLOWED"), verdicts.map { it.reason })
        assertEquals(2.0, registry.counter("bt_llm_veto_candidates_total", Tags.of("ticker", "CNYRUBF")).count())
    }

    @Test
    fun `settings validate sampling and budget`() {
        org.junit.jupiter.api.Assertions
            .assertThrows(IllegalArgumentException::class.java) { LlmVetoSettings(sampleEvery = -1) }
        org.junit.jupiter.api.Assertions
            .assertThrows(IllegalArgumentException::class.java) { LlmVetoSettings(budgetMs = -1) }
    }

    @Test
    fun `settings from config are used when no query override is given`() {
        val config =
            BacktestConfig().apply {
                llmVetoEnabled = true
                llmVetoBudgetMs = 7_777
                llmVetoBlockOnUnknown = false
                llmVetoPromptVersion = "veto-v1"
                llmVetoSampleEvery = 3
                llmVetoTemperature = 0.0
                llmVetoCacheNamespace = "backtest-veto"
            }

        val fromConfig = LlmVetoSettings.from(config)
        assertTrue(fromConfig.enabled, "config-only включение обязано доходить до настроек veto")
        assertEquals(7_777L, fromConfig.budgetMs)
        assertEquals(false, fromConfig.blockOnUnknown)
        assertEquals("veto-v1", fromConfig.promptVersion)
        assertEquals(3, fromConfig.sampleEvery)

        val fromQuery =
            LlmVetoSettings.from(
                config,
                LlmVetoOverrides(enabled = false, budgetMs = null, blockOnUnknown = null, promptVersion = null, sampleEvery = null),
            )
        assertEquals(false, fromQuery.enabled, "query-параметр перекрывает bt.llm-veto-enabled")
        assertEquals(7_777L, fromQuery.budgetMs, "непереданные query-поля остаются из config")
    }
}
