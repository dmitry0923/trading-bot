package com.trading.bot.backtest

import com.trading.bot.agent.ArbitratorAgent
import com.trading.bot.agent.ContrarianAgent
import com.trading.bot.agent.StrategyAgent
import com.trading.bot.config.BacktestConfig
import com.trading.bot.domain.technical.IndicatorCalculator
import com.trading.bot.infrastructure.llm.PromptRegistry
import com.trading.bot.model.StrategyAction
import com.trading.bot.model.dto.FundamentalReport
import com.trading.bot.model.dto.MarketSnapshot
import com.trading.bot.model.dto.TechnicalReport
import com.trading.bot.model.entity.Candle
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Unit-тесты для [AgentVetoBacktestSignalGenerator] (LLM-veto Вариант B, docs/24).
 *
 * Покрывает обязательные сценарии:
 * 1. Fail-closed: LLM недоступен → HOLD (не отдавать детерминированный сигнал).
 * 2. ALLOW: арбитр согласился → детерминированный сигнал проходит без изменений.
 * 3. BLOCK: арбитр вернул HOLD → вход заблокирован.
 * 4. CRITICAL challenge → блок без вызова арбитра.
 *
 * Моки ставятся **именованными аргументами с точными значениями** (как в [LlmVetoTest]):
 * [LlmVeto] строит draft/tech/fund детерминированно, поэтому точное совпадение аргументов
 * надёжнее matchers'ов — при расхождении значений тест падает явно, а не молча пропускает
 * вызов агента.
 */
@ExtendWith(MockitoExtension::class)
class AgentVetoBacktestSignalGeneratorTest {
    @Mock
    private lateinit var contrarianAgent: ContrarianAgent

    @Mock
    private lateinit var arbitratorAgent: ArbitratorAgent

    private lateinit var meterRegistry: MeterRegistry
    private lateinit var backtestConfig: BacktestConfig

    @BeforeEach
    fun setUp() {
        meterRegistry = SimpleMeterRegistry()
        backtestConfig = BacktestConfig()
    }

    /**
     * Тест 1: fail-closed — LLM недоступен, вход блокируется.
     */
    @Test
    fun `fail-closed - LLM unavailable returns HOLD`() =
        runBlocking {
            val candles = buySetupCandles()
            val index = candles.lastIndex
            val window = candles.subList(0, index + 1)
            val baseline = DeterministicBacktestSignalGenerator().signal(TICKER, candles, index, MIN_BARS, CYCLE_ID)
            assertNotEquals(StrategyAction.HOLD, baseline, "Фикстура должна давать не-HOLD baseline")

            stubChallenge(challengeOf(llmAvailable = false), window, baseline)

            val result = generator().signal(TICKER, candles, index, MIN_BARS, CYCLE_ID)

            assertEquals(
                StrategyAction.HOLD,
                result,
                "Fail-closed: при недоступности LLM ожидается HOLD",
            )
        }

    /**
     * Тест 2: ALLOW — арбитр согласился, детерминированный сигнал проходит как есть.
     */
    @Test
    fun `allow - arbitrator approves entry returns deterministic signal`() =
        runBlocking {
            val candles = buySetupCandles()
            val index = candles.lastIndex
            val window = candles.subList(0, index + 1)
            val baseline = DeterministicBacktestSignalGenerator().signal(TICKER, candles, index, MIN_BARS, CYCLE_ID)
            assertNotEquals(StrategyAction.HOLD, baseline, "Фикстура должна давать не-HOLD baseline")

            stubChallenge(challengeOf(), window, baseline)
            stubFinal(ArbitratorAgent.Final(StrategyAction.BUY, BigDecimal("89.50"), 0.70, "entry approved"), window, baseline)

            val result = generator().signal(TICKER, candles, index, MIN_BARS, CYCLE_ID)

            assertEquals(
                baseline,
                result,
                "ALLOW: детерминированный сигнал должен пройти без изменений",
            )
        }

    /**
     * Тест 3: BLOCK — арбитр вернул HOLD, вход отклонён независимо от baseline.
     */
    @Test
    fun `block - arbitrator HOLD vetoes entry`() =
        runBlocking {
            val candles = buySetupCandles()
            val index = candles.lastIndex
            val window = candles.subList(0, index + 1)
            val baseline = DeterministicBacktestSignalGenerator().signal(TICKER, candles, index, MIN_BARS, CYCLE_ID)
            assertNotEquals(StrategyAction.HOLD, baseline, "Фикстура должна давать не-HOLD baseline")

            stubChallenge(challengeOf(), window, baseline)
            stubFinal(ArbitratorAgent.Final(StrategyAction.HOLD, BigDecimal.ZERO, 0.20, "blocked"), window, baseline)

            val result = generator().signal(TICKER, candles, index, MIN_BARS, CYCLE_ID)

            assertEquals(
                StrategyAction.HOLD,
                result,
                "BLOCK: вердикт HOLD арбитра должен блокировать вход",
            )
        }

