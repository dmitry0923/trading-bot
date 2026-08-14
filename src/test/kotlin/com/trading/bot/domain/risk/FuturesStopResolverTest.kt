package com.trading.bot.domain.risk

import com.trading.bot.config.RiskConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Единая политика ATR-стопа фьючерсов (live + backtest) — regression-тест
 * [FuturesStopResolver]. Контуры не должны разойтись: любой сценарий здесь —
 * обязательный контракт для обоих.
 */
class FuturesStopResolverTest {
    private val resolver = FuturesStopResolver()
    private val config = RiskConfig()
    private val priceStep = BigDecimal("0.01")

    @Test
    fun `atr maps to points via multiplier`() {
        // ATR 0.20 ₽ -> 20 пунктов × 2 = 40 пунктов (не дефолт, не кламп).
        assertEquals(40, resolver.resolve(BigDecimal("0.20"), priceStep, config))
    }

    @Test
    fun `atr stop clamps to configured bounds`() {
        assertEquals(
            config.futuresAtrStopMaxPoints,
            resolver.resolve(BigDecimal("4.0"), priceStep, config),
        )
        assertEquals(
            config.futuresAtrStopMinPoints,
            resolver.resolve(BigDecimal("0.03"), priceStep, config),
        )
    }

    @Test
    fun `null atr falls back to default stop points`() {
        assertEquals(config.defaultStopLossPoints, resolver.resolve(null, priceStep, config))
    }

    @Test
    fun `non positive price step falls back to default`() {
        assertEquals(config.defaultStopLossPoints, resolver.resolve(BigDecimal("0.20"), BigDecimal.ZERO, config))
    }

    @Test
    fun `degenerate atr mapping falls back to default`() {
        val zeroMultiplier = RiskConfig().apply { futuresAtrStopMultiplier = 0.0 }
        assertEquals(zeroMultiplier.defaultStopLossPoints, resolver.resolve(BigDecimal("0.20"), priceStep, zeroMultiplier))
    }

    @Test
    fun `disabled flag returns default even with valid atr`() {
        val disabled = RiskConfig().apply { futuresAtrStopEnabled = false }
        assertEquals(disabled.defaultStopLossPoints, resolver.resolve(BigDecimal("0.20"), priceStep, disabled))
        assertEquals(disabled.defaultStopLossPoints, resolver.resolve(null, priceStep, disabled))
    }
}
