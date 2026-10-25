package com.trading.bot.service

import com.trading.bot.config.ExperimentConfig
import com.trading.bot.domain.risk.TradeRiskDecision
import com.trading.bot.event.PositionClosedEvent
import com.trading.bot.infrastructure.llm.PromptRegistry
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.StrategyAction
import com.trading.bot.model.entity.ExperimentDecision
import com.trading.bot.repository.ExperimentDecisionRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

/**
 * Unit-тесты Shadow Mode / Decision-level A/B эксперимента ([PaperTradingService]):
 * запись CONTROL/VARIANT решений, метрики, гипотетический P&L вариантной руки
 * (HOLD / противоположное направление / масштаб по объёму) и устойчивость
 * event-listener'а к отсутствию данных и сбоям репозитория.
 *
 * Репозиторий — Mockito-мок (`ExperimentDecisionRepository` — final-класс, inline
 * mock maker), `markResult` перехватывается answer'ом и складывает результаты в
 * потокобезопасную карту: markOutcome выполняется в фоновой корутине, поэтому
 * проверки ждут наступления условия, а не фиксируют порядок.
 */
class PaperTradingServiceTest {
    private val config =
        ExperimentConfig().apply {
            enabled = true
            experimentId = "exp-test"
            rolloutPercent = 100
        }

    private val repository = mock<ExperimentDecisionRepository>()

    private val meterRegistry = SimpleMeterRegistry()

    private val markedResults = ConcurrentHashMap<String, BigDecimal>()

    private lateinit var service: PaperTradingService

    @BeforeEach
    fun setUp() {
        service = PaperTradingService(config, repository, meterRegistry)
    }

    @AfterEach
    fun tearDown() {
        service.close()
        meterRegistry.clear()
    }

    @Test
    fun `control decision is persisted and counted for executed and shadowed arms`() =
        runBlocking {
            stubSave()
            val executed = service.recordControlDecision(decision("cycle-1"), """{"raw":true}""", executed = true)

            assertEquals("CONTROL", executed.arm)
            assertEquals("exp-test", executed.experimentId)
            assertEquals("SBER", executed.ticker)
            assertEquals("MINUTE_10", executed.timeframe)
            assertEquals("BUY", executed.action)
            assertEquals(2, executed.quantity)
            assertEquals(PromptRegistry.DEFAULT_VERSION, executed.version)
            assertEquals("""{"raw":true}""", executed.rawOutput)
            assertFalse(executed.isPaper)
            assertTrue(executed.executed)
            assertEquals(1.0, counter("experiment.control.executed"))

            val shadowed = service.recordControlDecision(decision("cycle-2"), null, executed = false)
            assertFalse(shadowed.executed)
            assertNull(shadowed.rawOutput)
            assertEquals(1.0, counter("experiment.control.shadowed"))
            assertEquals(2.0, counter("experiment.decision.logged", "arm", "CONTROL", "action", "BUY"))
        }

    @Test
    fun `variant decision is always paper and records llm or copy mode`() =
        runBlocking {
            stubSave()
            val llmVariant = service.recordVariantDecision(decision("cycle-1"), "v2")
            assertEquals("VARIANT", llmVariant.arm)
            assertEquals("v2", llmVariant.version)
            assertTrue(llmVariant.isPaper)
            assertFalse(llmVariant.executed)
            assertEquals(1.0, counter("experiment.variant.llm", "mode", "LLM"))

            val copy = service.recordVariantDecision(decision("cycle-2", action = StrategyAction.HOLD, quantity = 0), null)
            assertEquals("shadow-copy", copy.version)
            assertEquals(1.0, counter("experiment.variant.llm", "mode", "COPY"))
            assertEquals(1.0, counter("experiment.decision.logged", "arm", "VARIANT", "action", "HOLD"))
        }

