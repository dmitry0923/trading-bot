package com.trading.bot.service

import com.trading.bot.config.RiskConfig
import com.trading.bot.domain.risk.MarketRegime
import com.trading.bot.domain.risk.MarketRegimeProvider
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.dto.DrawdownStatus
import com.trading.bot.model.dto.TradeStats
import com.trading.bot.model.entity.Candle
import com.trading.bot.model.entity.Position
import com.trading.bot.repository.AgentLogRepository
import com.trading.bot.repository.PositionRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal

/**
 * Ветви [AdaptiveRiskService], не покрытые существующими тестами (скан-19: 40 из 152):
 * ожидаемая прибыль на лот (недостаток/вырожденность статистики), диагностика
 * онлайн-калибровки порога (все причины fallback + успешная калибровка),
 * правило паузы по серии убытков/PF, волатильностный множитель (ATR / дневная σ /
 * внутридневная σ / нет данных), режимный overlay (VOLATILE/STRESS/выключен),
 * секторная корреляция (хедж `Si`, неизвестный сектор, та же бумага) и
 * drawdown-recovery с позицией без P&L.
 */
class AdaptiveRiskServiceBranchesTest {
    private val riskConfig =
        RiskConfig().apply {
            marketRegimeEnabled = false
            perTickerRegimeEnabled = false
            confidenceSizingEnabled = false
        }
    private val tradeAnalysis = Mockito.mock(TradeAnalysisService::class.java)
    private val positionRepo = Mockito.mock(PositionRepository::class.java)
    private val candleCache = Mockito.mock(CandleCacheService::class.java)
    private val drawdownProtection = Mockito.mock(DrawdownProtectionService::class.java)
    private val meterRegistry = SimpleMeterRegistry()
    private val correlationProvider = Mockito.mock(CorrelationMatrixProvider::class.java)
    private var marketRegime: MarketRegime = MarketRegime.NORMAL
    private val marketRegimeProvider = MarketRegimeProvider { marketRegime }
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

    @BeforeEach
    fun setUp() {
        Mockito.reset(tradeAnalysis, positionRepo, candleCache, drawdownProtection, correlationProvider, aumProvider)
        marketRegime = MarketRegime.NORMAL
        runBlocking {
            Mockito
                .`when`(tradeAnalysis.analyzeLastNDays(Mockito.anyInt(), Mockito.nullable(Long::class.java)))
                .thenReturn(emptyMap())
            Mockito
                .`when`(aumProvider.currentAum(Mockito.nullable(Long::class.java)))
                .thenReturn(BigDecimal("100000"))
            Mockito
                .`when`(positionRepo.findClosedByAccountSince(Mockito.nullable(Long::class.java), anyTime()))
                .thenReturn(emptyList())
            Mockito
                .`when`(positionRepo.findClosedByTickerSince(Mockito.anyString(), anyTime()))
                .thenReturn(emptyList())
            Mockito
                .`when`(
                    agentLogRepo.findStrategySignalStrengthByCycleIds(
                        Mockito.anyString(),
                        Mockito.anyCollection(),
                    ),
                ).thenReturn(emptyMap())
        }
        Mockito.`when`(drawdownProtection.cachedOrNeutral(Mockito.nullable(Long::class.java))).thenReturn(drawdown(0.0))
        Mockito
            .`when`(candleCache.calculateRealizedVolatility(Mockito.anyString(), Mockito.anyString(), Mockito.anyInt()))
            .thenReturn(null)
        Mockito
            .`when`(candleCache.calculateAtr(Mockito.anyString(), Mockito.anyString(), Mockito.anyInt()))
            .thenReturn(null)
        Mockito
            .`when`(candleCache.getRecentCandles(Mockito.anyString(), Mockito.anyString(), Mockito.anyInt()))
            .thenReturn(emptyList())
    }

    private fun drawdown(percent: Double) =
        DrawdownStatus(
            aum = BigDecimal("100000"),
            peakAum = BigDecimal("100000"),
            drawdownPercent = percent,
            dailyPnlRub = BigDecimal.ZERO,
            dailyLimitRub = BigDecimal("2000"),
            dailyLimitBreached = false,
            rolling7dPnlRub = BigDecimal.ZERO,
            rolling7dLimitRub = BigDecimal("7000"),
            rolling7dBreached = false,
            rolling30dPnlRub = BigDecimal.ZERO,
            rolling30dLimitRub = BigDecimal("12000"),
            rolling30dBreached = false,
            consecutiveLosses = 0,
            maxConsecutiveLosses = 3,
            shadowModeActive = false,
            shadowModeUntil = null,
            reasons = emptyList(),
            timestamp = java.time.Instant.now(),
        )

