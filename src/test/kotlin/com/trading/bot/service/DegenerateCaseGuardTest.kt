package com.trading.bot.service

import com.trading.bot.client.AlorClient
import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.config.RiskConfig
import com.trading.bot.model.dto.MarketSnapshot
import com.trading.bot.model.entity.Candle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * Unit-тесты пре-входного guard'а вырожденных случаев [DegenerateCaseGuard].
 *
 * Покрывают:
 * - WIDE_SPREAD по снэпшоту котировок (fail-closed при отсутствии снэпшота);
 * - PRICE_GAP по свечам кэша;
 * - DEPOSITARY_PAUSE по нулевым объёмам;
 * - NO_CANDLE_DATA при пустом кэше свечей (fail-closed);
 * - порядок проверок (спред → данные → гэп → пауза);
 * - pass-through при выключенном guard и при чистом состоянии;
 * - per-instrument spread/gap thresholds.
 */
class DegenerateCaseGuardTest {
    private val riskConfig = RiskConfig()
    private val instrumentsConfig = InstrumentsConfig()
    private val alorClient = Mockito.mock(AlorClient::class.java)
    private val candleCache = Mockito.mock(CandleCacheService::class.java)
    private val guard = DegenerateCaseGuard(riskConfig, instrumentsConfig, alorClient, candleCache)

    @BeforeEach
    fun setUp() {
        Mockito.reset(alorClient, candleCache)
        runBlocking {
            Mockito
                .`when`(alorClient.getMarketSnapshot(any()))
                .thenReturn(MarketSnapshot(currentPrice = BigDecimal("100"), bid = BigDecimal("99.9"), ask = BigDecimal("100.1")))
            Mockito.`when`(candleCache.getRecentCandles(any(), any(), any())).thenReturn(
                listOf(
                    Candle(
                        ticker = "SBER",
                        timeframe = "MINUTE_10",
                        openPrice = BigDecimal("100"),
                        highPrice = BigDecimal("100"),
                        lowPrice = BigDecimal("100"),
                        closePrice = BigDecimal("100"),
                        volume = 1000L,
                        time = LocalDateTime.of(2026, 1, 1, 10, 0),
                    ),
                    Candle(
                        ticker = "SBER",
                        timeframe = "MINUTE_10",
                        openPrice = BigDecimal("100"),
                        highPrice = BigDecimal("100"),
                        lowPrice = BigDecimal("100"),
                        closePrice = BigDecimal("100"),
                        volume = 1000L,
                        time = LocalDateTime.of(2026, 1, 1, 10, 10),
                    ),
                    Candle(
                        ticker = "SBER",
                        timeframe = "MINUTE_10",
                        openPrice = BigDecimal("100"),
                        highPrice = BigDecimal("100"),
                        lowPrice = BigDecimal("100"),
                        closePrice = BigDecimal("100"),
                        volume = 1000L,
                        time = LocalDateTime.of(2026, 1, 1, 10, 20),
                    ),
                ),
            )
        }
    }

    private fun candles(vararg volumes: Long): List<Candle> =
        volumes.mapIndexed { i, v ->
            Candle(
                ticker = "SBER",
                timeframe = "MINUTE_10",
                openPrice = BigDecimal("100"),
                highPrice = BigDecimal("100"),
                lowPrice = BigDecimal("100"),
                closePrice = BigDecimal("100"),
                volume = v,
                time = LocalDateTime.of(2026, 1, 1, 10, 0).plusMinutes(10L * i),
            )
        }

    @Test
    fun `passes when market is normal`() {
        val reason = runBlocking { guard.blockReason("SBER", "MINUTE_10") }
        assertNull(reason)
    }

    @Test
    fun `wide spread blocks entry`() {
        riskConfig.maxSpreadPercent = BigDecimal("0.2")
        runBlocking {
            Mockito
                .`when`(alorClient.getMarketSnapshot("SBER"))
                .thenReturn(MarketSnapshot(currentPrice = BigDecimal("100"), bid = BigDecimal("99"), ask = BigDecimal("101")))
        }

        val reason = runBlocking { guard.blockReason("SBER", "MINUTE_10") }
        assertEquals("WIDE_SPREAD", reason)
    }