    @Test
    fun `config accessors and status reflect experiment settings`() =
        runBlocking {
            assertTrue(service.isExperimentEnabled())
            assertFalse(service.isShadowExecution())
            assertTrue(service.inExperiment("any-cycle"))
            assertNull(service.variantVersion())
            assertEquals(
                mapOf(
                    "enabled" to true,
                    "experimentId" to "exp-test",
                    "variantPromptVersion" to null,
                    "shadowExecution" to false,
                    "rolloutPercent" to 100,
                ),
                service.status(),
            )

            config.shadowExecution = true
            config.variantPromptVersion = "v9"
            assertTrue(service.isShadowExecution())
            assertEquals("v9", service.variantVersion())

            config.rolloutPercent = 0
            assertFalse(service.inExperiment("any-cycle"))
            config.enabled = false
            assertFalse(service.isExperimentEnabled())
            assertFalse(service.isShadowExecution())
            assertFalse(service.inExperiment("any-cycle"))

            val stored = listOf(decisionRecord("cycle-9"))
            whenever(repository.findRecent(5)).thenReturn(stored)
            assertEquals(stored, service.recentDecisions(5))
        }

    @Test
    fun `position close marks both arms when variant repeats the control direction`() {
        stubOutcomeLookups(variant = variant("BUY", quantity = 2))
        stubMarkResult()

        service.onPositionClosed(closedEvent("cycle-1", BigDecimal("100")))

        awaitCondition { markedResults.size == 2 }
        assertEquals(BigDecimal("100"), markedResults["cycle-1/CONTROL"])
        assertEquals(BigDecimal("100"), markedResults["cycle-1/VARIANT"])
        awaitCondition { counter("experiment.outcome.marked", "arm", "VARIANT") >= 1.0 }
        assertEquals(100.0, counter("experiment.outcome.pnl_profit", "arm", "CONTROL"))
        assertEquals(100.0, counter("experiment.outcome.pnl_profit", "arm", "VARIANT"))
        assertEquals(1.0, counter("experiment.outcome.win", "arm", "CONTROL"))
        assertEquals(1.0, counter("experiment.outcome.win", "arm", "VARIANT"))
    }

    @Test
    fun `opposite variant direction negates pnl and counts a loss`() {
        stubOutcomeLookups(variant = variant("SELL", quantity = 2))
        stubMarkResult()

        service.onPositionClosed(closedEvent("cycle-1", BigDecimal("100")))

        awaitCondition { markedResults.size == 2 }
        assertEquals(BigDecimal("-100"), markedResults["cycle-1/VARIANT"])
        awaitCondition { counter("experiment.outcome.pnl_loss", "arm", "VARIANT") >= 100.0 }
        assertEquals(100.0, counter("experiment.outcome.pnl_loss", "arm", "VARIANT"))
        assertEquals(0.0, counter("experiment.outcome.win", "arm", "VARIANT"))
        assertEquals(0.0, counter("experiment.outcome.pnl_loss", "arm", "CONTROL"))
    }

    @Test
    fun `hold variant earns zero and never counts as a win`() {
        stubOutcomeLookups(variant = variant("HOLD", quantity = 0))
        stubMarkResult()

        service.onPositionClosed(closedEvent("cycle-1", BigDecimal("250")))

        awaitCondition { markedResults.size == 2 }
        assertEquals(BigDecimal.ZERO, markedResults["cycle-1/VARIANT"])
        awaitCondition { counter("experiment.outcome.marked", "arm", "VARIANT") >= 1.0 }
        assertEquals(0.0, counter("experiment.outcome.win", "arm", "VARIANT"))
        assertEquals(1.0, counter("experiment.outcome.win", "arm", "CONTROL"))
    }

    @Test
    fun `variant with different quantity is scaled proportionally`() {
        stubOutcomeLookups(variant = variant("BUY", quantity = 4))
        stubMarkResult()

        service.onPositionClosed(closedEvent("cycle-1", BigDecimal("100")))

        awaitCondition { markedResults.size == 2 }
        assertEquals(BigDecimal("200.0000"), markedResults["cycle-1/VARIANT"])
    }

    @Test
    fun `missing control leaves experiment untouched`() {
        runBlocking { whenever(repository.findByCycleIdAndArm("cycle-1", "CONTROL")).thenReturn(null) }
        stubMarkResult()

        service.onPositionClosed(closedEvent("cycle-1", BigDecimal("100")))
        Thread.sleep(200)

        assertTrue(markedResults.isEmpty(), "nothing may be marked without a control decision")
        runBlocking { verify(repository, never()).markResult(any(), any(), any()) }
    }

