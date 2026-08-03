package com.trading.bot.integration

import com.trading.bot.backtest.BacktestEngine
import com.trading.bot.backtest.BacktestResult
import com.trading.bot.client.MoexClient
import com.trading.bot.model.Candle
import com.trading.bot.repository.CandleRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.ActiveProfiles
import java.math.BigDecimal
import java.time.LocalDateTime

@ActiveProfiles("test")
class BacktestIntegrationTest : AbstractTestContainerTest() {

    @Autowired
    private lateinit var backtestEngine: BacktestEngine

    @Autowired
    private lateinit var candleRepository: CandleRepository

    @Autowired
    private lateinit var moexClient: MoexClient

    @BeforeEach
    fun setup() {
        candleRepository.deleteAll()
    }

    @Test
    fun `should run backtest on real PostgreSQL and Redis`() {
        // Load real data from MOEX or generate mock
        val realCandles = loadRealOrMockCandles("SBER")
        candleRepository.saveAll(realCandles)

        val result = backtestEngine.runBacktest("SBER")

        assertNotNull(result)
        assertEquals("SBER", result.ticker)
        assertTrue(result.equityCurve.isNotEmpty(), "Equity curve should not be empty")

        println(formatBacktestReport(result))
    }

    @Test
    fun `should have acceptable risk metrics`() {
        val candles = generateMockCandles("SBER", 500)
        candleRepository.saveAll(candles)

        val result = backtestEngine.runBacktest("SBER")

        assertTrue(result.maxDrawdownPercent < 50.0,
            "Max drawdown ${result.maxDrawdownPercent}% exceeds 50%")
        assertTrue(result.winRate in 0.0..1.0,
            "Win rate ${result.winRate} out of range")
        assertTrue(result.sharpeRatio > -5.0,
            "Sharpe ratio ${result.sharpeRatio} too low")
    }

    @Test
    fun `should load real MOEX data when available`() {
        runBlocking {
            val from = LocalDateTime.now().minusYears(2)
            val to = LocalDateTime.now()
            val realCandles = moexClient.getCandles("SBER", 10, from, to)

            if (realCandles.isNotEmpty()) {
                candleRepository.saveAll(realCandles)
                println("Loaded ${realCandles.size} real candles from MOEX ISS")

                val result = backtestEngine.runBacktest("SBER")
                assertTrue(result.totalTrades > 0, "Should have trades with real data")
                println(formatBacktestReport(result))
            } else {
                println("MOEX ISS unavailable, skipping real data test")
            }
        }
    }

    @Test
    fun `should run multi-ticker backtest`() {
        val tickers = listOf("SBER", "GAZP", "LKOH")
        tickers.forEach { ticker ->
            val candles = generateMockCandles(ticker, 300)
            candleRepository.saveAll(candles)
            val result = backtestEngine.runBacktest(ticker)
            assertNotNull(result)
            println("$ticker: ${result.totalTrades} trades, return=${String.format("%.2f", result.totalReturnPercent)}%")
        }
    }

    private fun loadRealOrMockCandles(ticker: String): List<Candle> {
        return runBlocking {
            val from = LocalDateTime.now().minusYears(2)
            val to = LocalDateTime.now()
            val real = moexClient.getCandles(ticker, 10, from, to)
            if (real.isNotEmpty()) real else generateMockCandles(ticker, 500)
        }
    }

    private fun generateMockCandles(ticker: String, count: Int): List<Candle> {
        val candles = mutableListOf<Candle>()
        var price = 250.0
        val start = LocalDateTime.now().minusDays(count.toLong())

        repeat(count) { i ->
            val change = (Math.random() - 0.48) * 5
            price += change
            price = price.coerceIn(150.0, 400.0)
            val time = start.plusDays(i.toLong())
            candles.add(Candle(
                ticker = ticker,
                timeframe = "MINUTE_10",
                time = time,
                open = BigDecimal(price - 1),
                high = BigDecimal(price + 2),
                low = BigDecimal(price - 2),
                close = BigDecimal(price),
                volume = (1_000_000 + Math.random() * 5_000_000).toLong()
            ))
        }
        return candles
    }

    private fun formatBacktestReport(result: BacktestResult): String {
        return """

            ╔══════════════════════════════════════════════════════╗
            ║           BACKTEST REPORT: ${result.ticker.padEnd(10)}          ║
            ╠══════════════════════════════════════════════════════╣
            ║  Period:     ${result.startDate.toLocalDate()} → ${result.endDate.toLocalDate()}              ║
            ║  Trades:     ${result.totalTrades.toString().padEnd(5)} (Win: ${result.winningTrades}, Loss: ${result.losingTrades})       ║
            ║  Win Rate:   ${(result.winRate * 100).toString().padEnd(5)}%                              ║
            ║  Return:     ${result.totalReturn} ₽ (${String.format("%.2f", result.totalReturnPercent)}%)            ║
            ║  Max DD:     ${result.maxDrawdown} ₽ (${String.format("%.2f", result.maxDrawdownPercent)}%)             ║
            ║  Sharpe:     ${String.format("%.2f", result.sharpeRatio)}                              ║
            ║  P.Factor:   ${String.format("%.2f", result.profitFactor)}                              ║
            ╚══════════════════════════════════════════════════════╝
            """.trimIndent()
    }
}
