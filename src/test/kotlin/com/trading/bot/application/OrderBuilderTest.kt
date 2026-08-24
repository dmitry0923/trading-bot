package com.trading.bot.application

import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.config.RiskConfig
import com.trading.bot.model.PositionDirection
import com.trading.bot.repository.StrategyRepository
import com.trading.bot.service.ReactiveRedisCacheService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal

class OrderBuilderTest {
    private val strategyRepo = Mockito.mock(StrategyRepository::class.java)
    private val redis = Mockito.mock(ReactiveRedisCacheService::class.java)
    private val instrumentsConfig = InstrumentsConfig()

    private fun builder(riskConfig: RiskConfig = RiskConfig()) =
        OrderBuilder(
            riskConfig = riskConfig,
            instrumentsConfig = instrumentsConfig,
            strategyRepo = strategyRepo,
            redis = redis,
        )

    // ── CNYRUB_TOM spot order params ───────────────────────

    @Test
    fun `CNYRUB_TOM uses per-instrument slPercent 0_5 and tpPercent 1_0`() {
        val params =
            builder().buildSpotOrderParams(
                ticker = "CNYRUB_TOM",
                direction = PositionDirection.LONG,
                quantity = 1,
                entryPrice = BigDecimal("12.4200"),
            )
        // SL: 12.42 * (1 - 0.005) = 12.3579 → FLOOR to 0.0005 → 12.3575
        assertEquals(0, BigDecimal("12.3575").compareTo(params.stopLossPrice))
        // TP: 12.42 * (1 + 0.01) = 12.5442 → CEILING to 0.0005 → 12.5445
        assertEquals(0, BigDecimal("12.5445").compareTo(params.takeProfitPrice))
    }

    @Test
    fun `CNYRUB_TOM SHORT SL is above entry and TP is below`() {
        val params =
            builder().buildSpotOrderParams(
                ticker = "CNYRUB_TOM",
                direction = PositionDirection.SHORT,
                quantity = 1,
                entryPrice = BigDecimal("12.4200"),
            )
        // SL SHORT: 12.42 * (1 + 0.005) = 12.4821 → CEILING to 0.0005 → 12.4825
        assertEquals(0, BigDecimal("12.4825").compareTo(params.stopLossPrice))
        // TP SHORT: 12.42 * (1 - 0.01) = 12.2958 → FLOOR to 0.0005 → 12.2955
        assertEquals(0, BigDecimal("12.2955").compareTo(params.takeProfitPrice))
    }

    @Test
    fun `CNYRUB_TOM priceStep 0_0005 is used not default 0_01`() {
        val params =
            builder().buildSpotOrderParams(
                ticker = "CNYRUB_TOM",
                direction = PositionDirection.LONG,
                quantity = 1,
                entryPrice = BigDecimal("12.4200"),
            )
        // If default 0.01 was used: SL = 12.42 * 0.98 = 12.30, TP = 12.42 * 1.01 = 12.54
        // With 0.0005: SL = 12.3575, TP = 12.5445
        assertEquals(0, BigDecimal("12.3575").compareTo(params.stopLossPrice))
        assertEquals(0, BigDecimal("12.5445").compareTo(params.takeProfitPrice))
    }

    // ── Unknown ticker returns quantity=0 ────────────────────

    @Test
    fun `unknown ticker returns zero quantity`() {
        val params =
            builder().buildSpotOrderParams(
                ticker = "UNKNOWN_TICKER",
                direction = PositionDirection.LONG,
                quantity = 5,
                entryPrice = BigDecimal("100.50"),
            )
        assertEquals(0, params.quantity)
    }

    // ── Trailing stop ───────────────────────────────────────

    @Test
    fun `trailingStopPrice equals stopLoss when trailing enabled`() {
        val params =
            builder().buildSpotOrderParams(
                ticker = "CNYRUB_TOM",
                direction = PositionDirection.LONG,
                quantity = 1,
                entryPrice = BigDecimal("12.4200"),
            )
        assertNotNull(params.trailingStopPrice)
        assertEquals(0, params.trailingStopPrice!!.compareTo(params.stopLossPrice))
    }

    @Test
    fun `trailingStopPrice is null when trailing disabled`() {
        val rc = RiskConfig().apply { trailingStopEnabled = false }
        val params =
            builder(rc).buildSpotOrderParams(
                ticker = "CNYRUB_TOM",
                direction = PositionDirection.LONG,
                quantity = 1,
                entryPrice = BigDecimal("12.4200"),
            )
        assertNull(params.trailingStopPrice)
    }

    // ── SBER (default stock params) ────────────────────────

    @Test
    fun `SBER uses default 2_0 SL and 4_0 TP with priceStep 0_01`() {
        val params =
            builder().buildSpotOrderParams(
                ticker = "SBER",
                direction = PositionDirection.LONG,
                quantity = 10,
                entryPrice = BigDecimal("270.50"),
            )
        // SL: 270.50 * 0.98 = 265.09 → align to 0.01 → 265.09
        assertEquals(0, BigDecimal("265.09").compareTo(params.stopLossPrice))
        // TP: 270.50 * 1.04 = 281.32 → align to 0.01 → 281.32
        assertEquals(0, BigDecimal("281.32").compareTo(params.takeProfitPrice))
    }

    @Test
    fun `quantity is preserved in OrderParams`() {
        val params =
            builder().buildSpotOrderParams(
                ticker = "CNYRUB_TOM",
                direction = PositionDirection.LONG,
                quantity = 3,
                entryPrice = BigDecimal("12.4200"),
            )
        assertEquals(3, params.quantity)
        assertEquals(PositionDirection.LONG, params.direction)
    }
}
