package com.trading.bot.service

import com.trading.bot.config.RiskConfig
import com.trading.bot.domain.risk.MarketRegime
import com.trading.bot.domain.risk.MarketRegimeProvider
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.dto.TradeStats
import com.trading.bot.model.entity.Position
import com.trading.bot.repository.AgentLogRepository
import com.trading.bot.repository.PositionRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import java.math.BigDecimal

/**
 * Онлайн-калибровка порога уверенности (roadmap 13.11.8): по закрытым сделкам
 * тикера и силе сигнала стратега на входе подбирается порог, при недостатке
 * данных — fallback на правила по win rate.
 */
class AdaptiveRiskServiceConfidenceTest {
    private val riskConfig = RiskConfig()
    private val tradeAnalysis = Mockito.mock(TradeAnalysisService::class.java)
    private val positionRepo = Mockito.mock(PositionRepository::class.java)
    private val candleCache = Mockito.mock(CandleCacheService::class.java)
    private val drawdownProtection = Mockito.mock(DrawdownProtectionService::class.java)
    private val meterRegistry = SimpleMeterRegistry()
    private val correlationProvider = Mockito.mock(CorrelationMatrixProvider::class.java)
    private val marketRegimeProvider: MarketRegimeProvider = { MarketRegime.NORMAL }
    private val aumProvider = Mockito.mock(AumProvider::class.java)
    private val agentLogRepo = Mockito.mock(AgentLogRepository::class.java)

    private val service =
        AdaptiveRiskService(
            riskConfig,
            tradeAnalysis,
            positionRepo,
            candleCache,
            drawdownProtection,
            meterRegistry,
            correlationProvider,
            marketRegimeProvider,
            aumProvider,
            agentLogRepo,
        )

    private fun closedPos(
        cycleId: String,
        win: Boolean,
    ): Position =
        Position(
            ticker = "SBER",
            direction = PositionDirection.LONG,
            quantity = 1,
            entryPrice = BigDecimal("100"),
            pnl = if (win) BigDecimal("50") else BigDecimal("-50"),
            status = PositionStatus.CLOSED,
            cycleId = cycleId,
        )