    private fun stats(
        ticker: String = "SBER",
        totalTrades: Int = 30,
        winRate: Double = 0.6,
        avgWin: String = "100",
        avgLoss: String = "50",
        profitFactor: Double = 1.5,
        maxConsecutiveLosses: Int = 1,
    ) = TradeStats(
        ticker = ticker,
        totalTrades = totalTrades,
        winningTrades = (winRate * totalTrades).toInt(),
        losingTrades = totalTrades - (winRate * totalTrades).toInt(),
        winRate = winRate,
        avgWin = BigDecimal(avgWin),
        avgLoss = BigDecimal(avgLoss),
        profitFactor = profitFactor,
        maxConsecutiveLosses = maxConsecutiveLosses,
        avgHoldTimeMinutes = 60,
        slHitRate = 0.3,
        tpHitRate = 0.4,
        strategyCloseRate = 0.3,
        bestEntryHour = 10,
        worstEntryHour = 15,
        blindSpots = emptyList(),
    )

    private fun stubStats(
        days: Int,
        vararg entries: Pair<String, TradeStats>,
    ) {
        runBlocking {
            Mockito
                .`when`(tradeAnalysis.analyzeLastNDays(Mockito.eq(days), Mockito.nullable(Long::class.java)))
                .thenReturn(entries.toMap())
        }
    }

    /** Helper-паттерн Mockito: матчер для non-null LocalDateTime + реальный dummy. */
    private fun anyTime(): java.time.LocalDateTime {
        Mockito.any(java.time.LocalDateTime::class.java)
        return java.time.LocalDateTime.of(2026, 1, 1, 0, 0)
    }

    private fun closedPosition(
        pnl: BigDecimal?,
        cycleId: String? = "cycle-1",
    ): Position =
        Position(
            ticker = "SBER",
            direction = PositionDirection.LONG,
            quantity = 1,
            entryPrice = BigDecimal("100"),
            pnl = pnl,
            cycleId = cycleId,
        )

    private fun position(ticker: String): Position =
        Position(
            ticker = ticker,
            direction = PositionDirection.LONG,
            quantity = 1,
            entryPrice = BigDecimal("100"),
        )

    // ------------------------------------------------------- expectedNetProfit

    @Test
    fun `expected net profit requires statistics`() {
        assertNull(runBlocking { service.expectedNetProfitPerLot("SBER") })
    }

    @Test
    fun `expected net profit requires the minimum trade count`() {
        stubStats(30, "SBER" to stats(totalTrades = 5))

        assertNull(runBlocking { service.expectedNetProfitPerLot("SBER") })
    }

    @Test
    fun `expected net profit requires a non zero average win or loss`() {
        stubStats(30, "SBER" to stats(totalTrades = 30, avgWin = "0", avgLoss = "0"))

        assertNull(runBlocking { service.expectedNetProfitPerLot("SBER") })
    }

    @Test
    fun `expected net profit applies the wilson weighted averages`() {
        stubStats(30, "SBER" to stats(totalTrades = 30, winRate = 0.6, avgWin = "100", avgLoss = "50"))

        val expected = runBlocking { service.expectedNetProfitPerLot("SBER") }

        assertTrue(expected != null && expected > BigDecimal.ZERO, "положительное мат. ожидание: $expected")
        assertTrue(expected!! < BigDecimal("100"), "Wilson шринкует win rate ниже сырого: $expected")
    }

    // ------------------------------------------------- confidence calibration

    @Test
    fun `calibration falls back when disabled`() {
        riskConfig.confidenceCalibrationEnabled = false
        try {
            val diagnostic = runBlocking { service.diagnoseConfidenceCalibration("SBER") }

            assertEquals(AdaptiveRiskService.ConfidenceSource.FALLBACK, diagnostic.source)
            assertEquals("disabled", diagnostic.reason)
            assertEquals(0.60, diagnostic.threshold, 1e-9, "нет статистики — нейтральный порог")
        } finally {
            riskConfig.confidenceCalibrationEnabled = true
        }
    }

