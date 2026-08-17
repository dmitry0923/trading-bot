package com.trading.bot.application

import com.trading.bot.client.AlorClient
import com.trading.bot.config.AlorConfig
import com.trading.bot.model.CloseReason
import com.trading.bot.model.InstrumentType
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.entity.Position
import com.trading.bot.repository.OrderOutboxRepository
import com.trading.bot.repository.PositionRepository
import com.trading.bot.service.OrderOutboxService
import com.trading.bot.service.TradeEventService
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit-тест атомарной резервации слота входа (EXEC-002, MR-B).
 *
 * Гарантии, проверяемые здесь:
 * - резервация ([PositionRepository.reserveEntry]) происходит ДО отправки entry-ордера;
 *   если слот уже занят (конкурентный вход / открытая позиция) — ордер НЕ размещается,
 *   метрика duplicate, повторный вход по тикеру заблокирован;
 * - полное исполнение → позиция открыта, резервация УДЕРЖИВАЕТСЯ (слот держит
 *   открытая позиция до закрытия);
 * - определённый отказ биржи → [PositionRepository.releaseEntry], ордер не создан,
 *   слот снова свободен;
 * - UNCERTAIN-доставка → pendingEntry-позиция создаётся, резервация НЕ снимается
 *   (факт подтвердит реконсилятор);
 * - PARTIAL fill → pendingEntry на фактическом qty, резервация удерживается;
 * - закрытие позиции ([OrderExecutionEngine.closePosition] через transitionToClosed)
 *   освобождает резервацию по ключу (ticker, accountId).
 */
class OrderExecutionEngineEntryReservationTest {
    private val alorClient = Mockito.mock(AlorClient::class.java)
    private val orderOutboxService = Mockito.mock(OrderOutboxService::class.java)
    private val orderOutboxRepo = Mockito.mock(OrderOutboxRepository::class.java)
    private val positionRepo = Mockito.mock(PositionRepository::class.java)
    private val alorConfig = Mockito.mock(AlorConfig::class.java)
    private val objectMapper = jacksonObjectMapper()
    private val tradeEventService = Mockito.mock(TradeEventService::class.java)
    private val meterRegistry = SimpleMeterRegistry()

    private val openedCount = AtomicInteger(0)
    private val closedCount = AtomicInteger(0)

    private val engine =
        OrderExecutionEngine(
            alorClient = alorClient,
            orderOutboxService = orderOutboxService,
            orderOutboxRepo = orderOutboxRepo,
            positionRepo = positionRepo,
            alorConfig = alorConfig,
            objectMapper = objectMapper,
            tradeEventService = tradeEventService,
            meterRegistry = meterRegistry,
            pnlCalculator = PnlCalculator.plain(),
            instrumentFilter = { true },
            metricPrefix = "test",
            onEntryOpened = { openedCount.incrementAndGet() },
            onPositionClosed = { closedCount.incrementAndGet() },
            protectionOrdersEnabled = false,
            portfolioResolver = { "D12345" },
        )

    private var savedPos: Position? = null

    private fun anyPosition(): Position {
        Mockito.any(Position::class.java)
        return entryPos()
    }

    private fun anyBigDecimal(): BigDecimal {
        Mockito.any(BigDecimal::class.java)
        return BigDecimal.ZERO
    }

    private fun anyString(): String {
        Mockito.any(String::class.java)
        return "Si"
    }

    private fun anyCloseReason(): CloseReason {
        Mockito.any(CloseReason::class.java)
        return CloseReason.STOP_LOSS
    }

    private fun anyDirection(): PositionDirection {
        Mockito.any(PositionDirection::class.java)
        return PositionDirection.LONG
    }

    private fun anyStatus(): PositionStatus {
        Mockito.any(PositionStatus::class.java)
        return PositionStatus.OPEN
    }

    private fun entryPos(): Position =
        Position(
            id = 1L,
            ticker = "Si",
            direction = PositionDirection.LONG,
            quantity = 3,
            entryPrice = BigDecimal("92000"),
            currentPrice = BigDecimal("92000"),
            instrumentType = InstrumentType.FUTURES,
            status = PositionStatus.OPEN,
        )

    private fun buildPos(
        orderId: String?,
        pending: Boolean,
        fillPrice: BigDecimal,
        qty: Int,
    ): Position =
        Position(
            ticker = "Si",
            direction = PositionDirection.LONG,
            quantity = qty,
            entryPrice = fillPrice,
            instrumentType = InstrumentType.FUTURES,
            status = PositionStatus.OPEN,
            alorOrderId = orderId,
            pendingEntry = pending,
        )

    private fun successResult(orderId: String): OrderOutboxService.PlaceOrderResult =
        OrderOutboxService.PlaceOrderResult(UUID.randomUUID(), orderId, success = true)

    private fun stubReserve(reservedId: Long?) {
        runBlocking {
            Mockito
                .`when`(positionRepo.reserveEntry(Mockito.anyString(), anyDirection(), Mockito.nullable(Long::class.java)))
                .thenReturn(reservedId)
        }
    }