    private fun stats(winRate: Double): TradeStats =
        TradeStats(
            ticker = "SBER",
            totalTrades = 20,
            winningTrades = (winRate * 20).toInt(),
            losingTrades = 20 - (winRate * 20).toInt(),
            winRate = winRate,
            avgWin = BigDecimal("125"),
            avgLoss = BigDecimal("100"),
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

    private suspend fun stubClosed(positions: List<Position>) {
        Mockito.`when`(positionRepo.findClosedByTickerSince(any(), any())).thenReturn(positions)
    }

    private suspend fun stubSignalStrengths(map: Map<String, Double>) {
        Mockito.`when`(agentLogRepo.findStrategySignalStrengthByCycleIds(eq("SBER"), any())).thenReturn(map)
    }

    private suspend fun stubStats(winRate: Double?) {
        val map = if (winRate == null) emptyMap() else mapOf("SBER" to stats(winRate))
        Mockito.`when`(tradeAnalysis.analyzeLastNDays(14)).thenReturn(map)
    }

    private fun fallbackCounter(): Double = meterRegistry.counter("adaptive.confidence_fallback", "ticker", "SBER").count()

    @Test
    fun `calibration lowers threshold when high signal strength trades win`() =
        runBlocking {
            riskConfig.confidenceCalibrationMinTrades = 5
            stubClosed(
                listOf(
                    closedPos("c1", true),
                    closedPos("c2", true),
                    closedPos("c3", true),
                    closedPos("c4", false),
                    closedPos("c5", true),
                    closedPos("c6", false),
                    closedPos("c7", false),
                    closedPos("c8", false),
                ),
            )
            stubSignalStrengths(
                mapOf(
                    "c1" to 0.85,
                    "c2" to 0.85,
                    "c3" to 0.80,
                    "c4" to 0.80,
                    "c5" to 0.60,
                    "c6" to 0.60,
                    "c7" to 0.50,
                    "c8" to 0.50,
                ),
            )

            val threshold = service.getAdaptiveConfidenceThreshold("SBER")

            // c=0.60: 4/6 = 0.667; c=0.55: 4/6; c=0.50: 4/8 = 0.5 < 0.55 -> 0.55.
            assertEquals(0.55, threshold, 1e-9)
            assertEquals(
                1.0,
                meterRegistry.counter("adaptive.confidence_calibrated", "ticker", "SBER").count(),
                1e-9,
            )
            assertEquals(0.0, fallbackCounter(), 1e-9)
            val gauge = meterRegistry.find("adaptive.confidence_threshold").tag("ticker", "SBER").gauge()
            assertEquals(0.55, gauge!!.value(), 1e-9)
        }

    @Test
    fun `calibration disabled falls back to win rate rules`() =
        runBlocking {
            riskConfig.confidenceCalibrationEnabled = false
            stubStats(0.30)

            val threshold = service.getAdaptiveConfidenceThreshold("SBER")

            assertEquals(0.80, threshold, 1e-9)
            assertEquals(1.0, fallbackCounter(), 1e-9)
        }

    @Test
    fun `no closed trades falls back to neutral rule threshold`() =
        runBlocking {
            stubClosed(emptyList())
            stubStats(null)

            val threshold = service.getAdaptiveConfidenceThreshold("SBER")

            assertEquals(0.60, threshold, 1e-9)
            assertEquals(1.0, fallbackCounter(), 1e-9)
        }

    @Test
    fun `too few closed trades falls back to rules`() =
        runBlocking {
            riskConfig.confidenceCalibrationMinTrades = 5
            stubClosed(listOf(closedPos("c1", true), closedPos("c2", false), closedPos("c3", true)))
            stubStats(0.70)

            val threshold = service.getAdaptiveConfidenceThreshold("SBER")

            assertEquals(0.55, threshold, 1e-9)
            assertEquals(1.0, fallbackCounter(), 1e-9)
        }

    @Test
    fun `positions without strategy signal strength are excluded from calibration`() {
        runBlocking {
            riskConfig.confidenceCalibrationMinTrades = 5
            stubClosed(
                listOf(
                    closedPos("c1", true),
                    closedPos("c2", true),
                    closedPos("c3", true),
                    closedPos("c4", false),
                    closedPos("c5", true),
                    closedPos("c6", false),
                ),
            )
            stubSignalStrengths(mapOf("c1" to 0.85, "c2" to 0.85, "c3" to 0.80, "c4" to 0.80))
            stubStats(0.50)

            val threshold = service.getAdaptiveConfidenceThreshold("SBER")

            // Только 4 сделки имеют лог стратега < minTrades 5 -> fallback (winRate 0.5 -> 0.60).
            assertEquals(0.60, threshold, 1e-9)
            Mockito.verify(agentLogRepo).findStrategySignalStrengthByCycleIds(eq("SBER"), eq(listOf("c1", "c2", "c3", "c4", "c5", "c6")))
        }
    }

    @Test
    fun `positions without pnl are excluded and repo is not queried`() {
        runBlocking {
            riskConfig.confidenceCalibrationMinTrades = 5
            stubClosed(
                listOf(
                    closedPos("c1", true),
                    closedPos("c2", false),
                    closedPos("c3", true),
                    closedPos("c4", false),
                    closedPos("c5", true).also { it.pnl = null },
                    closedPos("c6", false).also { it.pnl = null },
                ),
            )
            stubStats(0.50)

            val threshold = service.getAdaptiveConfidenceThreshold("SBER")

            // Только 4 сделки с pnl < minTrades 5 -> fallback без запроса agent_logs.
            assertEquals(0.60, threshold, 1e-9)
            Mockito.verify(agentLogRepo, Mockito.never()).findStrategySignalStrengthByCycleIds(eq("SBER"), any())
        }
    }

    @Test
    fun `diagnose reports insufficient-closed-trades fallback for empty futures pipeline`() =
        runBlocking {
            riskConfig.confidenceCalibrationMinTrades = 5
            stubClosed(
                listOf(
                    closedPos("c1", true),
                    closedPos("c2", false),
                    closedPos("c3", true),
                ),
            )
            stubStats(null)

            val diag = service.diagnoseConfidenceCalibration("SBER")

            assertEquals(AdaptiveRiskService.ConfidenceSource.FALLBACK, diag.source)
            assertEquals("insufficient-closed-trades", diag.reason)
            assertEquals(0.60, diag.threshold, 1e-9)
            assertEquals(3, diag.closedTrades)
            assertEquals(0, diag.scoredTrades)
        }

    @Test
    fun `diagnose reports calibrated source with sample stats`() =
        runBlocking {
            riskConfig.confidenceCalibrationMinTrades = 5
            stubClosed(
                listOf(
                    closedPos("c1", true),
                    closedPos("c2", true),
                    closedPos("c3", true),
                    closedPos("c4", false),
                    closedPos("c5", true),
                    closedPos("c6", false),
                    closedPos("c7", false),
                    closedPos("c8", false),
                ),
            )
            stubSignalStrengths(
                mapOf(
                    "c1" to 0.85,
                    "c2" to 0.85,
                    "c3" to 0.80,
                    "c4" to 0.80,
                    "c5" to 0.60,
                    "c6" to 0.60,
                    "c7" to 0.50,
                    "c8" to 0.50,
                ),
            )

            val diag = service.diagnoseConfidenceCalibration("SBER")

            assertEquals(AdaptiveRiskService.ConfidenceSource.CALIBRATED, diag.source)
            assertEquals("calibrated", diag.reason)
            assertEquals(8, diag.closedTrades)
            assertEquals(8, diag.scoredTrades)
            assertTrue(diag.sampleSize >= 5)
            assertTrue(diag.winRate >= 0.55)
        }
}
