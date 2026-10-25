package com.trading.bot.application

import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.config.InstrumentsConfig.InstrumentSpec
import com.trading.bot.config.RiskConfig
import com.trading.bot.event.PriceChangedEvent
import com.trading.bot.model.CloseReason
import com.trading.bot.model.InstrumentType
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.StrategyAction
import com.trading.bot.model.entity.Position
import com.trading.bot.model.entity.Strategy
import com.trading.bot.repository.PositionRepository
import com.trading.bot.service.ReactiveRedisCacheService
import com.trading.bot.service.TradeEventService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * [StockPositionMonitor]: локальный мониторинг SL/TP/trailing и подтягивание
 * уровней из стратегии в Redis-кэше.
 *
 * Движок исполнения, репозиторий и кэш — моки: проверяется именно решение
 * монитора (какой выход инициирован, какие уровни обновлены и когда состояние
 * сохраняется). Бирокевые защитные заявки, покрывающие уровень, обязаны
 * подавлять локальное закрытие — иначе двойное закрытие позиции.
 */
class StockPositionMonitorTest {
    private val positionRepo = mock<PositionRepository>()
    private val engine = mock<OrderExecutionEngine>()
    private val redis = mock<ReactiveRedisCacheService>()
    private val tradeEventService = mock<TradeEventService>()
    private val meterRegistry = SimpleMeterRegistry()
    private val riskConfig =
        RiskConfig().apply {
            trailingStopEnabled = true
            trailingStopPercent = 1.0
        }
    private val instrumentsConfig =
        InstrumentsConfig().apply {
            instruments =
                listOf(
                    InstrumentSpec(
                        ticker = "SBER",
                        type = "STOCK",
                        lotSize = 10,
                        priceStep = BigDecimal("0.01"),
                    ),
                    InstrumentSpec(
                        ticker = "Si",
                        type = "FUTURES",
                        lotSize = 1,
                        priceStep = BigDecimal("0.01"),
                    ),
                )
        }

    private val monitor =
        StockPositionMonitor(
            positionRepo,
            engine,
            redis,
            riskConfig,
            instrumentsConfig,
            tradeEventService,
            meterRegistry,
        )

    private suspend fun stubPositions(
        vararg positions: Position,
        strategy: Strategy? = null,
    ) {
        whenever(positionRepo.findByStatusAndTicker(eq(PositionStatus.OPEN), any())).thenReturn(positions.toList())
        whenever(positionRepo.save(anyPosition())).thenAnswer { it.getArgument<Position>(0) }
        whenever(redis.getStrategy(any())).thenReturn(strategy)
    }

    private fun openPosition(
        direction: PositionDirection = PositionDirection.LONG,
        ticker: String = "SBER",
        quantity: Int = 2,
        entryPrice: String = "100",
        stopLoss: String? = null,
        takeProfit: String? = null,
        trailingStopPrice: String? = null,
        instrumentType: InstrumentType = InstrumentType.STOCK,
        pendingEntry: Boolean = false,
        pendingClose: Boolean = false,
        slOrderId: String? = null,
        slOrderPrice: String? = null,
        tpOrderId: String? = null,
        tpOrderPrice: String? = null,
    ): Position =
        Position(
            id = 1L,
            ticker = ticker,
            direction = direction,
            quantity = quantity,
            entryPrice = BigDecimal(entryPrice),
            instrumentType = instrumentType,
            status = PositionStatus.OPEN,
            stopLoss = stopLoss?.toBigDecimal(),
            takeProfit = takeProfit?.toBigDecimal(),
            trailingStopPrice = trailingStopPrice?.toBigDecimal(),
            pendingEntry = pendingEntry,
            pendingClose = pendingClose,
            slOrderId = slOrderId,
            slOrderPrice = slOrderPrice?.toBigDecimal(),
            tpOrderId = tpOrderId,
            tpOrderPrice = tpOrderPrice?.toBigDecimal(),
            cycleId = "cycle-1",
        )

    private fun strategy(
        action: StrategyAction = StrategyAction.HOLD,
        stopLoss: String? = null,
        takeProfit: String? = null,
    ): Strategy =
        Strategy(
            ticker = "SBER",
            action = action,
            targetPrice = BigDecimal("100"),
            quantity = 1,
            signalStrength = 0.6,
            reasoning = "test",
            cycleId = "cycle-1",
            validUntil = LocalDateTime.now().plusMinutes(5),
            stopLoss = stopLoss?.toBigDecimal(),
            takeProfit = takeProfit?.toBigDecimal(),
        )

