package com.trading.bot.service

import com.trading.bot.config.RiskConfig
import com.trading.bot.model.Position
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.TradeStats
import com.trading.bot.repository.PositionRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import java.math.BigDecimal

/**
 * Критерий Келли в AdaptiveRiskService: применение доли Kelly (Half/Quarter),
 * cap 50%, volatility targeting (ATR%) и drawdown degradation.
 */
class AdaptiveRiskServiceKellyTest {
    private val riskConfig = RiskConfig()
    private val tradeAnalysis = Mockito.mock(TradeAnalysisService::class.java)
    private val positionRepo = Mockito.mock(PositionRepository::class.java)
    private val candleCache = Mockito.mock(CandleCacheService::class.java)
    private val meterRegistry = SimpleMeterRegistry()

    private val service = AdaptiveRiskService(riskConfig, tradeAnalysis, positionRepo, candleCache, meterRegistry)

    private fun stats(
        winRate: Double,
        avgWin: BigDecimal,
        avgLoss: BigDecimal,
    ): TradeStats =
        TradeStats(
            ticker = "SBER",
            totalTrades = 20,
            winningTrades = (winRate * 20).toInt(),
            losingTrades = 20 - (winRate * 20).toInt(),
            winRate = winRate,
            avgWin = avgWin,
            avgLoss = avgLoss,
            profitFactor = 1.5,
            maxConsecutiveLosses = 1,
            avgHoldTimeMinutes = 120,
            slHitRate = 0.3,
            tpHitRate = 0.4,
            strategyCloseRate = 0.3,
            bestEntryHour = 11,
            worstEntryHour = 17,
            blindSpots = emptyList(),
        )

    private fun stubStats(map: Map<String, TradeStats>) {
        runBlocking {
            Mockito.`when`(tradeAnalysis.analyzeLastNDays(30)).thenReturn(map)
        }
    }

    private fun stubClosedPositions(list: List<Position>) {
        runBlocking {
            Mockito.`when`(positionRepo.findClosedSince(any())).thenReturn(list)
        }
    }

    @BeforeEach
    fun stubNoConsecutiveLosses() {
        stubClosedPositions(emptyList())
    }

    @Test
    fun `half kelly reduces full kelly by exactly half`() =
        runBlocking {
            // w=0.6, r=2.0 -> kelly=(0.6*2-0.4)/2 = 0.4 (Full)
            val s = stats(winRate = 0.6, avgWin = BigDecimal("200"), avgLoss = BigDecimal("100"))
            stubStats(mapOf("SBER" to s))

            riskConfig.kellyFraction = 1.0
            val full = service.calculateOptimalPositionSize("SBER")

            riskConfig.kellyFraction = 0.5
            val half = service.calculateOptimalPositionSize("SBER")

            assertEquals(full.multiply(BigDecimal("0.5")), half)
        }

    @Test
    fun `kelly is capped at 50 percent of max position`() =
        runBlocking {
            // Экстремально выгодная статистика -> kelly > 1 -> cap 0.50
            val s = stats(winRate = 0.9, avgWin = BigDecimal("1000"), avgLoss = BigDecimal("100"))
            stubStats(mapOf("SBER" to s))
            riskConfig.kellyFraction = 1.0

            val size = service.calculateOptimalPositionSize("SBER")
            val cap = riskConfig.maxPositionRub.multiply(BigDecimal("0.50"))
            assertEquals(0, cap.compareTo(size))
        }

    @Test
    fun `no trades falls back to max position`() =
        runBlocking {
            stubStats(emptyMap())
            riskConfig.kellyFraction = 0.5
            val size = service.calculateOptimalPositionSize("SBER")
            assertEquals(riskConfig.maxPositionRub, size)
        }

    @Test
    fun `volatility targeting reduces size for high atr`() =
        runBlocking {
            // w=0.6, r=2.0 -> kelly=0.4 -> full kelly size = 0.4 * 50000 = 20000
            val s = stats(winRate = 0.6, avgWin = BigDecimal("200"), avgLoss = BigDecimal("100"))
            stubStats(mapOf("SBER" to s))
            riskConfig.kellyFraction = 1.0

            val lowVol = service.calculateOptimalPositionSize("SBER", atr = BigDecimal("2"), currentPrice = BigDecimal("100"))
            val highVol = service.calculateOptimalPositionSize("SBER", atr = BigDecimal("10"), currentPrice = BigDecimal("100"))
            val extremeVol = service.calculateOptimalPositionSize("SBER", atr = BigDecimal("20"), currentPrice = BigDecimal("100"))

            // ATR 2% -> mult=2.0, ATR 10% -> mult=0.4, ATR 20% -> mult=0.25 (floor)
            assertEquals(40000.0, lowVol.toDouble(), 2.0)
            assertEquals(8000.0, highVol.toDouble(), 2.0)
            assertEquals(5000.0, extremeVol.toDouble(), 2.0)
        }

    @Test
    fun `drawdown recovery halves position size`() =
        runBlocking {
            val s = stats(winRate = 0.6, avgWin = BigDecimal("200"), avgLoss = BigDecimal("100"))
            stubStats(mapOf("SBER" to s))
            riskConfig.kellyFraction = 1.0

            val consecutiveLosses =
                (1..3).map {
                    Position(
                        ticker = "SBER",
                        direction = com.trading.bot.model.PositionDirection.LONG,
                        quantity = 1,
                        entryPrice = BigDecimal("100"),
                        pnl = BigDecimal("-100"),
                        status = PositionStatus.CLOSED,
                    )
                }
            stubClosedPositions(consecutiveLosses)

            riskConfig.kellyDrawdownReduction = 0.5
            val size = service.calculateOptimalPositionSize("SBER")
            // full kelly = 20000 -> drawdown reduction *0.5
            assertEquals(10000.0, size.toDouble(), 2.0)
        }

    @Test
    fun `quarter kelly is default`() =
        runBlocking {
            val s = stats(winRate = 0.6, avgWin = BigDecimal("200"), avgLoss = BigDecimal("100"))
            stubStats(mapOf("SBER" to s))

            assertEquals(0.25, riskConfig.kellyFraction)
            val size = service.calculateOptimalPositionSize("SBER")
            // full kelly 0.4 * 0.25 = 0.1 -> 5000
            assertTrue(size <= riskConfig.maxPositionRub.multiply(BigDecimal("0.10")).add(BigDecimal.ONE))
        }
}
