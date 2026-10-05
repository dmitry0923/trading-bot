package com.trading.bot.backtest

import com.trading.bot.agent.ArbitratorAgent
import com.trading.bot.agent.ContrarianAgent
import com.trading.bot.config.BacktestAgentConfig
import com.trading.bot.model.StrategyAction
import com.trading.bot.model.dto.MarketSnapshot
import com.trading.bot.model.entity.Candle
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import kotlin.test.assertEquals

/**
 * Unit-тесты для [AgentVetoBacktestSignalGenerator].
 *
 * Покрывает три обязательных сценария:
 * 1. Fail-closed: LLM недоступен → HOLD (не торговать).
 * 2. ALLOW логика: LLM согласен → детерминированный сигнал проходит.
 * 3. BLOCK логика: LLM блокирует → HOLD независимо от baseline сигнала.
 *
 * Mockito-паттерн для Kotlin non-null типов: используем вспомогательные helper-функции
 * (anyArgNonNull) во избежание NPE при Mockito.any() c non-nullable типами.
 */
@ExtendWith(MockitoExtension::class)
class AgentVetoBacktestSignalGeneratorTest {

    @Mock
    private lateinit var contrarianAgent: ContrarianAgent

    @Mock
    private lateinit var arbitratorAgent: ArbitratorAgent

    private lateinit var meterRegistry: MeterRegistry
    private lateinit var agentConfig: BacktestAgentConfig

    @BeforeEach
    fun setUp() {
        meterRegistry = SimpleMeterRegistry()
        agentConfig = BacktestAgentConfig()
    }

    /**
     * Тест 1: Fail-closed — LLM недоступен → HOLD.
     *
     * При недоступности LLM (contrarianAgent.challenge возвращает llmAvailable=false)
     * генератор должен вернуть HOLD, а не детерминированный сигнал.
     */
    @Test
    fun `fail-closed - LLM unavailable returns HOLD`() =
        runBlocking {
            val candles = buildCandles(40, rsiOversold = true)
            val generator =
                AgentVetoBacktestSignalGenerator(
                    contrarianAgent = contrarianAgent,
                    arbitratorAgent = arbitratorAgent,
                    agentConfig = agentConfig,
                    meterRegistry = meterRegistry,
                )

            // Настраиваем LLM: challenge возвращает llmAvailable=false (LLM недоступен).
            `when`(
                contrarianAgent.challenge(
                    anyNonNull(),
                    anyNonNull(),
                    anyNonNull(),
                    anyNonNull(),
                    anyString(),
                    anyString(),
                    anyDouble(),
                    anyString(),
                    anyNullable(),
                    anyBoolean(),
                ),
            ).thenReturn(
                ContrarianAgent.ChallengeReport(
                    llmAvailable = false,
                    riskLevel = "UNKNOWN",
                    reasoning = "LLM unavailable",
                    concerns = emptyList(),
                    signalStrength = 0.0,
                ),
            )

            val result = generator.signal("CNYRUBF", candles, 39, 20, "test-cycle")

            assertEquals(
                StrategyAction.HOLD,
                result,
                "Fail-closed: при недоступности LLM ожидается HOLD",
            )
        }

    /**
     * Тест 2: ALLOW — LLM согласен с входом → детерминированный сигнал проходит.
     *
     * При согласии LLM (arbitratorAgent.adjudicate возвращает action != HOLD)
     * генератор должен вернуть детерминированный сигнал (BUY).
     */
    @Test
    fun `allow - LLM approves entry returns deterministic signal`() =
        runBlocking {
            val candles = buildCandles(40, rsiOversold = true)
            val generator =
                AgentVetoBacktestSignalGenerator(
                    contrarianAgent = contrarianAgent,
                    arbitratorAgent = arbitratorAgent,
                    agentConfig = agentConfig,
                    meterRegistry = meterRegistry,
                )

            setupLlmAllow()

            val result = generator.signal("CNYRUBF", candles, 39, 20, "test-cycle")

            assertEquals(
                StrategyAction.BUY,
                result,
                "ALLOW: при согласии LLM ожидается BUY (детерминированный сигнал)",
            )
        }

    /**
     * Тест 3: BLOCK — LLM блокирует вход → HOLD.
     *
     * При HOLD от ArbitratorAgent с null overrideReason (настоящий вердикт)
     * генератор должен вернуть HOLD.
     */
    @Test
    fun `block - LLM vetoes entry returns HOLD`() =
        runBlocking {
            val candles = buildCandles(40, rsiOversold = true)
            val generator =
                AgentVetoBacktestSignalGenerator(
                    contrarianAgent = contrarianAgent,
                    arbitratorAgent = arbitratorAgent,
                    agentConfig = agentConfig,
                    meterRegistry = meterRegistry,
                )

            setupLlmBlock()

            val result = generator.signal("CNYRUBF", candles, 39, 20, "test-cycle")

            assertEquals(
                StrategyAction.HOLD,
                result,
                "BLOCK: при вето LLM ожидается HOLD",
            )
        }

