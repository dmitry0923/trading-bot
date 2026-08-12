package com.trading.bot.service

import com.trading.bot.config.MlConfig
import com.trading.bot.model.entity.BlindSpotEntity
import com.trading.bot.model.entity.Candle
import com.trading.bot.model.entity.MacroSnapshot
import com.trading.bot.repository.BlindSpotRepository
import com.trading.bot.repository.CandleRepository
import com.trading.bot.repository.MacroSnapshotRepository
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

class MlFeatureResolverTest {
    private val config = MlConfig()
    private val candleRepository = Mockito.mock(CandleRepository::class.java)
    private val blindSpotRepository = Mockito.mock(BlindSpotRepository::class.java)
    private val macroSnapshotRepository = Mockito.mock(MacroSnapshotRepository::class.java)
    private val macroContextService = Mockito.mock(MacroContextService::class.java)

    private val resolver =
        MlFeatureResolver(
            config,
            candleRepository,
            blindSpotRepository,
            macroSnapshotRepository,
            macroContextService,
        )

    @BeforeEach
    fun reset() {
        Mockito.reset(candleRepository, blindSpotRepository, macroSnapshotRepository, macroContextService)
    }

    @Test
    fun `resolves vector from candles snapshot blind spot and strategy info`() {
        config.dataset.timeframe = "MINUTE_10"
        val at = LocalDateTime.of(2026, 2, 1, 14, 0)
        runBlocking {
            Mockito
                .`when`(candleRepository.findByTickerAndTimeframeAndTimeBetween(any<String>(), any<String>(), any(), any()))
                .thenReturn(candles(60, at))
            Mockito
                .`when`(blindSpotRepository.findByIsActiveTrue())
                .thenReturn(
                    listOf(
                        BlindSpotEntity(
                            ticker = "SBER",
                            conditionPattern = "Entry at hour 14 for SBER",
                            lossRate = 0.7,
                            occurrenceCount = 3,
                            recommendation = "avoid",
                        ),
                    ),
                )
            Mockito
                .`when`(macroSnapshotRepository.findBetween(any(), any()))
                .thenReturn(
                    listOf(
                        MacroSnapshot(
                            capturedAt = at.minusMinutes(30),
                            cbrRate = BigDecimal("17.5"),
                            brentPrice = BigDecimal("80"),
                            usdRub = BigDecimal("95"),
                        ),
                    ),
                )
        }

        val vector = runBlocking { resolver.resolve("SBER", at, "BUY", 0.85, "LONG") }

        assertTrue(vector != null)
        assertEquals("BUY", vector!!.strategyAction)
        assertEquals(0.85, vector.strategyConfidence!!, 1e-9)
        assertEquals("LONG", vector.direction)
        assertEquals(17.5, vector.cbrRate, 1e-9)
        assertEquals(95.0, vector.usdRub, 1e-9)
        assertEquals(1, vector.inBlindSpotHour)
        assertEquals(14, vector.hourOfDay)
        assertEquals(0.85f, vector.numericFeatures()[12])
    }

    @Test
    fun `falls back to current macro context when no snapshot`() {
        val at = LocalDateTime.of(2026, 2, 1, 14, 0)
        runBlocking {
            Mockito
                .`when`(candleRepository.findByTickerAndTimeframeAndTimeBetween(any<String>(), any<String>(), any(), any()))
                .thenReturn(candles(60, at))
            Mockito.`when`(blindSpotRepository.findByIsActiveTrue()).thenReturn(emptyList())
            Mockito.`when`(macroSnapshotRepository.findBetween(any(), any())).thenReturn(emptyList())
            Mockito
                .`when`(
                    macroContextService.fetch(),
                ).thenReturn(MacroContextService.MacroContext(BigDecimal("16"), BigDecimal("75"), BigDecimal("90")))
        }

        val vector = runBlocking { resolver.resolve("SBER", at, "SELL", 0.6, "SHORT") }

        assertTrue(vector != null)
        assertEquals(16.0, vector!!.cbrRate, 1e-9)
        assertEquals(0, vector.inBlindSpotHour)
        runBlocking {
            Mockito.verify(macroContextService, Mockito.times(1)).fetch()
        }
    }

    @Test
    fun `returns null when candles insufficient`() {
        val at = LocalDateTime.of(2026, 2, 1, 14, 0)
        runBlocking {
            Mockito
                .`when`(candleRepository.findByTickerAndTimeframeAndTimeBetween(any<String>(), any<String>(), any(), any()))
                .thenReturn(candles(5, at))
            Mockito.`when`(blindSpotRepository.findByIsActiveTrue()).thenReturn(emptyList())
            Mockito.`when`(macroSnapshotRepository.findBetween(any(), any())).thenReturn(emptyList())
            Mockito
                .`when`(
                    macroContextService.fetch(),
                ).thenReturn(MacroContextService.MacroContext(BigDecimal("16"), BigDecimal("75"), BigDecimal("90")))
        }

        val vector = runBlocking { resolver.resolve("SBER", at, "BUY", 0.85, "LONG") }

        assertNull(vector)
        runBlocking {
            Mockito.verify(macroContextService, Mockito.never()).fetch()
        }
    }

    private fun candles(
        count: Int,
        endAt: LocalDateTime,
    ): List<Candle> =
        (0 until count).map { i ->
            val close = 100.0 + 0.5 * i
            Candle(
                ticker = "SBER",
                timeframe = "MINUTE_10",
                openPrice = BigDecimal(close - 0.4),
                highPrice = BigDecimal(close + 0.5),
                lowPrice = BigDecimal(close - 0.5),
                closePrice = BigDecimal(close),
                volume = 1000L,
                time = endAt.minusMinutes(10L * (count - 1 - i)),
            )
        }
}
