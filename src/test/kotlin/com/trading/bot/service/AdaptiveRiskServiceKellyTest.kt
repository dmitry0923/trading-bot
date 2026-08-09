package com.trading.bot.service

import com.trading.bot.config.RiskConfig
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.dto.DrawdownStatus
import com.trading.bot.model.dto.TradeStats
import com.trading.bot.model.entity.Position
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
import java.time.Instant

/**
 * Критерий Келли в AdaptiveRiskService: Wilson-шринкейдж win rate, минимальная
 * выборка, консервативный fallback, cap, volatility targeting по дневной
 * волатильности и непрерывная деградация по глубине просадки.
 */
class AdaptiveRiskServiceKellyTest {
    private val riskConfig = RiskConfig()
    private val tradeAnalysis = Mockito.mock(TradeAnalysisService::class.java)
    private val positionRepo = Mockito.mock(PositionRepository::class.java)
    private val candleCache = Mockito.mock(CandleCacheService::class.java)
    private val drawdownProtection = Mockito.mock(DrawdownProtectionService::class.java)
    private val meterRegistry = SimpleMeterRegistry()
    private val correlationProvider = Mockito.mock(CorrelationMatrixProvider::class.java)

    private val service =
        AdaptiveRiskService(riskConfig, tradeAnalysis, positionRepo, candleCache, drawdownProtection, meterRegistry, correlationProvider)