    private fun anyPosition(): Position {
        Mockito.any(Position::class.java)
        return openPosition()
    }

    private fun anyBigDecimal(): BigDecimal {
        Mockito.any(BigDecimal::class.java)
        return BigDecimal.ZERO
    }

    private fun anyReason(): CloseReason {
        Mockito.any(CloseReason::class.java)
        return CloseReason.STOP_LOSS
    }

    private fun price(value: String): PriceChangedEvent = PriceChangedEvent("SBER", BigDecimal(value))

    @Test
    fun `local stop loss closes position and sets mark to market pnl`(): Unit =
        runBlocking {
            val position = openPosition(stopLoss = "95")
            stubPositions(position)

            monitor.monitor(price("94.5"))

            verify(engine).closePosition(anyPosition(), anyBigDecimal(), eq(CloseReason.STOP_LOSS))
            // P&L = (94.5 - 100) × qty 2 × lotSize 10
            assertEquals(0, BigDecimal("-110").compareTo(position.pnl!!))
            assertEquals(0, BigDecimal("94.5").compareTo(position.currentPrice!!))
            val pnlGauge = meterRegistry.find("position.pnl").tag("ticker", "SBER").gauge()
            assertEquals(-110.0, pnlGauge?.value() ?: 0.0, 1e-9)
        }

    @Test
    fun `short position pnl is inverted`(): Unit =
        runBlocking {
            val position = openPosition(direction = PositionDirection.SHORT, stopLoss = "105")
            stubPositions(position)

            monitor.monitor(price("103"))

            // P&L = (100 - 103) × 2 × 10 = -60
            assertEquals(0, BigDecimal("-60").compareTo(position.pnl!!))
            verify(engine, never()).closePosition(anyPosition(), anyBigDecimal(), anyReason())
        }

    @Test
    fun `exchange stop covering the level suppresses local close`(): Unit =
        runBlocking {
            val position =
                openPosition(
                    stopLoss = "95",
                    slOrderId = "SL-1",
                    slOrderPrice = "95",
                )
            stubPositions(position)

            monitor.monitor(price("94.5"))

            verify(engine, never()).closePosition(anyPosition(), anyBigDecimal(), anyReason())
        }

    @Test
    fun `take profit closes position when no exchange tp covers it`(): Unit =
        runBlocking {
            val position = openPosition(takeProfit = "110")
            stubPositions(position)

            monitor.monitor(price("110.5"))

            verify(engine).closePosition(anyPosition(), anyBigDecimal(), eq(CloseReason.TAKE_PROFIT))
        }

    @Test
    fun `exchange take profit covering the level suppresses local close`(): Unit =
        runBlocking {
            val position =
                openPosition(
                    takeProfit = "110",
                    tpOrderId = "TP-1",
                    tpOrderPrice = "110",
                )
            stubPositions(position)

            monitor.monitor(price("110.5"))

            verify(engine, never()).closePosition(anyPosition(), anyBigDecimal(), anyReason())
        }

    @Test
    fun `trailing stop breach closes position`(): Unit =
        runBlocking {
            val position = openPosition(trailingStopPrice = "98")
            stubPositions(position)

            monitor.monitor(price("97.9"))

            verify(engine).closePosition(anyPosition(), anyBigDecimal(), eq(CloseReason.TRAILING_STOP))
        }

    @Test
    fun `trailing stop is lifted with price and persisted`(): Unit =
        runBlocking {
            val position = openPosition()
            stubPositions(position)

            monitor.monitor(price("110"))

            assertNotNull(position.trailingStopPrice)
            // 110 - 1% = 108.9, округление CEILING до шага 0.01
            assertEquals(0, BigDecimal("108.90").compareTo(position.trailingStopPrice!!))
            verify(positionRepo).save(position)
            verify(tradeEventService).recordPositionUpdated(position)
            verify(engine).onProtectionLevelsChanged(position)
        }

    @Test
    fun `trailing update is skipped for instrument without spec`(): Unit =
        runBlocking {
            val position = openPosition(ticker = "UNKNOWN")
            whenever(positionRepo.findByStatusAndTicker(eq(PositionStatus.OPEN), any()))
                .thenReturn(listOf(position))
            whenever(redis.getStrategy(any())).thenReturn(null)

            monitor.monitor(PriceChangedEvent("UNKNOWN", BigDecimal("110")))

            assertEquals(null, position.trailingStopPrice)
            verify(positionRepo, never()).save(anyPosition())
        }

