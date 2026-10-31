package com.trading.bot.application

import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.config.RiskConfig
import com.trading.bot.domain.risk.PositionSizeResult
import com.trading.bot.domain.risk.TradeRiskDecision
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.StrategyAction
import com.trading.bot.model.entity.Strategy
import com.trading.bot.repository.StrategyRepository
import com.trading.bot.service.ReactiveRedisCacheService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * [OrderBuilder] — SHORT-ветки фьючерсных/спотовых стопов, приоритет frozen →
 * ATR → процент, выключенный trailing и персист фактических риск-параметров в
 * историю стратегий (БД + Redis).
 */
class OrderBuilderBranchesTest {
    private val strategyRepo = mock<StrategyRepository>()
    private val redis = mock<ReactiveRedisCacheService>()
    private val instrumentsConfig = InstrumentsConfig()

    private fun builder(riskConfig: RiskConfig = RiskConfig()) =
        OrderBuilder(
            riskConfig = riskConfig,
            instrumentsConfig = instrumentsConfig,
            strategyRepo = strategyRepo,
            redis = redis,
        )

    private fun size(quantity: Int = 2) =
        PositionSizeResult(
            quantity = quantity,
            marginRequired = BigDecimal("850"),
            riskAmount = BigDecimal("100"),
            liquidationPrice = BigDecimal("90"),
            reason = null,
        )

    // ─── futures ────────────────────────────────────────────────────

    @Test
    fun `futures SHORT stop is above entry and target below`() {
        val params =
            builder().buildFuturesOrderParams(
                ticker = "Si",
                direction = PositionDirection.SHORT,
                entryPrice = BigDecimal("100"),
                currentGo = BigDecimal("15000"),
                size = size(),
                leverage = BigDecimal("2"),
                stopLossPoints = 10,
                takeProfitPointsOverride = 20,
            )

        // Si: priceStep 0.01 → SL = 100 + 0.10, TP = 100 - 0.20
        assertEquals(0, BigDecimal("100.10").compareTo(params.stopLossPrice))
        assertEquals(0, BigDecimal("99.80").compareTo(params.takeProfitPrice))
        assertEquals(2, params.quantity)
        assertEquals(0, BigDecimal("90").compareTo(params.liquidationPrice))
        assertEquals(10, params.stopLossPoints)
    }

    @Test
    fun `futures unknown ticker returns an empty order`() {
        val params =
            builder().buildFuturesOrderParams(
                ticker = "UNKNOWN",
                direction = PositionDirection.LONG,
                entryPrice = BigDecimal("100"),
                currentGo = BigDecimal("1"),
                size = size(),
                leverage = BigDecimal.ONE,
                stopLossPoints = 10,
            )

        assertEquals(0, params.quantity)
        assertNull(params.stopLossPrice)
    }

    // ─── spot ───────────────────────────────────────────────────────

    @Test
    fun `frozen percentages take priority over atr and spec defaults`() {
        val params =
            builder().buildSpotOrderParams(
                ticker = "SBER",
                direction = PositionDirection.LONG,
                quantity = 10,
                entryPrice = BigDecimal("100"),
                atr = BigDecimal("1"),
                frozenSlPercent = 5.0,
                frozenTpPercent = 10.0,
            )

        assertEquals(0, BigDecimal("95.00").compareTo(params.stopLossPrice))
        assertEquals(0, BigDecimal("110.00").compareTo(params.takeProfitPrice))
    }

    @Test
    fun `atr stops are used when no frozen percentages are set`() {
        val config =
            RiskConfig().apply {
                atrSlMultiplier = BigDecimal("2.0")
                atrTpMultiplier = BigDecimal("3.0")
            }

        val params =
            builder(config).buildSpotOrderParams(
                ticker = "SBER",
                direction = PositionDirection.LONG,
                quantity = 10,
                entryPrice = BigDecimal("100"),
                atr = BigDecimal("1"),
            )

        assertEquals(0, BigDecimal("98.00").compareTo(params.stopLossPrice))
        assertEquals(0, BigDecimal("103.00").compareTo(params.takeProfitPrice))
    }

    @Test
    fun `zero frozen percentages fall back to the percent path`() {
        val config =
            RiskConfig().apply {
                atrSlMultiplier = BigDecimal.ZERO
                atrTpMultiplier = BigDecimal.ZERO
            }
        val params =
            builder(config).buildSpotOrderParams(
                ticker = "SBER",
                direction = PositionDirection.LONG,
                quantity = 10,
                entryPrice = BigDecimal("100"),
                atr = BigDecimal("1"),
                frozenSlPercent = 0.0,
                frozenTpPercent = 0.0,
            )

        // SBER без per-instrument SL/TP → глобальные 2% / 15%
        assertEquals(0, BigDecimal("98.00").compareTo(params.stopLossPrice))
        assertEquals(0, BigDecimal("115.00").compareTo(params.takeProfitPrice))
    }

