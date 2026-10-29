package com.trading.bot.integration

import com.trading.bot.model.entity.Candle
import com.trading.bot.repository.CandleRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * Интеграционные тесты CandleRepository против реальной Postgres.
 *
 * Проверяют point-in-time границу ML-выборки (roadmap 13.22.2, CRIT-1/CRIT-2):
 * [CandleRepository.findByTickerAndTimeframeAndTimeBefore] отдаёт только СТРОГО
 * закрытые к моменту `toExclusive` бары (`time < :toExclusive`), т.е. бар,
 * начавшийся в `toExclusive`, не попадает в ML-признаки (его close был бы lookahead'ом).
 */
@Tag("integration")
class CandleRepositoryIntegrationTest : AbstractTestContainerTest() {
    @Autowired
    lateinit var repo: CandleRepository

    @Test
    fun `timeBefore excludes the bar starting at the boundary and later ones`() {
        val ticker = "INT_CND_EXCL_SBER"
        val timeframe = "MINUTE_10"
        val base =
            LocalDateTime
                .now()
                .withSecond(0)
                .withNano(0)
                .minusMinutes(30)

        runBlocking {
            repo.saveAll(
                (0 until 5).map { i -> candle(ticker, timeframe, base.plusMinutes(10L * i), close = 100.0 + i) },
            )

            val before =
                repo.findByTickerAndTimeframeAndTimeBefore(
                    ticker,
                    timeframe,
                    from = base.minusHours(1),
                    toExclusive = base.plusMinutes(30),
                )

            assertEquals(
                listOf(100.0, 101.0, 102.0),
                before.map { it.closePrice.toDouble() },
                "bars at boundary (10:30) and later must not leak into ML features",
            )
        }
    }

    @Test
    fun `timeBefore lower bound is inclusive and upper bound matches timeBetween`() {
        val ticker = "INT_CND_LOW_SBER"
        val timeframe = "MINUTE_10"
        val base =
            LocalDateTime
                .now()
                .withSecond(0)
                .withNano(0)
                .minusMinutes(30)

        runBlocking {
            repo.saveAll(
                (0 until 5).map { i -> candle(ticker, timeframe, base.plusMinutes(10L * i), close = 100.0 + i) },
            )

            val before =
                repo.findByTickerAndTimeframeAndTimeBefore(
                    ticker,
                    timeframe,
                    from = base,
                    toExclusive = base.plusMinutes(30),
                )
            val between =
                repo.findByTickerAndTimeframeAndTimeBetween(
                    ticker,
                    timeframe,
                    from = base,
                    to = base.plusMinutes(30),
                )

            assertEquals(listOf(100.0, 101.0, 102.0), before.map { it.closePrice.toDouble() })
            assertEquals(listOf(100.0, 101.0, 102.0, 103.0), between.map { it.closePrice.toDouble() })
        }
    }

    @Test
    fun `repeated save of the same bar updates OHLCV instead of freezing the first snapshot`() {
        // Скан-16 P1: `ON CONFLICT DO NOTHING` навсегда фиксировал первый (частичный) снимок
        // незакрытого бара — в research попадал несуществующий бар.
        runBlocking {
            val now = LocalDateTime.now()
            val truncated = now.minusHours(1)
            val time = truncated.withSecond(0).withNano(0)
            repo.saveAll(listOf(candle("SBER", "MINUTE_10", time, close = 100.0)))

            val corrected =
                Candle(
                    ticker = "SBER",
                    timeframe = "MINUTE_10",
                    openPrice = BigDecimal("100"),
                    highPrice = BigDecimal("105"),
                    lowPrice = BigDecimal("99"),
                    closePrice = BigDecimal("104"),
                    volume = 9999L,
                    time = time,
                )
            repo.saveAll(listOf(corrected))

            val stored = repo.findByTickerAndTimeframeAndTimeBetween("SBER", "MINUTE_10", time, time.plusMinutes(1))
            assertEquals(1, stored.size, "повторная запись не должна дублировать бар")
            val bar = stored.single()
            assertEquals(0, BigDecimal("105").compareTo(bar.highPrice))
            assertEquals(0, BigDecimal("104").compareTo(bar.closePrice))
            assertEquals(9999L, bar.volume)
        }
    }

    private fun candle(
        ticker: String,
        timeframe: String,
        time: LocalDateTime,
        close: Double,
    ): Candle =
        Candle(
            ticker = ticker,
            timeframe = timeframe,
            openPrice = BigDecimal(close),
            highPrice = BigDecimal(close),
            lowPrice = BigDecimal(close),
            closePrice = BigDecimal(close),
            volume = 1000L,
            time = time,
        )
}