    /**
     * Тест 4: CRITICAL challenge блокирует **до** вызова арбитра (fail-closed по риску).
     */
    @Test
    fun `block - CRITICAL challenge blocks without calling the arbitrator`(): Unit =
        runBlocking {
            val candles = buySetupCandles()
            val index = candles.lastIndex
            val window = candles.subList(0, index + 1)
            val baseline = DeterministicBacktestSignalGenerator().signal(TICKER, candles, index, MIN_BARS, CYCLE_ID)
            assertNotEquals(StrategyAction.HOLD, baseline, "Фикстура должна давать не-HOLD baseline")
            stubChallenge(challengeOf(riskLevel = "CRITICAL"), window, baseline)

            val result = generator().signal(TICKER, candles, index, MIN_BARS, CYCLE_ID)
            Mockito.verifyNoInteractions(arbitratorAgent)

            assertEquals(
                StrategyAction.HOLD,
                result,
                "CRITICAL challenge обязан блокировать вход",
            )
        }

    /**
     * Тест 5: baseline HOLD — LLM не вызывается вовсе (вetoить нечего).
     */
    @Test
    fun `baseline HOLD - agents are not called`() =
        runBlocking {
            // Ровный рынок: RSI ≈ 50, MACD ≈ 0 → baseline HOLD.
            val candles = flatCandles(40)

            val result = generator().signal("CNYRUBF", candles, candles.lastIndex, MIN_BARS, CYCLE_ID)

            assertEquals(StrategyAction.HOLD, result, "Baseline HOLD должен проходить без вызова LLM")
            Mockito.verifyNoInteractions(contrarianAgent, arbitratorAgent)
        }

    /**
     * Тест 6: блок считается РОВНО один раз.
     *
     * Счётчики ведёт [LlmVeto]; прежняя дублирующая запись в генераторе удваивала
     * каждое наблюдение, из-за чего block rate по метрикам был непригоден.
     */
    @Test
    fun `block - veto counter is incremented exactly once`() =
        runBlocking {
            val candles = buySetupCandles()
            val index = candles.lastIndex
            val window = candles.subList(0, index + 1)
            val baseline = DeterministicBacktestSignalGenerator().signal(TICKER, candles, index, MIN_BARS, CYCLE_ID)

            stubChallenge(challengeOf(), window, baseline)
            stubFinal(ArbitratorAgent.Final(StrategyAction.HOLD, BigDecimal.ZERO, 0.20, "blocked"), window, baseline)

            generator().signal(TICKER, candles, index, MIN_BARS, CYCLE_ID)

            assertEquals(1.0, counterSum("bt_llm_veto_blocked_total"), "Один блок — одно наблюдение метрики (без двойного счёта)")
            assertEquals(1.0, counterSum("bt_llm_veto_candidates_total"), "Один кандидат — одно наблюдение")
        }

    /**
     * Тест 7: вердикт переиспользуется между вызовами на том же баре (мемоизация).
     *
     * WFA заново проигрывает одни и те же бары в каждом фолде и в каждой ячейке сетки
     * подбора SL/TP. Если бы [LlmVeto] создавался на каждый вызов `signal`, платные
     * вызовы LLM повторялись бы на каждом переигрывании — документированная
     * воспроизводимость и изоляция стоимости прогонов не выполнялись бы.
     */
    @Test
    fun `verdict is reused when the same bar is replayed`() =
        runBlocking {
            val candles = buySetupCandles()
            val index = candles.lastIndex
            val window = candles.subList(0, index + 1)
            val baseline = DeterministicBacktestSignalGenerator().signal(TICKER, candles, index, MIN_BARS, CYCLE_ID)

            stubChallenge(challengeOf(), window, baseline)
            stubFinal(ArbitratorAgent.Final(StrategyAction.HOLD, BigDecimal.ZERO, 0.20, "blocked"), window, baseline)

            val shared = generator()
            val first = shared.signal(TICKER, candles, index, MIN_BARS, CYCLE_ID)
            val second = shared.signal(TICKER, candles, index, MIN_BARS, CYCLE_ID)

            assertEquals(first, second)
            runBlocking {
                Mockito
                    .verify(contrarianAgent, Mockito.times(1))
                    .challenge(
                        draftOf(window, baseline),
                        techOf(window),
                        fundOf(),
                        snapshotOf(window),
                        CYCLE_ID,
                        PromptRegistry.DEFAULT_VERSION,
                        0.0,
                        CACHE_NAMESPACE,
                        null,
                        true,
                    )
            }
            assertEquals(
                1.0,
                counterSum("bt_llm_veto_cache_hits_total"),
                "Второе проигрывание того же бара обязано попасть в мемо",
            )
        }

