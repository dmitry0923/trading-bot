package com.trading.bot.backtest.portfolio

import com.trading.bot.application.MoexHolidayCalendar
import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.model.entity.Candle
import com.trading.bot.repository.CandleRepository
import com.trading.bot.repository.FundingHistoryRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.exp

/**
 * Unit-тесты сервиса research-прогона cross-sectional momentum + carry (`docs/26`):
 * загрузка свечей/funding через mock-репозитории, inner-join по времени, IS/OOS-отрезки,
 * walk-forward на dev-части и портфельный deployment-gate.
 *
 * Данные синтетические и детерминированные (трендовая кросс-секция на 400–800 барах),
 * реальные БД/сеть не используются. `CandleRepository`/`FundingHistoryRepository` —
 * suspend-функции, поэтому mock задан custom answer'ом (matcher-стабы для suspend +
 * non-null параметров в Kotlin нестабильны).
 */
class PortfolioMomentumServiceTest {
    private val universe = listOf("CNYRUBF", "USDRUBF", "EURRUBF")
    private val timeframe = "MINUTE_10"
    private val start: LocalDateTime = historyStart()

    private fun historyStart(): LocalDateTime {
        val now = LocalDateTime.now()
        val weekAgo = now.minusDays(6)
        return weekAgo.withSecond(0).withNano(0)
    }

    private fun bd(value: Double): BigDecimal = BigDecimal.valueOf(value)

    private fun candle(
        ticker: String,
        index: Int,
        price: Double,
    ): Candle =
        Candle(
            ticker = ticker,
            timeframe = timeframe,
            openPrice = bd(price),
            highPrice = bd(price),
            lowPrice = bd(price),
            closePrice = bd(price),
            volume = 1_000L,
            time = start.plusMinutes(10L * index),
        )

    /** Детерминированный ряд: цена = exp(base + drift × i). */
    private fun series(
        ticker: String,
        bars: Int,
        drift: Double,
        base: Double = 2.4,
    ): List<Candle> = (0 until bars).map { candle(ticker, it, exp(base + drift * it)) }

    /** Трендовое кросс-сечение: лидер растёт, замыкающий падает. */
    private fun trendingSeries(bars: Int = 400): Map<String, List<Candle>> =
        mapOf(
            universe[0] to series(universe[0], bars, 0.0008),
            universe[1] to series(universe[1], bars, 0.0),
            universe[2] to series(universe[2], bars, -0.0008),
        )

    private fun fundingSeries(values: Map<String, Double>): Map<String, Map<LocalDate, BigDecimal>> {
        val firstDay = start.toLocalDate().minusDays(1)
        val days = (0..8).map { firstDay.plusDays(it.toLong()) }
        return values.mapValues { (_, value) -> days.associateWith { bd(value) } }
    }

    private fun defaultFunding(): Map<String, Map<LocalDate, BigDecimal>> = fundingSeries(universe.associateWith { 0.5 })

    private fun candleRepository(data: Map<String, List<Candle>>): CandleRepository =
        Mockito.mock(
            CandleRepository::class.java,
            Answer { invocation: InvocationOnMock ->
                data[invocation.arguments[0] as String] ?: emptyList<Candle>()
            },
        )

    private fun fundingRepository(data: Map<String, Map<LocalDate, BigDecimal>>): FundingHistoryRepository =
        Mockito.mock(
            FundingHistoryRepository::class.java,
            Answer { invocation: InvocationOnMock ->
                data[invocation.arguments[0] as String] ?: emptyMap<LocalDate, BigDecimal>()
            },
        )

    private fun config(useFundingHistory: Boolean): PortfolioMomentumConfig =
        PortfolioMomentumConfig().also { cfg ->
            cfg.universe = universe
            cfg.timeframe = timeframe
            cfg.lookbackBars = 60
            cfg.rebalanceBars = 40
            cfg.minUniverse = 3
            cfg.capitalSlice = 0.5
            cfg.initialCapital = 100_000.0
            cfg.oosFraction = 0.30
            cfg.useFundingHistory = useFundingHistory
        }

    private fun service(
        candles: Map<String, List<Candle>> = trendingSeries(),
        funding: Map<String, Map<LocalDate, BigDecimal>> = defaultFunding(),
        useFundingHistory: Boolean = true,
        calendar: MoexHolidayCalendar? = null,
    ): PortfolioMomentumService =
        PortfolioMomentumService(
            candleRepository = candleRepository(candles),
            instrumentsConfig = InstrumentsConfig(),
            fundingHistoryRepository = fundingRepository(funding),
            config = config(useFundingHistory),
            moexHolidayCalendar = calendar,
        )

    @Test
    fun `run splits full history into IS and OOS with verdict`() =
        runBlocking {
            val response = service().run(universe = universe, days = 30)

            assertEquals(universe, response.universe)
            assertEquals(timeframe, response.timeframe)
            assertEquals(400, response.bars)
            assertTrue(response.rebalances > 0, "portfolio must rebalance")
            assertEquals(100_000.0, response.initialCapital)
            assertTrue(response.trades.isNotEmpty(), "expected OOS trades")
            assertTrue(response.full.containsKey("totalReturn"))
            assertTrue(response.inSample.containsKey("totalTrades"))
            assertTrue(response.outOfSample.containsKey("profitFactor"))
            // 4 OOS-сделки на коротком окне → INCONCLUSIVE по выборке.
            assertTrue(response.verdict.startsWith("INCONCLUSIVE"), "verdict=${response.verdict}")
        }