    @Test
    fun `calibration falls back with insufficient closed trades`() {
        runBlocking {
            Mockito
                .`when`(positionRepo.findClosedByTickerSince(Mockito.anyString(), anyTime()))
                .thenReturn(listOf(closedPosition(BigDecimal("10")), closedPosition(BigDecimal("-10"))))
        }

        val diagnostic = runBlocking { service.diagnoseConfidenceCalibration("SBER") }

        assertEquals("insufficient-closed-trades", diagnostic.reason)
        assertEquals(2, diagnostic.closedTrades)
    }

    @Test
    fun `calibration falls back without confidence logs`() {
        val closed = (0 until 12).map { closedPosition(BigDecimal("10"), cycleId = "c$it") }
        runBlocking {
            Mockito
                .`when`(positionRepo.findClosedByTickerSince(Mockito.anyString(), anyTime()))
                .thenReturn(closed)
        }

        val diagnostic = runBlocking { service.diagnoseConfidenceCalibration("SBER") }

        assertEquals("no-confidence-logs", diagnostic.reason)
        assertEquals(12, diagnostic.closedTrades)
        assertEquals(0, diagnostic.scoredTrades)
    }

    @Test
    fun `calibration falls back when no threshold reaches the target win rate`() {
        val closed = (0 until 12).map { closedPosition(BigDecimal("10"), cycleId = "c$it") }
        val losses = (0 until 12).map { closedPosition(BigDecimal("-10"), cycleId = "c$it") }
        runBlocking {
            Mockito
                .`when`(positionRepo.findClosedByTickerSince(Mockito.anyString(), anyTime()))
                .thenReturn(closed.mapIndexed { i, pos -> if (i < 4) pos else losses[i] })
            Mockito
                .`when`(
                    agentLogRepo.findStrategySignalStrengthByCycleIds(Mockito.anyString(), Mockito.anyCollection()),
                ).thenReturn((0 until 12).associate { "c$it" to 0.9 })
        }

        val diagnostic = runBlocking { service.diagnoseConfidenceCalibration("SBER") }

        assertEquals("no-satisfying-threshold", diagnostic.reason)
        assertEquals(12, diagnostic.scoredTrades)
    }

    @Test
    fun `calibration returns a calibrated threshold and counts the source`() {
        val closed = (0 until 12).map { closedPosition(BigDecimal("10"), cycleId = "c$it") }
        runBlocking {
            Mockito
                .`when`(positionRepo.findClosedByTickerSince(Mockito.anyString(), anyTime()))
                .thenReturn(closed)
            Mockito
                .`when`(
                    agentLogRepo.findStrategySignalStrengthByCycleIds(Mockito.anyString(), Mockito.anyCollection()),
                ).thenReturn((0 until 12).associate { "c$it" to 0.9 })
        }

        val threshold = runBlocking { service.getAdaptiveConfidenceThreshold("SBER") }

        assertEquals(0.5, threshold, 1e-9, "все сделки выигрышные — нижняя граница калибровки")
        assertEquals(
            1.0,
            meterRegistry.counter("adaptive.confidence_calibrated", "ticker", "SBER").count(),
        )
        assertEquals(
            0.0,
            meterRegistry.counter("adaptive.confidence_fallback", "ticker", "SBER").count(),
        )
    }

    @Test
    fun `fallback threshold follows the recent win rate`() {
        riskConfig.confidenceCalibrationEnabled = false
        try {
            val cases =
                mapOf(
                    0.30 to 0.80,
                    0.40 to 0.70,
                    0.55 to 0.60,
                    0.65 to 0.55,
                )
            cases.forEach { (winRate, expected) ->
                stubStats(14, "SBER" to stats(winRate = winRate))
                val diagnostic = runBlocking { service.diagnoseConfidenceCalibration("SBER") }
                assertEquals(expected, diagnostic.threshold, 1e-9, "winRate=$winRate")
            }
        } finally {
            riskConfig.confidenceCalibrationEnabled = true
        }
    }

    // ------------------------------------------------------- pause decision

    @Test
    fun `trading pause triggers on a long losing streak`() {
        stubStats(7, "SBER" to stats(maxConsecutiveLosses = 4))

        assertTrue(runBlocking { service.shouldPauseTrading("SBER") })
        val pause = meterRegistry.find("adaptive.pause").tag("ticker", "SBER").gauge()
        assertEquals(1.0, pause!!.value())
    }