    /**
     * Тест 8: `sampleEvery` действительно прореживает вызовы на разных барах.
     *
     * Счётчик кандидатов живёт в [LlmVeto], поэтому при его пересоздании на каждый
     * вызов он всегда был 0 и «каждый N-й кандидат» вырождался в проверку всех
     * подряд. Порядок обхода детерминирован: 1-й кандидат проверяется, 2-й
     * прореживается, 3-й проверяется.
     */
    @Test
    fun `sampleEvery thins out LLM calls across bars`() =
        runBlocking {
            val candles = buySetupCandles()
            backtestConfig.llmVetoSampleEvery = 2

            val lastIndex = candles.lastIndex
            val sampledIndex = lastIndex - 1
            // Стибы ставим только для проверяемых баров: на прореженном баре
            // агент не вызывается вовсе (strict stubs это тоже проверяют).
            listOf(lastIndex, lastIndex - 2).forEach { index ->
                val window = candles.subList(0, index + 1)
                val baseline = DeterministicBacktestSignalGenerator().signal(TICKER, candles, index, MIN_BARS, CYCLE_ID)
                assertNotEquals(StrategyAction.HOLD, baseline, "Фикстура должна давать не-HOLD baseline на баре $index")
                stubChallenge(challengeOf(), window, baseline)
                stubFinal(ArbitratorAgent.Final(StrategyAction.BUY, BigDecimal.ZERO, 0.70, "ok"), window, baseline)
            }
            // Прореженный бар тоже обязан быть не-HOLD, иначе тест прошёл бы не из-за
            // sampleEvery, а из-за раннего выхода по HOLD в детерминированной стратегии.
            assertNotEquals(
                StrategyAction.HOLD,
                DeterministicBacktestSignalGenerator().signal(TICKER, candles, sampledIndex, MIN_BARS, CYCLE_ID),
                "Прореженный бар тоже должен давать не-HOLD baseline",
            )

            val shared = generator()
            listOf(lastIndex, sampledIndex, lastIndex - 2).forEach { index ->
                shared.signal(TICKER, candles, index, MIN_BARS, CYCLE_ID)
            }

            assertEquals(
                2.0,
                counterSum("bt_llm_veto_candidates_total"),
                "При sampleEvery=2 из трёх кандидатов проверяются два",
            )
            assertEquals(
                2.0,
                counterSum("bt_llm_veto_allowed_total"),
                "Прореженный кандидат не должен давать ни вердикта, ни метрики решения",
            )
        }

    // ========== Helpers ==========

    /**
     * Сумма всех счётчиков реестра по имени.
     *
     * `meterRegistry.counter(name)` ищет meter **без тегов** и для тегированных
     * счётчиков возвращает пустой, поэтому контроль двойного счёта удобнее вести
     * суммой по всем наборам тегов одного имени.
     */
    private fun counterSum(name: String): Double =
        meterRegistry
            .meters
            .filterIsInstance<io.micrometer.core.instrument.Counter>()
            .filter { it.id.name == name }
            .sumOf { it.count() }

    private fun generator() =
        AgentVetoBacktestSignalGenerator(
            contrarianAgent = contrarianAgent,
            arbitratorAgent = arbitratorAgent,
            backtestConfig = backtestConfig,
            meterRegistry = meterRegistry,
        )

    private fun challengeOf(
        llmAvailable: Boolean = true,
        riskLevel: String = "LOW",
    ) = ContrarianAgent.ChallengeReport(
        isValid = true,
        riskLevel = riskLevel,
        critique = "ok",
        signalStrength = 0.70,
        llmAvailable = llmAvailable,
    )