    @Test
    fun `missing snapshot blocks entry fail-closed`() {
        runBlocking {
            Mockito.`when`(alorClient.getMarketSnapshot(any())).thenReturn(null)
        }

        val reason = runBlocking { guard.blockReason("SBER", "MINUTE_10") }
        assertEquals("MARKET_SNAPSHOT_UNAVAILABLE", reason)
    }

    @Test
    fun `price gap blocks entry`() {
        runBlocking {
            Mockito
                .`when`(candleCache.getRecentCandles(any(), any(), any()))
                .thenReturn(
                    listOf(
                        Candle(
                            ticker = "SBER",
                            timeframe = "MINUTE_10",
                            openPrice = BigDecimal("100"),
                            highPrice = BigDecimal("100"),
                            lowPrice = BigDecimal("100"),
                            closePrice = BigDecimal("100"),
                            volume = 1000L,
                            time = LocalDateTime.of(2026, 1, 1, 10, 0),
                        ),
                        Candle(
                            ticker = "SBER",
                            timeframe = "MINUTE_10",
                            openPrice = BigDecimal("100"),
                            highPrice = BigDecimal("100"),
                            lowPrice = BigDecimal("100"),
                            closePrice = BigDecimal("100"),
                            volume = 1000L,
                            time = LocalDateTime.of(2026, 1, 1, 10, 10),
                        ),
                        Candle(
                            ticker = "SBER",
                            timeframe = "MINUTE_10",
                            openPrice = BigDecimal("106"),
                            highPrice = BigDecimal("106"),
                            lowPrice = BigDecimal("106"),
                            closePrice = BigDecimal("106"),
                            volume = 1000L,
                            time = LocalDateTime.of(2026, 1, 1, 10, 20),
                        ),
                    ),
                )
        }

        val reason = runBlocking { guard.blockReason("SBER", "MINUTE_10") }
        assertEquals("PRICE_GAP", reason)
    }

    @Test
    fun `depositary pause blocks entry`() {
        runBlocking {
            Mockito.`when`(candleCache.getRecentCandles(any(), any(), any())).thenReturn(candles(1000L, 0L, 0L, 0L))
        }

        val reason = runBlocking { guard.blockReason("SBER", "MINUTE_10") }
        assertEquals("DEPOSITARY_PAUSE", reason)
    }

    @Test
    fun `insufficient candle data blocks entry fail-closed`() {
        runBlocking {
            Mockito.`when`(candleCache.getRecentCandles(any(), any(), any())).thenReturn(
                listOf(
                    Candle(
                        ticker = "SBER",
                        timeframe = "MINUTE_10",
                        openPrice = BigDecimal("100"),
                        highPrice = BigDecimal("100"),
                        lowPrice = BigDecimal("100"),
                        closePrice = BigDecimal("100"),
                        volume = 1000L,
                        time = LocalDateTime.of(2026, 1, 1, 10, 0),
                    ),
                ),
            )
        }

        val reason = runBlocking { guard.blockReason("SBER", "MINUTE_10") }
        assertEquals("INSUFFICIENT_CANDLE_DATA", reason)
    }

    @Test
    fun `check returns Blocked for missing snapshot`() {
        runBlocking {
            Mockito.`when`(alorClient.getMarketSnapshot(any())).thenReturn(null)
        }

        val result = runBlocking { guard.check("SBER", "MINUTE_10") }
        assertTrue(result is DegenerateCaseGuard.GuardResult.Blocked)
        assertEquals("MARKET_SNAPSHOT_UNAVAILABLE", (result as DegenerateCaseGuard.GuardResult.Blocked).reason)
    }

    @Test
    fun `check returns Allowed when market is normal`() {
        val result = runBlocking { guard.check("SBER", "MINUTE_10") }
        assertTrue(result is DegenerateCaseGuard.GuardResult.Allowed)
    }