    @Test
    fun `trading pause triggers on a low profit factor with enough trades`() {
        stubStats(7, "SBER" to stats(profitFactor = 0.4, totalTrades = 6))

        assertTrue(runBlocking { service.shouldPauseTrading("SBER") })
    }

    @Test
    fun `low profit factor does not pause on a tiny sample`() {
        stubStats(7, "SBER" to stats(profitFactor = 0.4, totalTrades = 3))

        assertFalse(runBlocking { service.shouldPauseTrading("SBER") })
    }

    @Test
    fun `missing statistics never pause trading`() {
        assertFalse(runBlocking { service.shouldPauseTrading("SBER") })
        val pause = meterRegistry.find("adaptive.pause").tag("ticker", "SBER").gauge()
        assertEquals(0.0, pause!!.value())
    }

    // ------------------------------------------------------ position sizing

    @Test
    fun `sizing uses explicit atr against the current price`() {
        val highVol = runBlocking { service.calculateOptimalPositionSize("SBER", BigDecimal("8"), BigDecimal("100")) }
        val lowVol = runBlocking { service.calculateOptimalPositionSize("SBER", BigDecimal("1"), BigDecimal("100")) }

        assertTrue(lowVol > highVol, "низкая волатильность не должна резать размер: $lowVol vs $highVol")
    }

    @Test
    fun `sizing uses daily realized volatility when atr is absent`() {
        Mockito
            .`when`(candleCache.calculateRealizedVolatility("SBER", "DAY_1", riskConfig.volatilityLookbackDays))
            .thenReturn(1.0)

        val size = runBlocking { service.calculateOptimalPositionSize("SBER") }

        // Без данных множитель 1.0; дневная σ 1% против таргета 4% даёт множитель 2 (clamp).
        assertTrue(
            size > riskConfig.kellyNoDataFraction.let { BigDecimal("100000").multiply(BigDecimal(it.toString())) },
            "низкая σ увеличивает базовый размер: $size",
        )
    }

    @Test
    fun `sizing falls back to the intraday volatility scaled to daily`() {
        Mockito
            .`when`(candleCache.calculateRealizedVolatility("SBER", "MINUTE_10", riskConfig.volatilityLookbackDays))
            .thenReturn(1.0)
        Mockito
            .`when`(candleCache.getRecentCandles("SBER", "MINUTE_10", 1))
            .thenReturn(listOf(candle(BigDecimal("100"))))

        val size = runBlocking { service.calculateOptimalPositionSize("SBER") }

        assertTrue(size > BigDecimal.ZERO)
    }

    @Test
    fun `sizing without volatility data stays neutral`() {
        val size = runBlocking { service.calculateOptimalPositionSize("SBER") }
        val baseline = BigDecimal("100000").multiply(BigDecimal(riskConfig.kellyNoDataFraction.toString()))

        assertEquals(0, baseline.compareTo(size), "нет данных о волатильности — множитель 1.0")
    }

    @Test
    fun `volatile market halves the size and stress zeroes it`() {
        riskConfig.marketRegimeEnabled = true
        try {
            marketRegime = MarketRegime.NORMAL
            val normal = runBlocking { service.calculateOptimalPositionSize("SBER") }

            marketRegime = MarketRegime.VOLATILE
            val volatile = runBlocking { service.calculateOptimalPositionSize("SBER") }

            marketRegime = MarketRegime.STRESS
            val stress = runBlocking { service.calculateOptimalPositionSize("SBER") }

            assertEquals(
                normal.multiply(BigDecimal(riskConfig.regimeVolatileSizeMultiplier.toString())).toDouble(),
                volatile.toDouble(),
                1e-6,
            )
            assertEquals(0.0, stress.toDouble(), 1e-9, "STRESS полностью выключает сайзинг")
        } finally {
            riskConfig.marketRegimeEnabled = false
        }
    }

    @Test
    fun `per ticker regime without history keeps the full size`() {
        riskConfig.perTickerRegimeEnabled = true
        try {
            val neutral = runBlocking { service.calculateOptimalPositionSize("SBER") }

            val baseline = BigDecimal("100000").multiply(BigDecimal(riskConfig.kellyNoDataFraction.toString()))
            assertEquals(0, baseline.compareTo(neutral), "нехватка баров режима — множитель 1.0")
        } finally {
            riskConfig.perTickerRegimeEnabled = false
        }
    }