    private fun stats(
        winRate: Double,
        avgWin: BigDecimal,
        avgLoss: BigDecimal,
        totalTrades: Int = 20,
    ): TradeStats =
        TradeStats(
            ticker = "SBER",
            totalTrades = totalTrades,
            winningTrades = (winRate * totalTrades).toInt(),
            losingTrades = totalTrades - (winRate * totalTrades).toInt(),
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

    private fun stubDrawdown(ddPercent: Double) {
        val aum = riskConfig.maxPositionRub
        Mockito
            .`when`(drawdownProtection.cachedOrNeutral())
            .thenReturn(
                DrawdownStatus(
                    aum = aum,
                    peakAum = aum,
                    drawdownPercent = ddPercent,
                    dailyPnlRub = BigDecimal.ZERO,
                    dailyLimitRub = BigDecimal("5000"),
                    dailyLimitBreached = false,
                    rolling7dPnlRub = BigDecimal.ZERO,
                    rolling7dLimitRub = BigDecimal("7500"),
                    rolling7dBreached = false,
                    rolling30dPnlRub = BigDecimal.ZERO,
                    rolling30dLimitRub = BigDecimal("12500"),
                    rolling30dBreached = false,
                    consecutiveLosses = 0,
                    maxConsecutiveLosses = 3,
                    shadowModeActive = false,
                    shadowModeUntil = null,
                    reasons = emptyList(),
                    timestamp = Instant.now(),
                ),
            )
    }

    @BeforeEach
    fun stubDefaults() {
        stubClosedPositions(emptyList())
        stubDrawdown(0.0)
    }

    @Test
    fun `half kelly reduces full kelly by exactly half`() =
        runBlocking {
            // w=0.6, r=2.0, n=20 -> wilson lower bound ~0.488 -> full kelly ~0.232
            val s = stats(winRate = 0.6, avgWin = BigDecimal("200"), avgLoss = BigDecimal("100"))
            stubStats(mapOf("SBER" to s))

            riskConfig.kellyFraction = 1.0
            val full = service.calculateOptimalPositionSize("SBER")

            riskConfig.kellyFraction = 0.5
            val half = service.calculateOptimalPositionSize("SBER")

            assertEquals(full.multiply(BigDecimal("0.5")), half)
        }

    @Test
    fun `wilson shrinkage makes kelly smaller than raw win rate`() =
        runBlocking {
            // Raw w=0.6, r=2 -> full kelly = 0.4 -> 20000.
            // Wilson lower bound (z=1, n=20) ~0.488 -> full kelly ~0.232 -> ~11617.
            val s = stats(winRate = 0.6, avgWin = BigDecimal("200"), avgLoss = BigDecimal("100"))
            stubStats(mapOf("SBER" to s))
            riskConfig.kellyFraction = 1.0

            val size = service.calculateOptimalPositionSize("SBER")

            assertTrue(size > BigDecimal("10000"), "wilson size should stay positive, was $size")
            assertTrue(size < BigDecimal("13000"), "wilson size should be shrunk well below 20000, was $size")
        }

    @Test
    fun `kelly is capped at 50 percent of max position`() =
        runBlocking {
            // Экстремально выгодная статистика -> wilson lower bound всё ещё > 0.5 -> cap 0.50
            val s = stats(winRate = 0.9, avgWin = BigDecimal("1000"), avgLoss = BigDecimal("100"))
            stubStats(mapOf("SBER" to s))
            riskConfig.kellyFraction = 1.0

            val size = service.calculateOptimalPositionSize("SBER")
            val cap = riskConfig.maxPositionRub.multiply(BigDecimal("0.50"))
            assertEquals(0, cap.compareTo(size))
        }

    @Test
    fun `no trades falls back to conservative fraction not max position`() =
        runBlocking {
            stubStats(emptyMap())
            riskConfig.kellyFraction = 0.5
            val size = service.calculateOptimalPositionSize("SBER")
            val fallback = riskConfig.maxPositionRub.multiply(BigDecimal(riskConfig.kellyNoDataFraction.toString()))
            assertEquals(0, fallback.compareTo(size))
        }

    @Test
    fun `insufficient trade sample uses conservative fallback not kelly`() =
        runBlocking {
            // 5 сделок < kellyMinTrades (15): win rate статистически бессмысленен
            val s = stats(winRate = 0.9, avgWin = BigDecimal("1000"), avgLoss = BigDecimal("100"), totalTrades = 5)
            stubStats(mapOf("SBER" to s))
            riskConfig.kellyFraction = 1.0

            val size = service.calculateOptimalPositionSize("SBER")
            val fallback = riskConfig.maxPositionRub.multiply(BigDecimal(riskConfig.kellyNoDataFraction.toString()))
            assertEquals(0, fallback.compareTo(size))
        }

    @Test
    fun `volatility targeting reduces size for high atr`() =
        runBlocking {
            // w=0.6, r=2.0, n=20 -> wilson kelly ~0.232 -> base ~11617
            val s = stats(winRate = 0.6, avgWin = BigDecimal("200"), avgLoss = BigDecimal("100"))
            stubStats(mapOf("SBER" to s))
            riskConfig.kellyFraction = 1.0

            val lowVol = service.calculateOptimalPositionSize("SBER", atr = BigDecimal("2"), currentPrice = BigDecimal("100"))
            val highVol = service.calculateOptimalPositionSize("SBER", atr = BigDecimal("10"), currentPrice = BigDecimal("100"))
            val extremeVol = service.calculateOptimalPositionSize("SBER", atr = BigDecimal("20"), currentPrice = BigDecimal("100"))

            // ATR 2% -> mult=2.0, ATR 10% -> mult=0.4, ATR 20% -> mult=0.25 (floor)
            val base = BigDecimal("11617")
            assertEquals(base.multiply(BigDecimal("2.0")).toDouble(), lowVol.toDouble(), 60.0)
            assertEquals(base.multiply(BigDecimal("0.4")).toDouble(), highVol.toDouble(), 30.0)
            assertEquals(base.multiply(BigDecimal("0.25")).toDouble(), extremeVol.toDouble(), 30.0)
        }

    @Test
    fun `missing volatility data keeps neutral multiplier`() =
        runBlocking {
            val s = stats(winRate = 0.6, avgWin = BigDecimal("200"), avgLoss = BigDecimal("100"))
            stubStats(mapOf("SBER" to s))
            riskConfig.kellyFraction = 1.0

            // candleCache mocked: calculateRealizedVolatility/calculateAtr/getRecentCandles -> null/empty
            val size = service.calculateOptimalPositionSize("SBER")

            // wilson kelly ~0.232 * 50000 ~11617, без vol-множителя и без просадки
            assertTrue(size > BigDecimal("10000") && size < BigDecimal("13000"), "unexpected size $size")
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
            // full kelly ~11617 -> drawdown reduction *0.5
            assertEquals(5808.0, size.toDouble(), 60.0)
        }

    @Test
    fun `drawdown depth of 7 percent halves size continuously`() =
        runBlocking {
            val s = stats(winRate = 0.6, avgWin = BigDecimal("200"), avgLoss = BigDecimal("100"))
            stubStats(mapOf("SBER" to s))
            riskConfig.kellyFraction = 1.0
            stubDrawdown(7.0)

            val size = service.calculateOptimalPositionSize("SBER")
            // base ~11617 * 0.5 (tier 6%) -> ~5808
            assertEquals(5808.0, size.toDouble(), 60.0)
        }

    @Test
    fun `drawdown depth of 12 percent degrades to quarter`() =
        runBlocking {
            val s = stats(winRate = 0.6, avgWin = BigDecimal("200"), avgLoss = BigDecimal("100"))
            stubStats(mapOf("SBER" to s))
            riskConfig.kellyFraction = 1.0
            stubDrawdown(12.0)

            val size = service.calculateOptimalPositionSize("SBER")
            // base ~11617 * 0.25 (tier 10%) -> ~2904
            assertEquals(2904.0, size.toDouble(), 60.0)
        }

    @Test
    fun `drawdown depth of 15 percent blocks entry`() =
        runBlocking {
            val s = stats(winRate = 0.9, avgWin = BigDecimal("1000"), avgLoss = BigDecimal("100"))
            stubStats(mapOf("SBER" to s))
            riskConfig.kellyFraction = 1.0
            stubDrawdown(15.0)

            val size = service.calculateOptimalPositionSize("SBER")
            assertEquals(0, size.compareTo(BigDecimal.ZERO))
        }

    @Test
    fun `quarter kelly is default`() =
        runBlocking {
            val s = stats(winRate = 0.6, avgWin = BigDecimal("200"), avgLoss = BigDecimal("100"))
            stubStats(mapOf("SBER" to s))

            assertEquals(0.25, riskConfig.kellyFraction)
            val size = service.calculateOptimalPositionSize("SBER")
            // full kelly ~0.232 * 0.25 = ~0.058 -> ~2904
            assertTrue(size <= riskConfig.maxPositionRub.multiply(BigDecimal("0.10")).add(BigDecimal.ONE))
        }
}