    /** Мок challenge с точными значениями, которые [LlmVeto.decide] передаёт агенту. */
    private suspend fun stubChallenge(
        report: ContrarianAgent.ChallengeReport,
        window: List<Candle>,
        action: StrategyAction,
    ) {
        `when`(
            contrarianAgent.challenge(
                draft = draftOf(window, action),
                tech = techOf(window),
                fund = fundOf(),
                snapshot = snapshotOf(window),
                cycleId = CYCLE_ID,
                version = PromptRegistry.DEFAULT_VERSION,
                temperature = 0.0,
                cacheNamespace = CACHE_NAMESPACE,
                techDelta = null,
                bypassCache = true,
            ),
        ).thenReturn(report)
    }

    /** Мок adjudicate с точными значениями, которые [LlmVeto.adjudicate] передаёт агенту. */
    private suspend fun stubFinal(
        final: ArbitratorAgent.Final,
        window: List<Candle>,
        action: StrategyAction,
    ) {
        `when`(
            arbitratorAgent.adjudicate(
                draft = draftOf(window, action),
                challenge = challengeOf(),
                tech = techOf(window),
                fund = fundOf(),
                snapshot = snapshotOf(window),
                cycleId = CYCLE_ID,
                contextPrompt = null,
                adaptiveConfidence = 0.0,
                version = PromptRegistry.DEFAULT_VERSION,
                bypassCache = true,
                temperature = 0.0,
                cacheNamespace = CACHE_NAMESPACE,
            ),
        ).thenReturn(final)
    }

    private fun lastCandle(window: List<Candle>) = window.last()

    private fun snapshotOf(window: List<Candle>) =
        MarketSnapshot(
            ticker = TICKER,
            currentPrice = lastCandle(window).closePrice,
            volume = lastCandle(window).volume,
            timestamp = lastCandle(window).time.atZone(ZoneId.systemDefault()).toInstant(),
        )

    private fun draftOf(
        window: List<Candle>,
        action: StrategyAction,
    ) = StrategyAgent.Draft(
        action = action,
        targetPrice = lastCandle(window).closePrice,
        signalStrength = DEFAULT_STRENGTH,
        reasoning = LlmVeto.DRAFT_REASONING,
    )

    private fun techOf(window: List<Candle>): TechnicalReport {
        val ind =
            IndicatorCalculator
                .calculate(window)
                ?: error("Индикаторы по фикстуре не рассчитались")
        return TechnicalReport(
            trend = ind.trend,
            rsi = ind.rsi,
            atr = ind.atr,
            macd = ind.macdHistogram,
            bbUpper = ind.bbUpper,
            bbLower = ind.bbLower,
            conclusion = ind.conclusion,
            signalStrength = DEFAULT_STRENGTH,
            reasoning = LlmVeto.TECH_REASONING,
        )
    }

    private fun fundOf() =
        FundamentalReport(
            conclusion = "NEUTRAL",
            signalStrength = 0.0,
            reasoning = LlmVeto.FUND_REASONING,
        )

    /**
     * Фикстура: длинное снижение (RSI < 30) + слабый отскок в конце (MACD-гистограмма > 0)
     * ⇒ детерминированный baseline даёт не-HOLD сигнал.
     */
    private fun buySetupCandles(): List<Candle> {
        val prices = mutableListOf<BigDecimal>()
        var p = BigDecimal("100.00")
        repeat(31) {
            prices += p
            p = p.multiply(BigDecimal("0.985"))
        }
        // Отскок: EMA12 догоняет EMA26 ⇒ macdHistogram > 0, RSI остаётся низким.
        repeat(9) {
            prices += p
            p = p.multiply(BigDecimal("1.004"))
        }
        return prices.mapIndexed { i, price -> candleAt(i, price) }
    }

    private fun flatCandles(count: Int): List<Candle> {
        val price = BigDecimal("50.00")
        return (0 until count).map { i -> candleAt(i, price) }
    }

    private fun candleAt(
        i: Int,
        price: BigDecimal,
    ) = Candle(
        ticker = TICKER,
        time = BASE_TIME.plusMinutes(i.toLong() * 10),
        openPrice = price,
        highPrice = price.multiply(BigDecimal("1.002")),
        lowPrice = price.multiply(BigDecimal("0.998")),
        closePrice = price,
        volume = 1_000L,
        timeframe = "MINUTE_10",
    )

    private companion object {
        const val TICKER = "CNYRUBF"
        const val CYCLE_ID = "test-cycle"
        const val MIN_BARS = 20
        const val CACHE_NAMESPACE = "backtest-veto"
        const val DEFAULT_STRENGTH = 0.5
        val BASE_TIME: LocalDateTime = LocalDateTime.of(2026, 1, 5, 10, 0)
    }
}
