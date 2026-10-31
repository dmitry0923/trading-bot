package com.trading.bot.application

import com.trading.bot.application.risk.FuturesRiskEngine
import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.config.InstrumentsConfig.InstrumentSpec
import com.trading.bot.config.RiskConfig
import com.trading.bot.model.CloseReason
import com.trading.bot.model.InstrumentType
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.entity.Position
import com.trading.bot.repository.PositionRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.math.BigDecimal

/**
 * [FuturesPositionMonitor] — порядок решений монитора фьючерсной позиции:
 * pendingEntry/pendingClose отдаются реконсилятору, ликвидация приоритетнее SL/TP,
 * покрывающая биржевая защита подавляет локальное закрытие, а trailing-стоп
 * подтягивается только при включённом флаге.
 */
class FuturesPositionMonitorTest {
    private val futuresRiskEngine = mock<FuturesRiskEngine>()
    private val positionRepo = mock<PositionRepository>()
    private val engine = mock<OrderExecutionEngine>()
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
                        ticker = "Si",
                        type = "FUTURES",
                        lotSize = 1,
                        priceStep = BigDecimal("0.01"),
                        priceStepCost = BigDecimal("1.0"),
                    ),
                )
        }

    private val monitor =
        FuturesPositionMonitor(
            futuresRiskEngine,
            riskConfig,
            instrumentsConfig,
            positionRepo,
            engine,
            meterRegistry,
        )

    private fun position(
        ticker: String = "Si",
        direction: PositionDirection = PositionDirection.LONG,
        quantity: Int = 1,
        instrumentType: InstrumentType = InstrumentType.FUTURES,
        stopLoss: String? = null,
        takeProfit: String? = null,
        trailingStop: String? = null,
        pendingEntry: Boolean = false,
        pendingClose: Boolean = false,
        id: Long? = 1L,
        slOrderId: String? = null,
        slOrderPrice: String? = null,
        tpOrderId: String? = null,
        tpOrderPrice: String? = null,
    ): Position =
        Position(
            id = id,
            ticker = ticker,
            direction = direction,
            quantity = quantity,
            entryPrice = BigDecimal("100"),
            instrumentType = instrumentType,
            status = PositionStatus.OPEN,
            stopLoss = stopLoss?.toBigDecimal(),
            takeProfit = takeProfit?.toBigDecimal(),
            trailingStopPrice = trailingStop?.toBigDecimal(),
            pendingEntry = pendingEntry,
            pendingClose = pendingClose,
            slOrderId = slOrderId,
            slOrderPrice = slOrderPrice?.toBigDecimal(),
            tpOrderId = tpOrderId,
            tpOrderPrice = tpOrderPrice?.toBigDecimal(),
            cycleId = "cycle-1",
        )

    private suspend fun stubPositions(vararg positions: Position) {
        whenever(positionRepo.findByStatus(PositionStatus.OPEN)).thenReturn(positions.toList())
    }

    private fun liquidation(status: FuturesRiskEngine.LiquidationStatus) {
        whenever(futuresRiskEngine.checkLiquidationDistance(anyPosition(), anyBigDecimal())).thenReturn(status)
    }

    private fun anyPosition(): Position {
        Mockito.any(Position::class.java)
        return position()
    }

    private fun anyBigDecimal(): BigDecimal {
        Mockito.any(BigDecimal::class.java)
        return BigDecimal.ZERO
    }

    private fun anyReason(): CloseReason {
        Mockito.any(CloseReason::class.java)
        return CloseReason.STOP_LOSS
    }

    private fun anyLong(): Long {
        Mockito.any(Long::class.javaObjectType)
        return 1L
    }

    private fun anyNullableDecimal(): BigDecimal? {
        Mockito.nullable(BigDecimal::class.java)
        return null
    }

    /** `updateMonitorState` имеет 4 default-параметра: verify требует все 7 matchers. */
    private suspend fun verifyMonitorSaved() {
        verify(positionRepo).updateMonitorState(
            anyLong(),
            anyNullableDecimal(),
            anyNullableDecimal(),
            anyNullableDecimal(),
            anyNullableDecimal(),
            anyNullableDecimal(),
            anyNullableDecimal(),
        )
    }

    private suspend fun verifyMonitorNotSaved() {
        verify(positionRepo, never()).updateMonitorState(
            anyLong(),
            anyNullableDecimal(),
            anyNullableDecimal(),
            anyNullableDecimal(),
            anyNullableDecimal(),
            anyNullableDecimal(),
            anyNullableDecimal(),
        )
    }

    @Test
    fun `stock positions and other tickers are ignored`() =
        runBlocking {
            stubPositions(
                position(instrumentType = InstrumentType.STOCK),
                position(ticker = "RI"),
            )

            monitor.monitor("Si", BigDecimal("100"))

            verify(futuresRiskEngine, never()).checkLiquidationDistance(anyPosition(), anyBigDecimal())
            verify(engine, never()).closePosition(anyPosition(), anyBigDecimal(), anyReason())
            verifyMonitorNotSaved()
        }

    @Test
    fun `pending entry is delegated to outbox resolution`() =
        runBlocking {
            val pending = position(pendingEntry = true)
            stubPositions(pending)

            monitor.monitor("Si", BigDecimal("100"))

            verify(engine).resolveEntryViaOutbox(pending)
            verify(engine, never()).closePosition(anyPosition(), anyBigDecimal(), anyReason())
            verify(futuresRiskEngine, never()).checkLiquidationDistance(anyPosition(), anyBigDecimal())
        }

    @Test
    fun `pending close is delegated to reconciliation`() =
        runBlocking {
            val pending = position(pendingClose = true)
            stubPositions(pending)

            monitor.monitor("Si", BigDecimal("100"))

            verify(engine).reconcilePosition(pending)
            verify(engine, never()).closePosition(anyPosition(), anyBigDecimal(), anyReason())
        }

    @Test
    fun `critical liquidation closes immediately and skips protection logic`() =
        runBlocking {
            stubPositions(position(stopLoss = "100"))
            liquidation(FuturesRiskEngine.LiquidationStatus.CRITICAL)

            monitor.monitor("Si", BigDecimal("99"))

            verify(engine).closePosition(anyPosition(), anyBigDecimal(), eq(CloseReason.LIQUIDATION_CRITICAL))
            verifyMonitorNotSaved()
        }

    @Test
    fun `liquidation warning increments counter and keeps monitoring`() =
        runBlocking {
            stubPositions(position())
            liquidation(FuturesRiskEngine.LiquidationStatus.WARNING)

            monitor.monitor("Si", BigDecimal("100"))

            assertEquals(
                1.0,
                meterRegistry.counter("futures.liquidation.warning", "ticker", "Si").count(),
                1e-9,
            )
            verify(engine, never()).closePosition(anyPosition(), anyBigDecimal(), anyReason())
            verifyMonitorSaved()
        }

    @Test
    fun `safe liquidation proceeds to exit checks`() =
        runBlocking {
            stubPositions(position(stopLoss = "95"))
            liquidation(FuturesRiskEngine.LiquidationStatus.SAFE)

            monitor.monitor("Si", BigDecimal("94"))

            verify(engine).closePosition(anyPosition(), anyBigDecimal(), eq(CloseReason.STOP_LOSS))
        }

    @Test
    fun `take profit closes when no exchange take covers the level`() =
        runBlocking {
            stubPositions(position(takeProfit = "110"))
            liquidation(FuturesRiskEngine.LiquidationStatus.SAFE)

            monitor.monitor("Si", BigDecimal("111"))

            verify(engine).closePosition(anyPosition(), anyBigDecimal(), eq(CloseReason.TAKE_PROFIT))
        }

    @Test
    fun `trailing stop closes when price crosses the local stop`() =
        runBlocking {
            stubPositions(position(trailingStop = "102"))
            liquidation(FuturesRiskEngine.LiquidationStatus.SAFE)

            monitor.monitor("Si", BigDecimal("101"))

            verify(engine).closePosition(anyPosition(), anyBigDecimal(), eq(CloseReason.TRAILING_STOP))
        }

    @Test
    fun `exchange stop covering the level suppresses local stop loss`() =
        runBlocking {
            stubPositions(position(stopLoss = "95", slOrderId = "sl-1", slOrderPrice = "95"))
            liquidation(FuturesRiskEngine.LiquidationStatus.SAFE)

            monitor.monitor("Si", BigDecimal("94"))

            verify(engine, never()).closePosition(anyPosition(), anyBigDecimal(), anyReason())
            verifyMonitorSaved()
        }

    @Test
    fun `exchange take covering the level suppresses local take profit`() =
        runBlocking {
            stubPositions(position(takeProfit = "110", tpOrderId = "tp-1", tpOrderPrice = "110"))
            liquidation(FuturesRiskEngine.LiquidationStatus.SAFE)

            monitor.monitor("Si", BigDecimal("111"))

            verify(engine, never()).closePosition(anyPosition(), anyBigDecimal(), anyReason())
        }

    @Test
    fun `monitor persists state and republishes protection after trailing update`() =
        runBlocking {
            val open = position()
            stubPositions(open)
            liquidation(FuturesRiskEngine.LiquidationStatus.SAFE)

            monitor.monitor("Si", BigDecimal("110"))

            // trailing подтянут (цена выше entry) и сохранён узким UPDATE
            assertEquals(0, BigDecimal("108.90").compareTo(open.trailingStopPrice))
            verifyMonitorSaved()
            verify(engine).onProtectionLevelsChanged(open)
        }

    @Test
    fun `position without id still republishes protection without persistence`() =
        runBlocking {
            val open = position(id = null)
            stubPositions(open)
            liquidation(FuturesRiskEngine.LiquidationStatus.SAFE)

            monitor.monitor("Si", BigDecimal("110"))

            verifyMonitorNotSaved()
            verify(engine).onProtectionLevelsChanged(open)
        }

    @Test
    fun `trailing disabled leaves stop untouched but still persists`() =
        runBlocking {
            riskConfig.trailingStopEnabled = false
            val open = position()
            stubPositions(open)
            liquidation(FuturesRiskEngine.LiquidationStatus.SAFE)

            monitor.monitor("Si", BigDecimal("110"))

            assertNull(open.trailingStopPrice)
            verifyMonitorSaved()
        }
}