    private fun stubEntryPlaceOrder(result: OrderOutboxService.PlaceOrderResult) {
        runBlocking {
            Mockito
                .`when`(
                    orderOutboxService.placeOrder(
                        anyString(),
                        anyString(),
                        Mockito.anyInt(),
                        anyBigDecimal(),
                        anyString(),
                        Mockito.nullable(Long::class.java),
                        Mockito.nullable(String::class.java),
                        Mockito.nullable(BigDecimal::class.java),
                        Mockito.nullable(String::class.java),
                        Mockito.nullable(Long::class.java),
                    ),
                ).thenReturn(result)
        }
    }

    private fun stubEntryFill(execution: AlorClient.OrderExecution?) {
        runBlocking {
            Mockito
                .`when`(alorClient.verifyOrder(anyString(), Mockito.nullable(BigDecimal::class.java), anyString()))
                .thenReturn(execution)
        }
    }

    private fun stubSaveReturnsArg() {
        runBlocking {
            Mockito
                .`when`(positionRepo.save(anyPosition()))
                .thenAnswer { inv ->
                    val arg = inv.getArgument<Position>(0)
                    savedPos = arg
                    arg
                }
        }
    }

    private fun verifyEntryPlaceOrder(times: Int) {
        runBlocking {
            Mockito
                .verify(orderOutboxService, times(times))
                .placeOrder(
                    anyString(),
                    anyString(),
                    Mockito.anyInt(),
                    anyBigDecimal(),
                    anyString(),
                    Mockito.nullable(Long::class.java),
                    Mockito.nullable(String::class.java),
                    Mockito.nullable(BigDecimal::class.java),
                    Mockito.nullable(String::class.java),
                    Mockito.nullable(Long::class.java),
                )
        }
    }

    private fun verifyRelease(times: Int) {
        runBlocking { Mockito.verify(positionRepo, times(times)).releaseEntry(eqString("Si"), Mockito.isNull()) }
    }

    @Suppress("ReplaceCallWithBinaryOperator")
    private fun eqString(value: String): String {
        Mockito.eq(value)
        return value
    }

    @Suppress("ReplaceCallWithBinaryOperator")
    private fun eqDirection(value: PositionDirection): PositionDirection {
        Mockito.eq(value)
        return value
    }

    @Test
    fun `duplicate entry is blocked when slot is already reserved`() {
        stubReserve(null)
        stubEntryPlaceOrder(successResult("e1"))
        stubSaveReturnsArg()

        val result =
            runBlocking {
                engine.placeEntryOrder("Si", PositionDirection.LONG, 3, BigDecimal("92000")) { o, p, pr, q ->
                    buildPos(o, p, pr, q)
                }
            }

        assertNull(result)
        runBlocking {
            Mockito.verify(positionRepo).reserveEntry(eqString("Si"), eqDirection(PositionDirection.LONG), Mockito.isNull())
        }
        verifyEntryPlaceOrder(0)
        runBlocking {
            Mockito.verify(positionRepo, never()).save(anyPosition())
            Mockito.verify(tradeEventService, never()).recordPositionOpened(anyPosition())
        }
        assertEquals(1.0, meterRegistry.counter("test.entry.duplicate", Tags.of("ticker", "Si")).count())
    }

    @Test
    fun `full fill reserves slot places order and opens position`() {
        stubReserve(1L)
        stubEntryPlaceOrder(successResult("e1"))
        stubEntryFill(AlorClient.OrderExecution(status = "FILLED", filledQuantity = 3, avgPrice = BigDecimal("92100")))
        stubSaveReturnsArg()

        val result =
            runBlocking {
                engine.placeEntryOrder("Si", PositionDirection.LONG, 3, BigDecimal("92000")) { o, p, pr, q ->
                    buildPos(o, p, pr, q)
                }
            }

        verifyEntryPlaceOrder(1)
        runBlocking {
            Mockito.verify(positionRepo).reserveEntry(eqString("Si"), eqDirection(PositionDirection.LONG), Mockito.isNull())
            Mockito.verify(positionRepo, never()).releaseEntry(Mockito.anyString(), Mockito.nullable(Long::class.java))
            Mockito.verify(tradeEventService, times(1)).recordPositionOpened(anyPosition())
        }
        assertEquals(1, openedCount.get())
        assertEquals(0, BigDecimal("92100").compareTo(result!!.entryPrice))
        assertEquals(3, result.quantity)
        assertTrue(!result.pendingEntry)
    }

    @Test
    fun `definitive rejection releases reservation and places nothing`() {
        stubReserve(1L)
        stubEntryPlaceOrder(OrderOutboxService.PlaceOrderResult(UUID.randomUUID(), null, success = false))
        stubSaveReturnsArg()

        val result =
            runBlocking {
                engine.placeEntryOrder("Si", PositionDirection.LONG, 3, BigDecimal("92000")) { o, p, pr, q ->
                    buildPos(o, p, pr, q)
                }
            }

        assertNull(result)
        verifyEntryPlaceOrder(1)
        verifyRelease(1)
        runBlocking {
            Mockito.verify(positionRepo, never()).save(anyPosition())
            Mockito.verify(tradeEventService, never()).recordPositionOpened(anyPosition())
        }
        assertEquals(1.0, meterRegistry.counter("test.order.failed", Tags.of("ticker", "Si")).count())
    }

