package com.trading.bot.service

import com.trading.bot.client.MoexClient
import com.trading.bot.config.RiskConfig
import com.trading.bot.domain.risk.MarketRegime
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Движок рыночного режима: перцентильная классификация RVI, fail-safe NORMAL
 * при нехватке истории, fallback на IV Si при недоступности RVI.
 *
 * history = [1..100] (100 наблюдений): rank = доля строго меньших значений.
 */
class MarketRegimeServiceTest {
    private val moexClient = Mockito.mock(MoexClient::class.java)
    private val ivService = Mockito.mock(ImpliedVolatilityService::class.java)
    private val history = (1..100).map { it.toDouble() }

    private fun service(config: RiskConfig = RiskConfig()): MarketRegimeService =
        MarketRegimeService(config, moexClient, ivService, SimpleMeterRegistry())

    private suspend fun stubHistory(list: List<Double>) {
        // refresh() запрашивает историю за regimeLookbackDays (60) календарных дней
        val from = LocalDate.now().minusDays(RiskConfig().regimeLookbackDays.toLong())
        Mockito.`when`(moexClient.getVolatilityIndexDailyCloses("RVI", from)).thenReturn(list)
    }

    @Test
    fun `stress when current rvi at p90 and above`() =
        runBlocking {
            stubHistory(history)
            Mockito.`when`(moexClient.getVolatilityIndex("RVI")).thenReturn(BigDecimal("95")) // rank 94%

            val s = service()
            s.refresh()

            assertEquals(MarketRegime.STRESS, s.currentRegime())
            assertEquals(true, s.isStress())
            assertEquals(0.0, s.sizeMultiplier())
        }

    @Test
    fun `low when current rvi well below p40`() =
        runBlocking {
            stubHistory(history)
            Mockito.`when`(moexClient.getVolatilityIndex("RVI")).thenReturn(BigDecimal("15")) // rank 14%

            val s = service()
            s.refresh()

            assertEquals(MarketRegime.LOW, s.currentRegime())
            assertEquals(1.0, s.sizeMultiplier())
        }

    @Test
    fun `volatile when current rvi between p70 and p90`() =
        runBlocking {
            stubHistory(history)
            Mockito.`when`(moexClient.getVolatilityIndex("RVI")).thenReturn(BigDecimal("85")) // rank 84%

            val s = service()
            s.refresh()

            assertEquals(MarketRegime.VOLATILE, s.currentRegime())
            assertEquals(0.5, s.sizeMultiplier())
        }

    @Test
    fun `insufficient history is fail-safe normal`() =
        runBlocking {
            stubHistory(listOf(10.0, 20.0)) // < regimeMinHistorySamples (20)
            Mockito.`when`(moexClient.getVolatilityIndex("RVI")).thenReturn(BigDecimal("95"))

            val s = service()
            s.refresh()

            assertEquals(MarketRegime.NORMAL, s.currentRegime())
        }

    @Test
    fun `disabled regime engine is normal`() =
        runBlocking {
            stubHistory(history)
            Mockito.`when`(moexClient.getVolatilityIndex("RVI")).thenReturn(BigDecimal("95"))
            val config = RiskConfig().apply { marketRegimeEnabled = false }

            val s = service(config)
            s.refresh()

            assertEquals(MarketRegime.NORMAL, s.currentRegime())
        }

    @Test
    fun `falls back to implied volatility when rvi unavailable`() =
        runBlocking {
            stubHistory(history)
            Mockito.`when`(moexClient.getVolatilityIndex("RVI")).thenReturn(null)
            Mockito.`when`(ivService.impliedVolatilityPercent()).thenReturn(85.0) // rank 84%

            val s = service()
            s.refresh()

            assertEquals(MarketRegime.VOLATILE, s.currentRegime())
        }

    @Test
    fun `default regime is normal before any refresh`() {
        assertEquals(MarketRegime.NORMAL, service().currentRegime())
    }
}
