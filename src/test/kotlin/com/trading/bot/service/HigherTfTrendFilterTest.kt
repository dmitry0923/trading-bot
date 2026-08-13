package com.trading.bot.service

import com.trading.bot.config.MtfConfig
import com.trading.bot.domain.signal.Signal
import com.trading.bot.model.StrategyAction
import com.trading.bot.model.entity.Candle
import com.trading.bot.repository.CandleRepository
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * Unit-тесты multi-timeframe фильтра тренда [HigherTfTrendFilter]
 * (roadmap v2.5).
 *
 * Проверяют: pass-through при выключенном фильтре и HOLD, fail-closed при
 * недостатке свечей старшего ТФ, блокировку входов ПРОТИВ тренда старшего ТФ
 * (BUY при DOWN, SELL при UP), проход при согласованном/SIDEWAYS тренде,
 * принудительное включение из бэктеста (`requireEnabled=false`) и live-обёртку
 * с выборкой свечей из [CandleRepository].
 */
class HigherTfTrendFilterTest {
    private val config = MtfConfig()
    private val candleRepository = Mockito.mock(CandleRepository::class.java)
    private val meterRegistry = SimpleMeterRegistry()

    private val filter = HigherTfTrendFilter(config, candleRepository, meterRegistry)

    @BeforeEach
    fun reset() {
        Mockito.reset(candleRepository)
    }

    @Test
    fun `allows when disabled without touching repository`() {
        config.enabled = false

        assertNull(runBlocking { filter.shouldBlock(signal()) })

        runBlocking {
            Mockito.verify(candleRepository, Mockito.never()).findByTickerAndTimeframeAndTimeBetween(any(), any(), any(), any())
        }
    }

    @Test
    fun `allows non trading action`() {
        config.enabled = true

        assertNull(runBlocking { filter.shouldBlock(signal(StrategyAction.HOLD)) })

        runBlocking {
            Mockito.verify(candleRepository, Mockito.never()).findByTickerAndTimeframeAndTimeBetween(any(), any(), any(), any())
        }
    }

    @Test
    fun `core method default respects enabled flag`() {
        config.enabled = false
        val at = LocalDateTime.of(2026, 8, 12, 20, 0)

        val reason = runBlocking { filter.shouldBlock("SBER", StrategyAction.BUY, downward(240), at) }

        assertNull(reason)
    }

    @Test
    fun `backtest override runs even when disabled`() {
        config.enabled = false
        val at = LocalDateTime.of(2026, 8, 12, 20, 0)

        val reason =
            runBlocking {
                filter.shouldBlock("SBER", StrategyAction.BUY, downward(240), at, requireEnabled = false)
            }

        assertTrue(reason != null)
        assertTrue(reason!!.contains("DOWN opposes BUY"))
        assertTrue(filterResultCount("REJECT") > 0)
    }

    @Test
    fun `blocks fail closed when insufficient higher tf bars`() {
        config.enabled = true
        val at = LocalDateTime.of(2026, 8, 12, 20, 0)

        // 40 десятиминутных свечей = 6 часовых баров < 30 → тренд не вычисляется
        val reason = runBlocking { filter.shouldBlock("SBER", StrategyAction.BUY, downward(40), at) }

        assertTrue(reason != null)
        assertTrue(reason!!.contains("insufficient"))
        assertTrue(filterResultCount("FAIL_CLOSED") > 0)
    }

    @Test
    fun `rejects BUY when higher tf trend is DOWN`() {
        config.enabled = true
        val at = LocalDateTime.of(2026, 8, 12, 20, 0)
        val source = downward(300)

        val buyReason = runBlocking { filter.shouldBlock("SBER", StrategyAction.BUY, source, at) }
        val sellReason = runBlocking { filter.shouldBlock("SBER", StrategyAction.SELL, source, at) }

        assertTrue(buyReason != null)
        assertTrue(buyReason!!.contains("DOWN opposes BUY"))
        assertNull(sellReason)
        assertTrue(filterResultCount("REJECT") > 0)
    }

    @Test
    fun `rejects SELL when higher tf trend is UP`() {
        config.enabled = true
        val at = LocalDateTime.of(2026, 8, 12, 20, 0)
        val source = upward()

        val sellReason = runBlocking { filter.shouldBlock("SBER", StrategyAction.SELL, source, at) }
        val buyReason = runBlocking { filter.shouldBlock("SBER", StrategyAction.BUY, source, at) }

        assertTrue(sellReason != null)
        assertTrue(sellReason!!.contains("UP opposes SELL"))
        assertNull(buyReason)
    }

    @Test
    fun `allows when higher tf trend is sideways`() {
        config.enabled = true
        val at = LocalDateTime.of(2026, 8, 12, 20, 0)
        val source = flat()

        assertNull(runBlocking { filter.shouldBlock("SBER", StrategyAction.BUY, source, at) })
        assertNull(runBlocking { filter.shouldBlock("SBER", StrategyAction.SELL, source, at) })
    }

    @Test
    fun `live wrapper fetches source candles from repository and blocks opposing entry`() {
        config.enabled = true
        val source = downward(300)
        runBlocking {
            Mockito
                .`when`(candleRepository.findByTickerAndTimeframeAndTimeBetween(any(), any(), any(), any()))
                .thenReturn(source)
        }

        val reason = runBlocking { filter.shouldBlock(signal()) }

        assertTrue(reason != null)
        assertTrue(reason!!.contains("DOWN opposes BUY"))
        runBlocking {
            Mockito.verify(candleRepository).findByTickerAndTimeframeAndTimeBetween(eq("SBER"), eq("MINUTE_10"), any(), any())
        }
    }

    @Test
    fun `live wrapper allows aligned entry`() {
        config.enabled = true
        val source = upward()
        runBlocking {
            Mockito
                .`when`(candleRepository.findByTickerAndTimeframeAndTimeBetween(any(), any(), any(), any()))
                .thenReturn(source)
        }

        assertNull(runBlocking { filter.shouldBlock(signal()) })
    }

    private fun signal(action: StrategyAction = StrategyAction.BUY): Signal =
        Signal(
            ticker = "SBER",
            action = action,
            targetPrice = BigDecimal("100"),
            confidence = 0.8,
            reasoning = "test",
            timeframe = "MINUTE_10",
            cycleId = "cycle-1",
        )

    /** Серии десятиминутных свечей, дающие DOWN/UP/SIDEWAYS тренд на часовых барах. */
    private fun downward(bars: Int): List<Candle> = priceSeries(bars) { i -> BigDecimal(1000 - i * 2L) }

    private fun upward(): List<Candle> = priceSeries(300) { i -> BigDecimal(1000 + i * 2L) }

    private fun flat(): List<Candle> = priceSeries(300) { BigDecimal(1000) }

    private fun priceSeries(
        bars: Int,
        priceAt: (Int) -> BigDecimal,
    ): List<Candle> {
        val start = LocalDateTime.of(2026, 8, 1, 10, 0)
        return (0 until bars).map { i ->
            val price = priceAt(i)
            Candle(
                ticker = "SBER",
                timeframe = "MINUTE_10",
                openPrice = price,
                highPrice = price.add(BigDecimal("5")),
                lowPrice = price.subtract(BigDecimal("5")),
                closePrice = price,
                volume = 100L,
                time = start.plusMinutes(i * 10L),
            )
        }
    }

    private fun filterResultCount(result: String): Double =
        meterRegistry
            .counter("mtf.entry.filter", Tags.of("ticker", "SBER", "result", result))
            .count()
}