    @Test
    fun `uncertain delivery keeps reservation and creates pendingEntry position`() {
        stubReserve(1L)
        stubEntryPlaceOrder(OrderOutboxService.PlaceOrderResult(UUID.randomUUID(), null, success = false, uncertain = true))
        stubSaveReturnsArg()

        val result =
            runBlocking {
                engine.placeEntryOrder("Si", PositionDirection.LONG, 3, BigDecimal("92000")) { o, p, pr, q ->
                    buildPos(o, p, pr, q)
                }
            }

        assertNull(result)
        runBlocking {
            Mockito.verify(positionRepo, never()).releaseEntry(Mockito.anyString(), Mockito.nullable(Long::class.java))
        }
        assertTrue(savedPos!!.pendingEntry)
        assertEquals(3, savedPos!!.quantity)
        verifyRelease(0)
        assertEquals(1.0, meterRegistry.counter("test.entry.uncertain", Tags.of("ticker", "Si")).count())
    }

    @Test
    fun `partial fill keeps reservation until entry confirmed`() {
        stubReserve(1L)
        stubEntryPlaceOrder(successResult("e1"))
        stubEntryFill(AlorClient.OrderExecution(status = "PARTIALLY_FILLED", filledQuantity = 2, avgPrice = BigDecimal("92000")))
        stubSaveReturnsArg()

        val result =
            runBlocking {
                engine.placeEntryOrder("Si", PositionDirection.LONG, 3, BigDecimal("92000")) { o, p, pr, q ->
                    buildPos(o, p, pr, q)
                }
            }

        assertNull(result)
        assertTrue(savedPos!!.pendingEntry)
        assertEquals(2, savedPos!!.quantity)
        assertEquals("e1", savedPos!!.alorOrderId)
        verifyRelease(0)
        runBlocking {
            Mockito.verify(tradeEventService, never()).recordPositionOpened(anyPosition())
        }
        assertEquals(1.0, meterRegistry.counter("test.entry.partial", Tags.of("ticker", "Si")).count())
    }

    @Test
    fun `verifyOrder failure creates pendingEntry instead of assumed full open (EXEC-3)`() {
        stubReserve(1L)
        stubEntryPlaceOrder(successResult("e1"))
        stubEntryFill(null)
        stubSaveReturnsArg()

        val result =
            runBlocking {
                engine.placeEntryOrder("Si", PositionDirection.LONG, 3, BigDecimal("92000")) { o, p, pr, q ->
                    buildPos(o, p, pr, q)
                }
            }

        assertNull(result)
        assertTrue(savedPos!!.pendingEntry)
        assertEquals(3, savedPos!!.quantity)
        assertEquals("e1", savedPos!!.alorOrderId)
        verifyRelease(0)
        runBlocking {
            Mockito.verify(tradeEventService, never()).recordPositionOpened(anyPosition())
        }
        assertEquals(1.0, meterRegistry.counter("test.entry.uncertain", Tags.of("ticker", "Si")).count())
    }

    @Test
    fun `closing an open position releases its entry reservation`() {
        val pos = entryPos()
        runBlocking {
            Mockito.`when`(positionRepo.claimForClose(1L)).thenReturn(true)
            Mockito.`when`(positionRepo.findById(1L)).thenReturn(pos)
            Mockito
                .`when`(
                    positionRepo.transitionToClosed(
                        Mockito.anyLong(),
                        anyStatus(),
                        anyBigDecimal(),
                        anyCloseReason(),
                        anyBigDecimal(),
                    ),
                ).thenReturn(true)
        }
        stubSaveReturnsArg()
        stubEntryFill(AlorClient.OrderExecution(status = "FILLED", filledQuantity = 3, avgPrice = BigDecimal("90100")))
        runBlocking {
            Mockito
                .`when`(
                    orderOutboxService.placeOrder(
                        anyString(),
                        anyString(),
                        Mockito.anyInt(),
                        Mockito.nullable(BigDecimal::class.java),
                        anyString(),
                        Mockito.anyLong(),
                        anyString(),
                        Mockito.nullable(BigDecimal::class.java),
                        Mockito.nullable(String::class.java),
                        Mockito.nullable(Long::class.java),
                    ),
                ).thenReturn(successResult("c1"))
        }

        runBlocking { engine.closePosition(pos, BigDecimal("90000"), CloseReason.STOP_LOSS) }

        verifyRelease(1)
        runBlocking {
            Mockito.verify(tradeEventService, times(1)).recordPositionClosed(anyPosition(), anyString())
        }
        assertEquals(1, closedCount.get())
    }
}