    @Test
    fun `spread checked before candles`() {
        riskConfig.maxSpreadPercent = BigDecimal("0.2")
        runBlocking {
            Mockito
                .`when`(alorClient.getMarketSnapshot("SBER"))
                .thenReturn(MarketSnapshot(currentPrice = BigDecimal("100"), bid = BigDecimal("99"), ask = BigDecimal("101")))
            Mockito.`when`(candleCache.getRecentCandles(any(), any(), any())).thenReturn(candles(0L, 0L, 0L, 0L))
        }

        val reason = runBlocking { guard.blockReason("SBER", "MINUTE_10") }
        assertEquals("WIDE_SPREAD", reason)
        runBlocking { Mockito.verify(candleCache, Mockito.never()).getRecentCandles(any(), any(), any()) }
    }

    @Test
    fun `passes through when guard disabled`() {
        riskConfig.degenerateCaseGuardEnabled = false
        runBlocking {
            Mockito
                .`when`(alorClient.getMarketSnapshot("SBER"))
                .thenReturn(MarketSnapshot(currentPrice = BigDecimal("100"), bid = BigDecimal("50"), ask = BigDecimal("100")))
            Mockito.`when`(candleCache.getRecentCandles(any(), any(), any())).thenReturn(candles(0L, 0L, 0L, 0L))
        }

        val reason = runBlocking { guard.blockReason("SBER", "MINUTE_10") }
        assertNull(reason)
        runBlocking { Mockito.verify(alorClient, Mockito.never()).getMarketSnapshot(any()) }
    }

    @Test
    fun `non positive spread threshold disables spread check`() {
        riskConfig.maxSpreadPercent = BigDecimal("0.0")
        runBlocking {
            Mockito
                .`when`(alorClient.getMarketSnapshot("SBER"))
                .thenReturn(MarketSnapshot(currentPrice = BigDecimal("100"), bid = BigDecimal("50"), ask = BigDecimal("100")))
        }

        val reason = runBlocking { guard.blockReason("SBER", "MINUTE_10") }
        assertNull(reason)
    }

    @Test
    fun `per-instrument spread threshold overrides global`() {
        riskConfig.maxSpreadPercent = BigDecimal("5.0")
        instrumentsConfig.instruments =
            mutableListOf(
                InstrumentsConfig.InstrumentSpec(
                    ticker = "CNYRUB_TOM",
                    type = "FX",
                    lotSize = 1000,
                    priceStep = BigDecimal("0.0005"),
                    priceStepCost = BigDecimal("0.5"),
                    go = BigDecimal.ZERO,
                    leverage = BigDecimal("1.0"),
                    baseAsset = "CNY",
                    quoteAsset = "RUB",
                    maxSpreadPercent = BigDecimal("0.2"),
                ),
            )
        runBlocking {
            Mockito
                .`when`(alorClient.getMarketSnapshot("CNYRUB_TOM"))
                .thenReturn(MarketSnapshot(currentPrice = BigDecimal("12.40"), bid = BigDecimal("12.30"), ask = BigDecimal("12.50")))
        }

        val reason = runBlocking { guard.blockReason("CNYRUB_TOM", "MINUTE_10") }
        assertEquals("WIDE_SPREAD", reason)
    }

    @Test
    fun `per-instrument wider spread threshold allows entry`() {
        riskConfig.maxSpreadPercent = BigDecimal("0.2")
        instrumentsConfig.instruments =
            mutableListOf(
                InstrumentsConfig.InstrumentSpec(
                    ticker = "CNYRUB_TOM",
                    type = "FX",
                    lotSize = 1000,
                    priceStep = BigDecimal("0.0005"),
                    priceStepCost = BigDecimal("0.5"),
                    go = BigDecimal.ZERO,
                    leverage = BigDecimal("1.0"),
                    baseAsset = "CNY",
                    quoteAsset = "RUB",
                    maxSpreadPercent = BigDecimal("5.0"),
                ),
            )
        runBlocking {
            Mockito
                .`when`(alorClient.getMarketSnapshot("CNYRUB_TOM"))
                .thenReturn(MarketSnapshot(currentPrice = BigDecimal("12.40"), bid = BigDecimal("12.30"), ask = BigDecimal("12.50")))
        }

        val reason = runBlocking { guard.blockReason("CNYRUB_TOM", "MINUTE_10") }
        assertNull(reason)
    }
}