    @Test
    fun `SHORT spot stops are mirrored and trailing can be disabled`() {
        val config = RiskConfig().apply { trailingStopEnabled = false }

        val params =
            builder(config).buildSpotOrderParams(
                ticker = "SBER",
                direction = PositionDirection.SHORT,
                quantity = 10,
                entryPrice = BigDecimal("100"),
            )

        assertEquals(0, BigDecimal("102.00").compareTo(params.stopLossPrice))
        assertEquals(0, BigDecimal("85.00").compareTo(params.takeProfitPrice))
        assertNull(params.trailingStopPrice)
    }

    @Test
    fun `spot unknown ticker returns an empty order`() {
        val params =
            builder().buildSpotOrderParams(
                ticker = "UNKNOWN",
                direction = PositionDirection.LONG,
                quantity = 10,
                entryPrice = BigDecimal("100"),
            )

        assertEquals(0, params.quantity)
        assertNull(params.stopLossPrice)
    }

    // ─── персист решения ────────────────────────────────────────────

    @Test
    fun `zero quantity decision is not persisted`(): Unit =
        runBlocking {
            builder().recordStrategyExecution(decision(quantity = 0))

            Mockito.verifyNoInteractions(strategyRepo)
            verify(redis, never()).saveStrategy(anyStrategy())
        }

    @Test
    fun `decision persists order params and refreshes the redis strategy`(): Unit =
        runBlocking {
            val captured = mutableListOf<Any>()
            whenever(
                strategyRepo.updateOrderParams(
                    Mockito.anyString(),
                    Mockito.anyString(),
                    Mockito.anyString(),
                    Mockito.anyInt(),
                    Mockito.nullable(BigDecimal::class.java),
                    Mockito.nullable(BigDecimal::class.java),
                    Mockito.anyBoolean(),
                ),
            ).thenAnswer { invocation ->
                captured += invocation.getArgument<Any>(0)
                captured += invocation.getArgument<Any>(3)
                1L
            }
            val existing =
                Strategy(
                    ticker = "SBER",
                    action = StrategyAction.BUY,
                    targetPrice = BigDecimal("100"),
                    quantity = 1,
                    signalStrength = 0.6,
                    reasoning = "old",
                    cycleId = "c1",
                    validUntil = LocalDateTime.now().plusMinutes(5),
                )
            whenever(redis.getStrategy("SBER")).thenReturn(existing)
            whenever(redis.saveStrategy(anyStrategy())).thenAnswer { Unit }

            builder().recordStrategyExecution(
                decision(quantity = 3, stopLoss = BigDecimal("95"), takeProfit = BigDecimal("110"), trailing = true),
            )

            assertTrue(captured.contains("c1"))
            assertTrue(captured.contains(3))
            val expected =
                existing.copy(
                    quantity = 3,
                    stopLoss = BigDecimal("95"),
                    takeProfit = BigDecimal("110"),
                    trailingStop = true,
                )
            verify(redis).saveStrategy(expected)
        }

    @Test
    fun `decision without a cached strategy still persists to the database`(): Unit =
        runBlocking {
            whenever(
                strategyRepo.updateOrderParams(
                    Mockito.anyString(),
                    Mockito.anyString(),
                    Mockito.anyString(),
                    Mockito.anyInt(),
                    Mockito.nullable(BigDecimal::class.java),
                    Mockito.nullable(BigDecimal::class.java),
                    Mockito.anyBoolean(),
                ),
            ).thenReturn(1L)
            whenever(redis.getStrategy("SBER")).thenReturn(null)

            builder().recordStrategyExecution(decision(quantity = 2))

            verify(redis, never()).saveStrategy(anyStrategy())
        }

    private fun decision(
        quantity: Int,
        stopLoss: BigDecimal? = null,
        takeProfit: BigDecimal? = null,
        trailing: Boolean = false,
    ) = TradeRiskDecision(
        ticker = "SBER",
        cycleId = "c1",
        timeframe = "MINUTE_10",
        action = StrategyAction.BUY,
        direction = PositionDirection.LONG,
        quantity = quantity,
        targetPrice = BigDecimal("100"),
        stopLoss = stopLoss,
        takeProfit = takeProfit,
        trailingStop = trailing,
        signalStrength = 0.6,
        reasoning = "test",
    )

    private fun anyStrategy(): Strategy {
        Mockito.any(Strategy::class.java)
        return Strategy(
            ticker = "SBER",
            action = StrategyAction.BUY,
            targetPrice = BigDecimal("100"),
            quantity = 1,
            signalStrength = 0.5,
            reasoning = "matcher",
            cycleId = "c1",
            validUntil = LocalDateTime.now(),
        )
    }

    @Test
    fun `trailing is propagated from the built stop when enabled`() {
        val params =
            builder(RiskConfig().apply { trailingStopEnabled = true }).buildSpotOrderParams(
                ticker = "SBER",
                direction = PositionDirection.LONG,
                quantity = 10,
                entryPrice = BigDecimal("100"),
            )

        assertFalse(params.trailingStopPrice == null)
        assertEquals(0, params.stopLossPrice!!.compareTo(params.trailingStopPrice!!))
    }
}