    @Test
    fun `strategy close closes position with strategy reason`(): Unit =
        runBlocking {
            val position = openPosition()
            stubPositions(position, strategy = strategy(action = StrategyAction.CLOSE))

            monitor.monitor(price("101"))

            verify(engine).closePosition(anyPosition(), anyBigDecimal(), eq(CloseReason.STRATEGY_CLOSE))
        }

    @Test
    fun `strategy tightens stop loss and take profit for long`(): Unit =
        runBlocking {
            val position = openPosition(stopLoss = "95", takeProfit = "105")
            stubPositions(position, strategy = strategy(stopLoss = "98", takeProfit = "115"))

            monitor.monitor(price("101"))

            assertEquals(0, BigDecimal("98").compareTo(position.stopLoss!!))
            assertEquals(0, BigDecimal("115").compareTo(position.takeProfit!!))
            verify(positionRepo).save(position)
            verify(engine).onProtectionLevelsChanged(position)
        }

    @Test
    fun `strategy levels are applied when position has none yet`(): Unit =
        runBlocking {
            val position = openPosition()
            stubPositions(position, strategy = strategy(stopLoss = "97", takeProfit = "110"))

            monitor.monitor(price("101"))

            assertEquals(0, BigDecimal("97").compareTo(position.stopLoss!!))
            assertEquals(0, BigDecimal("110").compareTo(position.takeProfit!!))
        }

    @Test
    fun `strategy cannot loosen stop loss or take profit for long`(): Unit =
        runBlocking {
            val position = openPosition(stopLoss = "98", takeProfit = "110")
            stubPositions(position, strategy = strategy(stopLoss = "90", takeProfit = "105"))

            monitor.monitor(price("101"))

            assertEquals(0, BigDecimal("98").compareTo(position.stopLoss!!))
            assertEquals(0, BigDecimal("110").compareTo(position.takeProfit!!))
            // ни один уровень не ослаблен: позиция остаётся с прежними защитными ценами
            assertFalse(position.slPendingReplace)
        }

    @Test
    fun `strategy tightens short position levels downwards`(): Unit =
        runBlocking {
            val position =
                openPosition(
                    direction = PositionDirection.SHORT,
                    stopLoss = "105",
                    takeProfit = "95",
                )
            stubPositions(position, strategy = strategy(stopLoss = "103", takeProfit = "93"))

            monitor.monitor(price("99"))

            assertEquals(0, BigDecimal("103").compareTo(position.stopLoss!!))
            assertEquals(0, BigDecimal("93").compareTo(position.takeProfit!!))
        }

    @Test
    fun `pending entry or close positions are not monitored`(): Unit =
        runBlocking {
            val entry = openPosition(pendingEntry = true, stopLoss = "95")
            val close = openPosition(pendingClose = true, stopLoss = "95")
            stubPositions(entry, close)

            monitor.monitor(price("90"))

            verify(engine, never()).closePosition(anyPosition(), anyBigDecimal(), anyReason())
            verify(positionRepo, never()).save(anyPosition())
        }

    @Test
    fun `futures positions are ignored by stock monitor`(): Unit =
        runBlocking {
            val futures =
                openPosition(
                    ticker = "Si",
                    instrumentType = InstrumentType.FUTURES,
                    stopLoss = "95",
                )
            stubPositions(futures)

            monitor.monitor(PriceChangedEvent("Si", BigDecimal("90")))

            verify(engine, never()).closePosition(anyPosition(), anyBigDecimal(), anyReason())
        }

    @Test
    fun `monitor failure is counted and does not propagate`(): Unit =
        runBlocking {
            whenever(positionRepo.findByStatusAndTicker(eq(PositionStatus.OPEN), any()))
                .thenThrow(IllegalStateException("db down"))

            monitor.monitor(price("100"))

            assertEquals(1.0, meterRegistry.counter("bot.monitor.error", "ticker", "SBER").count())
            assertTrue(meterRegistry.find("bot.latency").timer() != null)
        }

    @Test
    fun `trailing disabled leaves stop untouched`(): Unit =
        runBlocking {
            riskConfig.trailingStopEnabled = false
            val position = openPosition()
            stubPositions(position)

            monitor.monitor(price("110"))

            assertEquals(null, position.trailingStopPrice)
            verify(positionRepo, never()).save(anyPosition())
        }
}
