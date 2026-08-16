package com.trading.bot.service

import com.trading.bot.config.RiskConfig
import com.trading.bot.domain.risk.MarketRegime
import com.trading.bot.domain.risk.MarketRegimeProvider
import com.trading.bot.model.dto.DrawdownStatus
import com.trading.bot.model.entity.Position
import com.trading.bot.repository.AgentLogRepository
import com.trading.bot.repository.PositionRepository
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import java.math.BigDecimal
import java.time.Instant

/**
 * Confidence-aware позиционный сайзинг (roadmap 13.11.9): размер позиции масштабируется
 * по силе сигнала относительно адаптивного порога тикера. Множитель только
 * урезает размер (min factor при пороге, max factor при ceiling), null-сигнал
 * и выключенный сайзинг нейтральны.
 */
class AdaptiveRiskServiceConfidenceSizingTest {
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

    private val service = buildService(meterRegistry)

    private fun buildService(registry: MeterRegistry): AdaptiveRiskService =
        AdaptiveRiskService(
            riskConfig,
            tradeAnalysis,
            positionRepo,
            candleCache,
            drawdownProtection,
            registry,
            correlationProvider,
            marketRegimeProvider,
            aumProvider,
            agentLogRepo,
        )

    private suspend fun stubAum() {
        Mockito.`when`(aumProvider.currentAum()).thenReturn(riskConfig.maxPositionRub)
    }

    private suspend fun stubClosedPositions(list: List<Position>) {
        Mockito.`when`(positionRepo.findClosedByAccountSince(anyOrNull(), any())).thenReturn(list)
        Mockito.`when`(positionRepo.findClosedByTickerSince(any(), any())).thenReturn(list)
    }

    private suspend fun stubStats(map: Map<String, com.trading.bot.model.dto.TradeStats>) {
        Mockito.`when`(tradeAnalysis.analyzeLastNDays(30)).thenReturn(map)
        Mockito.`when`(tradeAnalysis.analyzeLastNDays(14)).thenReturn(map)
    }

    private fun stubDrawdown() {
        val aum = riskConfig.maxPositionRub
        Mockito
            .`when`(drawdownProtection.cachedOrNeutral(anyOrNull()))
            .thenReturn(
                DrawdownStatus(
                    aum = aum,
                    peakAum = aum,
                    drawdownPercent = 0.0,
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
            stubAum()
            stubClosedPositions(emptyList())
            stubStats(emptyMap())
        }
        stubDrawdown()
    }

    // Порог fallback без данных = 0.60, ceiling = 0.90, minFactor = 0.5, maxFactor = 1.0.
    // База без Kelly-статистики = aum * min(0.15, 0.10) = 50000 * 0.10 = 5000.
    private fun assertSize(
        signalStrength: Double?,
        expected: BigDecimal,
    ) {
        val size = runBlocking { service.calculateOptimalPositionSize("SBER", signalStrength = signalStrength) }
        assertEquals(expected.toDouble(), size.toDouble(), 1.0, "size for signalStrength=$signalStrength")
    }

    @Test
    fun `null signal strength keeps size neutral`() {
        assertSize(null, BigDecimal("5000"))
    }

    @Test
    fun `signal strength at threshold uses minimum factor`() {
        // signalStrength = порог (0.60) -> factor 0.5 -> 2500
        assertSize(0.60, BigDecimal("2500"))
    }

    @Test
    fun `signal strength at ceiling uses max factor`() {
        // signalStrength = ceiling (0.90) -> factor 1.0 -> 5000
        assertSize(0.90, BigDecimal("5000"))
    }

    @Test
    fun `signal strength above ceiling stays at max factor`() {
        assertSize(0.95, BigDecimal("5000"))
    }

    @Test
    fun `mid signal strength interpolates linearly`() {
        // t = (0.75 - 0.60) / (0.90 - 0.60) = 0.5 -> factor = 0.5 + 0.5*0.5 = 0.75 -> 3750
        assertSize(0.75, BigDecimal("3750"))
    }

    @Test
    fun `below threshold clamps to min factor`() {
        assertSize(0.50, BigDecimal("2500"))
    }

    @Test
    fun `disabled sizing returns neutral factor`() {
        riskConfig.confidenceSizingEnabled = false
        assertSize(0.60, BigDecimal("5000"))
    }

    @Test
    fun `confidence factor is exported to gauge`() {
        // Отдельный registry: gauge с константой держит слабую ссылку на значение,
        // и перерегистрация из других тестов того же имени делает чтение недетерминированным.
        val registry = SimpleMeterRegistry()
        val isolatedService = buildService(registry)
        runBlocking { isolatedService.calculateOptimalPositionSize("SBER", signalStrength = 0.75) }
        val gauge = registry.find("adaptive.confidence_factor").tag("ticker", "SBER").gauge()
        assertEquals(0.75, gauge!!.value(), 1e-9)
    }
}