    @Test
    fun `run honours query overrides and carry only scoring`() =
        runBlocking {
            val funding =
                fundingSeries(mapOf(universe[0] to -2.0, universe[1] to 0.0, universe[2] to 2.0))
            val response =
                service(funding = funding).run(
                    universe = universe,
                    timeframe = timeframe,
                    days = 30,
                    lookbackBars = 40,
                    skipBars = 5,
                    rebalanceBars = 20,
                    longCount = 2,
                    shortCount = 1,
                    oosFraction = 0.5,
                    capitalSlice = 0.4,
                    momentumWeight = 0.0,
                    carryWeight = 1.0,
                    carryLookbackDays = 3,
                    minUniverse = 2,
                    initialCapital = 200_000.0,
                    reversalEnabled = true,
                    volScalingEnabled = true,
                    volLookbackBars = 30,
                    volTargetEnabled = true,
                    volTargetAnnualized = 0.05,
                    volTargetBarsPerYear = 20_000.0,
                )

            assertEquals(universe, response.universe)
            assertEquals(400, response.bars)
            assertEquals(200_000.0, response.initialCapital)
            assertTrue(response.trades.isNotEmpty(), "carry-only book must still trade")
        }

    @Test
    fun `inner join keeps only bars present for every leg`() =
        runBlocking {
            val candles =
                trendingSeries().toMutableMap().also { it[universe[2]] = series(universe[2], 300, -0.0008) }

            val response = service(candles = candles).run(universe = universe, days = 30)

            assertEquals(300, response.bars, "bars without the third leg must be skipped")
        }

    @Test
    fun `run falls back to instrument funding when history is disabled`() =
        runBlocking {
            val response =
                service(useFundingHistory = false, calendar = MoexHolidayCalendar())
                    .run(universe = universe, days = 30)

            assertEquals(400, response.bars)
            assertTrue(response.trades.isNotEmpty())
        }

    @Test
    fun `run rejects an instrument missing from the catalogue`() {
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { service().run(universe = listOf("CNYRUBF", "NOPE")) }
            }
        assertTrue(
            error.message.orEmpty().contains("Unknown instrument for portfolio backtest"),
            "message=${error.message}",
        )
    }

    @Test
    fun `run rejects an empty universe`() {
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { service().run(universe = listOf("   ")) }
            }
        assertTrue(error.message.orEmpty().contains("Portfolio universe is empty"), "message=${error.message}")
    }

    @Test
    fun `validate returns walk forward folds on the dev slice`() =
        runBlocking {
            val response =
                service(candles = trendingSeries(500))
                    .validate(universe = universe, days = 30, folds = 3, holdoutFraction = 0.30)

            assertEquals(350, response.bars, "holdout 30% must be cut off before walk-forward")
            assertEquals(3, response.folds)
            assertEquals(3, response.foldResults.size)
            assertEquals(0.30, response.holdoutFraction!!, 1e-9)
            assertTrue(response.aggregate.containsKey("totalTrades"))
            assertTrue(response.verdict.isNotBlank())
        }

    @Test
    fun `validate rejects an out of range holdout fraction`() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                service().validate(universe = universe, days = 30, folds = 2, holdoutFraction = 0.0)
            }
        }
    }

    @Test
    fun `deployment gate consolidates checks with monte carlo`() =
        runBlocking {
            val response =
                service(candles = trendingSeries(800)).deploymentGate(
                    universe = universe,
                    days = 60,
                    folds = 3,
                    holdoutFraction = 0.30,
                    simulations = 40,
                    seed = 7,
                )

            assertEquals(800, response.bars)
            assertEquals(560, response.devBars)
            assertEquals(240, response.holdoutBars)
            assertEquals(3, response.foldsRequested)
            assertEquals(3, response.folds)
            assertEquals(response.folds, response.foldCapacity.size)
            assertEquals(3, response.foldResults.size)
            assertTrue(response.checks.isNotEmpty(), "gate must report its checks")
            assertTrue(
                response.status in setOf("REJECTED", "RESEARCH_ONLY", "PAPER_ALLOWED", "LIVE_ALLOWED"),
                "status=${response.status}",
            )
            assertFalse(response.liveAllowed, "research mode never allows live")
            assertTrue(response.researchMode)
            assertFalse(response.confirmedForProduction)
            assertEquals(response.status == "LIVE_ALLOWED", response.liveAllowed)
            assertNotNull(response.monteCarlo, "holdout equity present → Monte Carlo runs")
            assertTrue(response.devBacktest.containsKey("sharpeRatio"))
            assertTrue(response.aggregate.containsKey("totalTrades"))
            assertTrue(response.holdout.containsKey("totalTrades"))
        }

    @Test
    fun `deployment gate skips monte carlo when holdout has no equity`() =
        runBlocking {
            val response =
                service().deploymentGate(universe = universe, days = 60, folds = 3, holdoutFraction = 0.20)

            assertEquals(80, response.holdoutBars, "80 bars are below the warmup of the engine")
            assertNull(response.monteCarlo, "no holdout equity → no Monte Carlo")
        }

    @Test
    fun `deployment gate reports capacity reason when dev part cannot host folds`() =
        runBlocking {
            val response =
                service().deploymentGate(universe = universe, days = 60, folds = 8, holdoutFraction = 0.60)

            assertEquals(0, response.folds)
            assertEquals(8, response.foldsRequested)
            assertEquals("REJECTED", response.status)
            assertTrue(response.foldCapacity.isEmpty())
            val capacity = response.checks.first { it.key == "fold_capacity" }
            assertFalse(capacity.passed)
            assertTrue(capacity.detail.startsWith("no folds"), "detail=${capacity.detail}")
        }

    @Test
    fun `deployment gate rejects an out of range holdout fraction`() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { service().deploymentGate(universe = universe, days = 60, holdoutFraction = 1.0) }
        }
    }
}