    /**
     * Тест 4: Совместимость с ArbitratorAgent.adjudicate() — вызывается корректно.
     *
     * При нормальной работе цепочки (challenge llmAvailable=true, riskLevel != CRITICAL)
     * должен вызываться ArbitratorAgent.adjudicate().
     */
    @Test
    fun `arbitrator compatibility - adjudicate called when challenge passes`() =
        runBlocking {
            val candles = buildCandles(40, rsiOversold = true)
            val generator =
                AgentVetoBacktestSignalGenerator(
                    contrarianAgent = contrarianAgent,
                    arbitratorAgent = arbitratorAgent,
                    agentConfig = agentConfig,
                    meterRegistry = meterRegistry,
                )

            setupLlmAllow()

            // Выполняем — ArbitratorAgent.adjudicate вызывается без исключений.
            val result = generator.signal("CNYRUBF", candles, 39, 20, "test-cycle")

            // Детерминированный сигнал пропустился → не HOLD.
            assertEquals(
                StrategyAction.BUY,
                result,
                "Совместимость: adjudicate вызван корректно, сигнал не блокирован",
            )
        }

    // ========== Helpers ==========

    private fun buildCandles(
        count: Int,
        rsiOversold: Boolean,
    ): List<Candle> {
        val basePrice = if (rsiOversold) BigDecimal("14.00") else BigDecimal("14.50")
        return (0 until count).map { i ->
            Candle(
                ticker = "CNYRUBF",
                time = LocalDateTime.now().minusMinutes((count - i).toLong() * 10),
                openPrice = basePrice,
                highPrice = basePrice.multiply(BigDecimal("1.005")),
                lowPrice = basePrice.multiply(BigDecimal("0.995")),
                closePrice = if (rsiOversold && i > 30) basePrice.multiply(BigDecimal("0.985")) else basePrice,
                volume = 1000L,
                timeframe = "MINUTE_10",
            )
        }
    }

    private suspend fun setupLlmAllow() {
        val challengeReport =
            ContrarianAgent.ChallengeReport(
                llmAvailable = true,
                riskLevel = "LOW",
                reasoning = "Signal looks valid",
                concerns = emptyList(),
                signalStrength = 0.7,
            )
        `when`(
            contrarianAgent.challenge(
                anyNonNull(),
                anyNonNull(),
                anyNonNull(),
                anyNonNull(),
                anyString(),
                anyString(),
                anyDouble(),
                anyString(),
                anyNullable(),
                anyBoolean(),
            ),
        ).thenReturn(challengeReport)

        val adjudicateResult =
            ArbitratorAgent.AdjudicationResult(
                action = StrategyAction.BUY,
                signalStrength = 0.7,
                reasoning = "Entry approved",
                overrideReason = null,
            )
        `when`(
            arbitratorAgent.adjudicate(
                anyNonNull(),
                anyNonNull(),
                anyNonNull(),
                anyNonNull(),
                anyNonNull(),
                anyString(),
                anyNullable(),
                anyDouble(),
                anyString(),
                anyBoolean(),
                anyDouble(),
                anyString(),
            ),
        ).thenReturn(adjudicateResult)
    }

    private suspend fun setupLlmBlock() {
        val challengeReport =
            ContrarianAgent.ChallengeReport(
                llmAvailable = true,
                riskLevel = "HIGH",
                reasoning = "Risk too high",
                concerns = listOf("Market conditions unfavorable"),
                signalStrength = 0.2,
            )
        `when`(
            contrarianAgent.challenge(
                anyNonNull(),
                anyNonNull(),
                anyNonNull(),
                anyNonNull(),
                anyString(),
                anyString(),
                anyDouble(),
                anyString(),
                anyNullable(),
                anyBoolean(),
            ),
        ).thenReturn(challengeReport)

        val adjudicateResult =
            ArbitratorAgent.AdjudicationResult(
                action = StrategyAction.HOLD,
                signalStrength = 0.2,
                reasoning = "Entry blocked by arbitrator",
                overrideReason = null,
            )
        `when`(
            arbitratorAgent.adjudicate(
                anyNonNull(),
                anyNonNull(),
                anyNonNull(),
                anyNonNull(),
                anyNonNull(),
                anyString(),
                anyNullable(),
                anyDouble(),
                anyString(),
                anyBoolean(),
                anyDouble(),
                anyString(),
            ),
        ).thenReturn(adjudicateResult)
    }

    // Mockito helper-функции для non-null Kotlin типов.
    private fun <T> anyNonNull(): T = org.mockito.Mockito.any<T>() ?: error("Mockito returned null for non-null type")
    private fun <T> anyNullable(): T? = org.mockito.Mockito.any<T>()
    private fun anyString(): String = org.mockito.Mockito.anyString() ?: ""
    private fun anyDouble(): Double = org.mockito.Mockito.anyDouble()
    private fun anyBoolean(): Boolean = org.mockito.Mockito.anyBoolean()
}
