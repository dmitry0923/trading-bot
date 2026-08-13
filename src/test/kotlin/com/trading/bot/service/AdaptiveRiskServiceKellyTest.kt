package com.trading.bot.service

import com.trading.bot.config.RiskConfig
import com.trading.bot.domain.risk.MarketRegime
import com.trading.bot.domain.risk.MarketRegimeProvider
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.dto.DrawdownStatus
import com.trading.bot.model.dto.TradeStats
import com.trading.bot.model.entity.Position
import com.trading.bot.repository.AgentLogRepository
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
import java.math.RoundingMode
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

    private suspend fun stubStats(map: Map<String, TradeStats>) {
        Mockito.`when`(tradeAnalysis.analyzeLastNDays(30)).thenReturn(map)
    }

    private suspend fun stubClosedPositions(list: List<Position>) {
        Mockito.`when`(positionRepo.findClosedSince(any())).thenReturn(list)
    }

    private suspend fun stubAum() {
        Mockito.`when`(aumProvider.currentAum()).thenReturn(riskConfig.maxPositionRub)
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
        runBlocking {
            stubClosedPositions(emptyList())
            stubAum()
        }
        stubDrawdown(0.0)
    }

    @Test
    fun `half kelly reduces full kelly by exactly half`() =
        runBlocking {
            // w=0.6, r=1.25, n=20 -> wilson lower bound ~0.488 -> full kelly ~0.079 (~3940 < cap 5000)
            val s = stats(winRate = 0.6, avgWin = BigDecimal("125"), avgLoss = BigDecimal("100"))
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
            // Raw w=0.6, r=1.25 -> full kelly = 0.28 -> 14000.
            // Wilson lower bound (z=1, n=20) ~0.488 -> full kelly ~0.079 -> ~3940 (ниже капа 5000).
            val s = stats(winRate = 0.6, avgWin = BigDecimal("125"), avgLoss = BigDecimal("100"))
            stubStats(mapOf("SBER" to s))
            riskConfig.kellyFraction = 1.0

            val size = service.calculateOptimalPositionSize("SBER")

            assertTrue(size > BigDecimal("3800"), "wilson size should stay positive, was $size")
            assertTrue(size < BigDecimal("4100"), "wilson size should be shrunk well below 14000, was $size")
        }

    @Test
    fun `kelly is capped at 10 percent of aum`() =
        runBlocking {
            // Экстремально выгодная статистика -> wilson lower bound всё ещё > 0.1 -> cap 0.10
            val s = stats(winRate = 0.9, avgWin = BigDecimal("1000"), avgLoss = BigDecimal("100"))
            stubStats(mapOf("SBER" to s))
            riskConfig.kellyFraction = 1.0

            val size = service.calculateOptimalPositionSize("SBER")
            val cap = riskConfig.maxPositionRub.multiply(BigDecimal("0.10"))
            assertEquals(0, size.setScale(4, RoundingMode.HALF_UP).compareTo(cap))
        }

    @Test
    fun `no trades falls back to conservative fraction not max position`() =
        runBlocking {
            stubStats(emptyMap())
            riskConfig.kellyFraction = 0.5
            val size = service.calculateOptimalPositionSize("SBER")
            // no-data fallback ограничен капом: min(0.15, 0.10) = 0.10
            val fallbackFraction = minOf(riskConfig.kellyNoDataFraction, riskConfig.kellyMaxPositionFraction)
            val fallback = riskConfig.maxPositionRub.multiply(BigDecimal(fallbackFraction.toString()))
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
            val fallbackFraction = minOf(riskConfig.kellyNoDataFraction, riskConfig.kellyMaxPositionFraction)
            val fallback = riskConfig.maxPositionRub.multiply(BigDecimal(fallbackFraction.toString()))
            assertEquals(0, fallback.compareTo(size))
        }

    @Test
    fun `volatility targeting reduces size for high atr`() =
        runBlocking {
            // w=0.6, r=2.0, n=20 -> wilson kelly ~0.232, но кап 0.10 -> base ~5000
            val s = stats(winRate = 0.6, avgWin = BigDecimal("200"), avgLoss = BigDecimal("100"))
            stubStats(mapOf("SBER" to s))
            riskConfig.kellyFraction = 1.0

            val lowVol = service.calculateOptimalPositionSize("SBER", atr = BigDecimal("2"), currentPrice = BigDecimal("100"))
            val highVol = service.calculateOptimalPositionSize("SBER", atr = BigDecimal("10"), currentPrice = BigDecimal("100"))
            val extremeVol = service.calculateOptimalPositionSize("SBER", atr = BigDecimal("20"), currentPrice = BigDecimal("100"))

            // ATR 2% -> mult=2.0, ATR 10% -> mult=0.4, ATR 20% -> mult=0.25 (floor)
            val base = BigDecimal("5000")
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

            // wilson kelly ~0.232, но кап 0.10 * 50000 ~5000, без vol-множителя и без просадки
            assertTrue(size > BigDecimal("4900") && size < BigDecimal("5100"), "unexpected size $size")
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
            // base ~5000 (cap 0.10) -> drawdown reduction *0.5
            assertEquals(2500.0, size.toDouble(), 60.0)
        }

    @Test
    fun `drawdown depth of 7 percent halves size continuously`() =
        runBlocking {
            val s = stats(winRate = 0.6, avgWin = BigDecimal("200"), avgLoss = BigDecimal("100"))
            stubStats(mapOf("SBER" to s))
            riskConfig.kellyFraction = 1.0
            stubDrawdown(7.0)

            val size = service.calculateOptimalPositionSize("SBER")
            // base ~5000 (cap 0.10) * 0.5 (tier 6%) -> ~2500
            assertEquals(2500.0, size.toDouble(), 60.0)
        }

    @Test
    fun `drawdown depth of 12 percent degrades to quarter`() =
        runBlocking {
            val s = stats(winRate = 0.6, avgWin = BigDecimal("200"), avgLoss = BigDecimal("100"))
            stubStats(mapOf("SBER" to s))
            riskConfig.kellyFraction = 1.0
            stubDrawdown(12.0)

            val size = service.calculateOptimalPositionSize("SBER")
            // base ~5000 (cap 0.10) * 0.25 (tier 10%) -> ~1250
            assertEquals(1250.0, size.toDouble(), 60.0)
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
