package com.trading.bot.backtest

import com.trading.bot.model.Candle
import com.trading.bot.repository.CandleRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.r2dbc.core.DatabaseClient
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Детерминированный бэктест на РЕАЛЬНЫХ данных MOEX ISS.
 *
 * Фикстура `moex_sber_minute10.csv` (10-минутные свечи SBER, ~10k баров) сгенерирована
 * скриптом `scripts/fetch_moex_fixture.ps1` из ISS API и закоммичена в репозиторий,
 * поэтому тест:
 *  - не обращается к сети (нет внешних зависимостей в CI);
 *  - использует реальные цены/объёмы, а не синтетику.
 *
 * Регенерация фикстуры: `./scripts/fetch_moex_fixture.ps1`
 */
class RealDataBacktestFixtureTest {
    private val engine =
        BacktestEngine(
            CandleRepository(Mockito.mock(DatabaseClient::class.java)),
        )

    private fun loadCandles(): List<Candle> {
        val stream = checkNotNull(javaClass.classLoader.getResourceAsStream("fixtures/moex_sber_minute10.csv"))
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        return stream
            .bufferedReader()
            .useLines { lines ->
                lines
                    .drop(1)
                    .filter { it.isNotBlank() }
                    .map { line ->
                        val p = line.split(",")
                        Candle(
                            ticker = "SBER",
                            timeframe = "MINUTE_10",
                            time = LocalDateTime.parse(p[0], formatter),
                            openPrice = BigDecimal(p[1]),
                            highPrice = BigDecimal(p[2]),
                            lowPrice = BigDecimal(p[3]),
                            closePrice = BigDecimal(p[4]),
                            volume = p[5].toLong(),
                        )
                    }.toList()
            }
    }

    @Test
    fun `real MOEX candles are correctly parsed`() {
        val candles = loadCandles()
        assertTrue(candles.size >= 1000, "expected >= 1000 real candles, got ${candles.size}")
        val sorted = candles.sortedBy { it.time }
        assertEquals(candles, sorted, "fixture must already be sorted by time")
        assertTrue(candles.all { it.highPrice >= it.lowPrice }, "high must be >= low for every candle")
        assertTrue(candles.all { it.closePrice > BigDecimal.ZERO }, "prices must be positive")
    }

    @Test
    fun `backtest on real MOEX data produces valid results`() {
        val candles = loadCandles()
        val result = engine.simulate("SBER", candles)

        assertTrue(result.equityCurve.isNotEmpty(), "equityCurve must not be empty on real data")
        assertTrue(result.totalTrades > 0, "expected trades on 10k real candles, got ${result.totalTrades}")
        assertTrue(result.sharpeRatio.isFinite(), "sharpe must be finite")
        assertTrue(result.profitFactor >= 0.0)
        assertTrue(result.totalReturn.isFinite())
        assertTrue(result.winRate in 0.0..1.0)
    }
}