    @Test
    fun `losing statistics collapse the size to zero`() {
        stubStats(30, "SBER" to stats(winRate = 0.2, avgWin = "10", avgLoss = "100"))

        val size = runBlocking { service.calculateOptimalPositionSize("SBER") }

        assertEquals(0.0, size.toDouble(), 1e-9, "отрицательный Kelly не должен давать размер")
    }

    // -------------------------------------------------- sector correlation

    @Test
    fun `sector correlation ignores the futures hedge and unknown sectors`() {
        riskConfig.sectors = mapOf("SBER" to "FIN", "VTBR" to "FIN")
        try {
            assertFalse(runBlocking { service.exceedsSectorCorrelationLimit("Si", listOf(position("SBER"))) })
            assertFalse(runBlocking { service.exceedsSectorCorrelationLimit("GAZP", listOf(position("SBER"))) })
        } finally {
            riskConfig.sectors = emptyMap()
        }
    }

    @Test
    fun `sector correlation skips the same ticker and the hedge position`() {
        riskConfig.sectors = mapOf("SBER" to "FIN", "VTBR" to "FIN")
        Mockito
            .`when`(
                correlationProvider.correlationOf(
                    Mockito.anyString(),
                    Mockito.anyString(),
                    Mockito.anyString(),
                    Mockito.anyInt(),
                ),
            ).thenReturn(0.95)
        try {
            assertFalse(
                runBlocking { service.exceedsSectorCorrelationLimit("SBER", listOf(position("SBER"), position("Si"))) },
                "та же бумага и хедж не считаются коррелированными",
            )
            assertTrue(runBlocking { service.exceedsSectorCorrelationLimit("SBER", listOf(position("VTBR"))) })
            assertEquals(
                1.0,
                meterRegistry.counter("adaptive.sector_correlation.blocked", "ticker", "SBER").count(),
            )
        } finally {
            riskConfig.sectors = emptyMap()
        }
    }

    @Test
    fun `sector correlation passes a different sector`() {
        riskConfig.sectors = mapOf("SBER" to "FIN", "GAZP" to "ENERGY")
        Mockito
            .`when`(
                correlationProvider.correlationOf(
                    Mockito.anyString(),
                    Mockito.anyString(),
                    Mockito.anyString(),
                    Mockito.anyInt(),
                ),
            ).thenReturn(0.99)
        try {
            assertFalse(runBlocking { service.exceedsSectorCorrelationLimit("SBER", listOf(position("GAZP"))) })
        } finally {
            riskConfig.sectors = emptyMap()
        }
    }

    // ------------------------------------------------------- recovery mode

    @Test
    fun `drawdown recovery ignores positions without pnl`() {
        runBlocking {
            Mockito
                .`when`(positionRepo.findClosedByAccountSince(Mockito.nullable(Long::class.java), anyTime()))
                .thenReturn(
                    listOf(
                        closedPosition(null),
                        closedPosition(BigDecimal("-10")),
                        closedPosition(BigDecimal("-10")),
                        closedPosition(BigDecimal("-10")),
                    ),
                )
        }

        assertFalse(
            runBlocking { service.isInDrawdownRecovery() },
            "позиция без P&L прерывает серию убытков (трактуется как 0)",
        )
    }

    @Test
    fun `three consecutive losses switch recovery mode on`() {
        runBlocking {
            Mockito
                .`when`(positionRepo.findClosedByAccountSince(Mockito.nullable(Long::class.java), anyTime()))
                .thenReturn(
                    (0 until 3).map { closedPosition(BigDecimal("-10")) },
                )
        }

        assertTrue(runBlocking { service.isInDrawdownRecovery() })
        assertEquals(1.0, meterRegistry.find("adaptive.drawdown_recovery").gauge()!!.value())
    }

    private fun candle(close: BigDecimal): Candle =
        Candle(
            ticker = "SBER",
            timeframe = "MINUTE_10",
            openPrice = close,
            highPrice = close.add(BigDecimal("1")),
            lowPrice = close.subtract(BigDecimal("1")),
            closePrice = close,
            volume = 1_000,
            time = java.time.LocalDateTime.of(2026, 10, 1, 10, 0),
        )
}