    @Test
    fun `missing variant marks only the control arm`() {
        stubOutcomeLookups(variant = null)
        stubMarkResult()

        service.onPositionClosed(closedEvent("cycle-1", BigDecimal("-40")))

        awaitCondition { markedResults.size == 1 }
        assertEquals(BigDecimal("-40"), markedResults["cycle-1/CONTROL"])
        assertFalse(markedResults.containsKey("cycle-1/VARIANT"))
        awaitCondition { counter("experiment.outcome.pnl_loss", "arm", "CONTROL") >= 40.0 }
        assertEquals(40.0, counter("experiment.outcome.pnl_loss", "arm", "CONTROL"))
    }

    @Test
    fun `position close without cycle id is ignored`() {
        service.onPositionClosed(closedEvent(cycleId = null, pnl = BigDecimal("100")))
        Thread.sleep(100)

        runBlocking { verify(repository, never()).findByCycleIdAndArm(any(), any()) }
        assertTrue(markedResults.isEmpty())
    }

    @Test
    fun `repository failure inside outcome marking is swallowed`() {
        runBlocking {
            whenever(repository.findByCycleIdAndArm("cycle-1", "CONTROL")).thenThrow(RuntimeException("db down"))
        }
        stubMarkResult()

        // Не должно выбросить наружу: ошибка логируется в фоновой корутине.
        service.onPositionClosed(closedEvent("cycle-1", BigDecimal("100")))
        Thread.sleep(200)

        assertTrue(markedResults.isEmpty())
    }

    // ---------- helpers ----------

    private fun stubSave() {
        runBlocking {
            whenever(repository.save(any())).thenAnswer { invocation -> invocation.arguments[0] as ExperimentDecision }
        }
    }

    private fun stubMarkResult() {
        runBlocking {
            whenever(repository.markResult(any(), any(), any())).thenAnswer { invocation ->
                val cycleId: String = invocation.getArgument(0)
                val arm: String = invocation.getArgument(1)
                val pnl: BigDecimal = invocation.getArgument(2)
                markedResults["$cycleId/$arm"] = pnl
                null
            }
        }
    }

    private fun stubOutcomeLookups(variant: ExperimentDecision?) {
        runBlocking {
            whenever(repository.findByCycleIdAndArm("cycle-1", "CONTROL")).thenReturn(decisionRecord("cycle-1"))
            whenever(repository.findByCycleIdAndArm("cycle-1", "VARIANT")).thenReturn(variant)
        }
    }

    private fun decision(
        cycleId: String,
        action: StrategyAction = StrategyAction.BUY,
        quantity: Int = 2,
    ): TradeRiskDecision =
        TradeRiskDecision(
            ticker = "SBER",
            cycleId = cycleId,
            timeframe = "MINUTE_10",
            action = action,
            direction = if (action == StrategyAction.SELL) PositionDirection.SHORT else PositionDirection.LONG,
            quantity = quantity,
            targetPrice = BigDecimal("101"),
            stopLoss = BigDecimal("99"),
            takeProfit = BigDecimal("105"),
            signalStrength = 0.72,
            reasoning = "unit test",
        )

    private fun decisionRecord(
        cycleId: String,
        action: String = "BUY",
        quantity: Int = 2,
    ): ExperimentDecision =
        ExperimentDecision(
            cycleId = cycleId,
            experimentId = "exp-test",
            arm = "CONTROL",
            ticker = "SBER",
            action = action,
            quantity = quantity,
            isPaper = false,
        )

    private fun variant(
        action: String,
        quantity: Int,
    ): ExperimentDecision = decisionRecord("cycle-1", action = action, quantity = quantity).copy(arm = "VARIANT", isPaper = true)

    private fun closedEvent(
        cycleId: String?,
        pnl: BigDecimal,
    ): PositionClosedEvent =
        PositionClosedEvent(
            positionId = 1L,
            ticker = "SBER",
            pnl = pnl,
            reason = "TAKE_PROFIT",
            cycleId = cycleId,
        )

    private fun counter(
        name: String,
        vararg tags: String,
    ): Double {
        val registered = meterRegistry.find(name).tags(*tags).counter()
        return registered?.count() ?: 0.0
    }

    private fun awaitCondition(condition: () -> Boolean) {
        repeat(100) {
            if (condition()) return
            Thread.sleep(20)
        }
        fail<Unit>("async experiment outcome was not recorded within 2s")
    }
}
