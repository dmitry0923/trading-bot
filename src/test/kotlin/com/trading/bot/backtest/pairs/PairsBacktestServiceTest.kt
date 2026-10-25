package com.trading.bot.backtest.pairs

import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.model.entity.Candle
import com.trading.bot.repository.CandleRepository
import com.trading.bot.repository.FundingHistoryRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
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
import kotlin.random.Random

/**
 * Unit-тесты сервиса research-прогона market-neutral пары (`docs/25`): inner-join ног
 * по времени, IS/OOS-отрезки, walk-forward и маппинг DTO. Свечи и funding приходят из
 * mock-репозиториев (suspend-функции → custom answer вместо matcher-стабов), данные
 * синтетические, БД/сеть не задействованы.
 */
class PairsBacktestServiceTest {
    private val legA = "CNYRUBF"
    private val legB = "USDRUBF"
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

    /**
     * Пульсирующий спред (детерминированный `Random(7)`): A = exp(logB + spread),
     * B = exp(logB). Даёт сделки на возврате спреда к нулю.
     */
    private fun pulsingSeries(bars: Int = 400): Map<String, List<Candle>> {
        val rnd = Random(7)
        val logB = DoubleArray(bars) { 2.4 }
        for (i in 1 until bars) logB[i] = logB[i - 1] + (rnd.nextDouble() - 0.5) * 0.002
        val spread =
            DoubleArray(bars) { i ->
                val t = i % 40
                when {
                    t in 0..5 -> 0.1 * (t / 5.0)
                    t in 6..15 -> 0.1 * ((15 - t) / 10.0)
                    else -> 0.0
                }
            }
        val seriesA = (0 until bars).map { candle(legA, it, exp(logB[it] + spread[it])) }
        val seriesB = (0 until bars).map { candle(legB, it, exp(logB[it])) }
        return mapOf(legA to seriesA, legB to seriesB)
    }

    private fun fundingMap(value: Double): Map<LocalDate, BigDecimal> {
        val firstDay = start.toLocalDate().minusDays(1)
        return (0..8).associate { firstDay.plusDays(it.toLong()) to bd(value) }
    }

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

    private fun config(useFundingHistory: Boolean): PairsBacktestConfig =
        PairsBacktestConfig().also { cfg ->
            cfg.legA = legA
            cfg.legB = legB
            cfg.timeframe = timeframe
            cfg.lookbackBars = 20
            cfg.entryZ = 2.0
            cfg.exitZ = 0.5
            cfg.stopZ = 6.0
            cfg.maxHoldBars = 0
            cfg.capitalSlice = 0.5
            cfg.initialCapital = 100_000.0
            cfg.oosFraction = 0.30
            cfg.useFundingHistory = useFundingHistory
        }

    private fun service(
        candles: Map<String, List<Candle>> = pulsingSeries(),
        useFundingHistory: Boolean = true,
    ): PairsBacktestService =
        PairsBacktestService(
            candleRepository = candleRepository(candles),
            instrumentsConfig = InstrumentsConfig(),
            fundingHistoryRepository = fundingRepository(mapOf(legA to fundingMap(1.5), legB to fundingMap(0.5))),
            config = config(useFundingHistory),
        )

    @Test
    fun `run reports full IS and OOS metrics with trade log`() =
        runBlocking {
            val response = service().run(days = 30)

            assertEquals(legA, response.legA)
            assertEquals(legB, response.legB)
            assertEquals(timeframe, response.timeframe)
            assertEquals(400, response.bars)
            assertEquals(100_000.0, response.initialCapital)
            assertTrue(response.trades.isNotEmpty(), "pulsing spread must produce trades")
            assertTrue(response.trades.first().containsKey("closeReason"))
            assertTrue(response.full.containsKey("totalReturn"))
            assertTrue(response.inSample.containsKey("totalTrades"))
            assertTrue(response.outOfSample.containsKey("profitFactor"))
            assertTrue(response.verdict.isNotBlank(), "verdict=${response.verdict}")
            assertTrue(response.meanBeta.isFinite())
            assertTrue(response.spreadReturnCorrelation.isFinite())
        }

    @Test
    fun `run honours query overrides and works without funding history`() =
        runBlocking {
            val response =
                service(useFundingHistory = false).run(
                    legA = legA,
                    legB = legB,
                    timeframe = timeframe,
                    days = 30,
                    lookbackBars = 30,
                    entryZ = 1.5,
                    exitZ = 0.4,
                    stopZ = 5.0,
                    maxHoldBars = 25,
                    oosFraction = 0.40,
                    capitalSlice = 0.3,
                    initialCapital = 150_000.0,
                )

            assertEquals(150_000.0, response.initialCapital)
            assertEquals(400, response.bars)
            assertTrue(response.trades.isNotEmpty(), "overrides must still trade")
        }

    @Test
    fun `run rejects an instrument missing from the catalogue`() {
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { service().run(legA = "NOPE", legB = legB, days = 30) }
            }
        assertTrue(
            error.message.orEmpty().contains("Unknown instrument for pairs backtest"),
            "message=${error.message}",
        )
    }

    @Test
    fun `run yields empty metrics when legs do not overlap in time`() =
        runBlocking {
            val shifted =
                pulsingSeries().toMutableMap().also { candles ->
                    candles[legB] = candles.getValue(legB).map { it.copy(time = it.time.plusDays(30)) }
                }

            val response = service(candles = shifted).run(days = 30)

            assertEquals(0, response.bars)
            assertTrue(response.trades.isEmpty())
            assertEquals(0, response.full["totalTrades"])
            assertTrue(response.verdict.startsWith("INCONCLUSIVE"), "verdict=${response.verdict}")
        }

    @Test
    fun `validate returns walk forward folds`() =
        runBlocking {
            val response = service(candles = pulsingSeries(500)).validate(days = 30, folds = 3)

            assertEquals(500, response.bars)
            assertEquals(3, response.folds)
            assertEquals(3, response.foldResults.size)
            assertTrue(response.aggregate.containsKey("totalTrades"))
            assertTrue(response.consistency in 0.0..1.0)
            assertTrue(response.verdict.isNotBlank(), "verdict=${response.verdict}")
        }

    @Test
    fun `validate reports inconclusive when history is too short for folds`() =
        runBlocking {
            val response = service(candles = pulsingSeries(50)).validate(days = 30, folds = 3)

            assertEquals(0, response.folds)
            assertTrue(response.foldResults.isEmpty())
            assertTrue(response.verdict.contains("INCONCLUSIVE"), "verdict=${response.verdict}")
            assertEquals(0.0, response.consistency, 1e-9)
            assertTrue(!response.robust)
        }
}
