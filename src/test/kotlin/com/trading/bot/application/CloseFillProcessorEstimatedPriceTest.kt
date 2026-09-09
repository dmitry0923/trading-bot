package com.trading.bot.application

import com.trading.bot.client.AlorClient
import com.trading.bot.model.CloseReason
import com.trading.bot.model.InstrumentType
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.dto.ExecutionReport
import com.trading.bot.model.dto.OrderStatus
import com.trading.bot.model.entity.Position
import com.trading.bot.repository.PositionRepository
import com.trading.bot.service.TradeEventService
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.math.BigDecimal

/**
 * P0-1 regression: WS close fill WITHOUT avgPrice must not NPE.
 *
 * handlePendingCloseReport previously used `report.avgPrice!!` which crashed when the
 * WS ExecutionReport carried a fill without an average price. Now the price is resolved
 * as `currentPrice ?: entryPrice` (mark-to-market estimate), and a
 * `{metricPrefix}.close.price_estimated` counter is incremented for visibility.
 */
class CloseFillProcessorEstimatedPriceTest {
    private val positionRepo = Mockito.mock(PositionRepository::class.java)
    private val alorClient = Mockito.mock(AlorClient::class.java)
    private val tradeEventService = Mockito.mock(TradeEventService::class.java)
    private val meterRegistry = SimpleMeterRegistry()

    private val processor =
        CloseFillProcessor(
            positionRepo = positionRepo,
            alorClient = alorClient,
            pnlCalculator = PnlCalculator { _, _, _, _ -> BigDecimal("10") },
            tradeEventService = tradeEventService,
            meterRegistry = meterRegistry,
            metricPrefix = "futures",
            portfolioResolver = { "portfolio" },
            onPositionClosed = {},
            cancelProtectionOrders = {},
            attachProtectionOrders = {},
        )

    @BeforeEach
    fun setUp() {
        Mockito.reset(positionRepo, alorClient, tradeEventService)
        meterRegistry.clear()
    }

    @Test
    fun closeFillWithoutAvgPrice_usesMarkToMarketAndCountsEstimated() =
        runBlocking {
            val pos = position()
            whenever(positionRepo.findById(7L)).thenReturn(pos)
            whenever(positionRepo.transitionToClosed(anyLong(), anyStatus(), anyBigDecimalArg(), anyReason(), anyBigDecimalArg(), anyInt()))
                .thenReturn(true)
            whenever(positionRepo.releaseEntry(anyString(), Mockito.nullable(Long::class.java))).thenReturn(Unit)
            whenever(tradeEventService.recordPositionClosed(anyPosition(), anyString())).thenReturn(Unit)

            val handled =
                processor.handlePendingCloseReport(
                    pos,
                    ExecutionReport(
                        orderId = "order-close-7",
                        status = OrderStatus.FILLED,
                        cumulativeFilledQty = 2,
                        avgPrice = null,
                    ),
                )

            assertEquals(true, handled) { "report must be handled without NPE" }
            val closePrice = argumentCaptor<BigDecimal>()
            verify(positionRepo)
                .transitionToClosed(
                    eq(7L),
                    eq(PositionStatus.CLOSED),
                    closePrice.capture(),
                    eq(CloseReason.STOP_LOSS),
                    anyBigDecimalArg(),
                    eq(2),
                )
            assertEquals(BigDecimal("105"), closePrice.firstValue) {
                "fallback close price must be currentPrice (mark-to-market)"
            }
            val estimated =
                meterRegistry.counter("futures.close.price_estimated", Tags.of("ticker", "CNYRUBF")).count()
            assertEquals(1.0, estimated)
        }

    @Test
    fun closeFillWithAvgPrice_usesReportPriceAndNoEstimatedMetric() =
        runBlocking {
            val pos =
                position(
                    id = 8L,
                    orderId = "order-close-8",
                    reason = CloseReason.TAKE_PROFIT,
                    quantity = 1,
                )
            whenever(positionRepo.findById(8L)).thenReturn(pos)
            whenever(positionRepo.transitionToClosed(anyLong(), anyStatus(), anyBigDecimalArg(), anyReason(), anyBigDecimalArg(), anyInt()))
                .thenReturn(true)
            whenever(positionRepo.releaseEntry(anyString(), Mockito.nullable(Long::class.java))).thenReturn(Unit)
            whenever(tradeEventService.recordPositionClosed(anyPosition(), anyString())).thenReturn(Unit)

            processor.handlePendingCloseReport(
                pos,
                ExecutionReport(
                    orderId = "order-close-8",
                    status = OrderStatus.FILLED,
                    cumulativeFilledQty = 1,
                    avgPrice = BigDecimal("110"),
                ),
            )

            val closePrice = argumentCaptor<BigDecimal>()
            verify(positionRepo)
                .transitionToClosed(
                    eq(8L),
                    eq(PositionStatus.TAKE_PROFIT),
                    closePrice.capture(),
                    eq(CloseReason.TAKE_PROFIT),
                    anyBigDecimalArg(),
                    eq(1),
                )
            assertEquals(BigDecimal("110"), closePrice.firstValue) {
                "close price must be report avgPrice when present"
            }
            val estimated =
                meterRegistry.counter("futures.close.price_estimated", Tags.of("ticker", "CNYRUBF")).count()
            assertEquals(0.0, estimated)
        }

    private fun position(
        id: Long = 7L,
        orderId: String = "order-close-7",
        reason: CloseReason = CloseReason.STOP_LOSS,
        quantity: Int = 2,
    ): Position =
        Position(
            id = id,
            ticker = "CNYRUBF",
            direction = PositionDirection.LONG,
            quantity = quantity,
            entryPrice = BigDecimal("100"),
            currentPrice = BigDecimal("105"),
            instrumentType = InstrumentType.FUTURES,
            status = PositionStatus.OPEN,
            pendingClose = true,
            closeOrderId = orderId,
            closeReason = reason,
        )

    private fun anyPosition(): Position {
        Mockito.any(Position::class.java)
        return Position(ticker = "CNYRUBF", direction = PositionDirection.LONG, quantity = 1, entryPrice = BigDecimal.ZERO)
    }

    private fun anyLong(): Long {
        Mockito.any(Long::class.javaObjectType)
        return 0L
    }

    private fun anyInt(): Int {
        Mockito.any(Int::class.javaObjectType)
        return 0
    }

    private fun anyStatus(): PositionStatus {
        Mockito.any(PositionStatus::class.java)
        return PositionStatus.CLOSED
    }

    private fun anyReason(): CloseReason {
        Mockito.any(CloseReason::class.java)
        return CloseReason.STOP_LOSS
    }

    private fun anyBigDecimalArg(): BigDecimal {
        Mockito.any(BigDecimal::class.java)
        return BigDecimal.ZERO
    }

    private fun anyString(): String {
        Mockito.any(String::class.java)
        return ""
    }
}
