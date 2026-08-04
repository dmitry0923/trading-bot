package com.trading.bot.service

import com.trading.bot.config.RiskConfig
import com.trading.bot.model.TradeStats
import com.trading.bot.repository.PositionRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal

/**
 * Критерий Келли в AdaptiveRiskService: применение доли Kelly (Half/Quarter),
 * cap 50% и учёт количества сделок.
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
}
