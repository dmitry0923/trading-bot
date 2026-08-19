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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
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
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Unit-тест атомарного claim позиции на закрытие (EXEC-001, MR-A).
 *
 * Гарантии, проверяемые здесь:
 * - первый [OrderExecutionEngine.closePosition] получает claim и создаёт ровно ОДИН
 *   close-ордер (через outbox);
 * - параллельный/повторный close (claim == false) НЕ создаёт второй ордер, а только
 *   сверяет исполнение уже существующего (confirmCloseFill / resolveCloseViaOutbox);
 * - UNCERTAIN-доставка оставляет [Position.pendingClose] = true (ордер мог дойти до
 *   биржи — реконсилятор доведёт до конца, claim не снимается);
 * - определённый отказ биржи освобождает claim ([PositionRepository.releaseCloseClaim])
 *   — позиция снова закрываема;
 * - двойная финализация одного close-ордера даёт ровно ОДИН recordPositionClosed:
 *   атомарный [PositionRepository.transitionToClosed] (rowsUpdated == 1 только у первого).
 */
class OrderExecutionEngineCloseClaimTest {
    private val alorClient = Mockito.mock(AlorClient::class.java)
    private val orderOutboxService = Mockito.mock(OrderOutboxService::class.java)
    private val orderOutboxRepo = Mockito.mock(OrderOutboxRepository::class.java)
    private val positionRepo = Mockito.mock(PositionRepository::class.java)
    private val alorConfig = Mockito.mock(AlorConfig::class.java)
    private val objectMapper = jacksonObjectMapper()
    private val tradeEventService = Mockito.mock(TradeEventService::class.java)
    private val meterRegistry = SimpleMeterRegistry()

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
            onEntryOpened = {},
            onPositionClosed = {},
            protectionOrdersEnabled = false,
            portfolioResolver = { "D12345" },
        )

    private fun openPos(): Position =
        Position(
            id = 1L,
            ticker = "Si",
            direction = PositionDirection.LONG,
            quantity = 1,
            entryPrice = BigDecimal("90000"),
            currentPrice = BigDecimal("90000"),
            instrumentType = InstrumentType.FUTURES,
            status = PositionStatus.OPEN,
        )

    private fun anyPosition(): Position {
        Mockito.any(Position::class.java)
        return openPos()
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

    private fun anyStatus(): PositionStatus {
        Mockito.any(PositionStatus::class.java)
        return PositionStatus.OPEN
    }

    @Suppress("UNUSED_PARAMETER")
    private fun successResult(orderId: String): OrderOutboxService.PlaceOrderResult =
        OrderOutboxService.PlaceOrderResult(UUID.randomUUID(), orderId, success = true)

    private fun stubFill() {
        runBlocking {
            Mockito
                .`when`(alorClient.verifyOrder(anyString(), anyBigDecimal(), anyString()))
                .thenReturn(AlorClient.OrderExecution(status = "FILLED", filledQuantity = 1, avgPrice = BigDecimal("90100")))
        }
    }

    private var savedPos: Position? = null

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

    private fun stubPlaceOrder(result: OrderOutboxService.PlaceOrderResult) {
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
                ).thenReturn(result)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun verifyPlaceOrder(times: Int) {
        runBlocking {
            Mockito
                .verify(orderOutboxService, times(times))
                .placeOrder(
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
                )
        }
    }

    @Test
    fun `first close claims the position and places exactly one order`() {
        val pos = openPos()
        runBlocking {
            Mockito.`when`(positionRepo.claimForClose(1L)).thenReturn(true)
            Mockito.`when`(positionRepo.findById(1L)).thenReturn(pos)
        }
        stubSaveReturnsArg()
        runBlocking {
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
        stubFill()
        stubPlaceOrder(successResult("c1"))

        runBlocking { engine.closePosition(pos, BigDecimal("90000"), CloseReason.STOP_LOSS) }

        verifyPlaceOrder(1)
        runBlocking {
            Mockito.verify(positionRepo, times(1)).claimForClose(1L)
            Mockito
                .verify(positionRepo, times(1))
                .transitionToClosed(Mockito.anyLong(), anyStatus(), anyBigDecimal(), anyCloseReason(), anyBigDecimal())
            Mockito.verify(tradeEventService, times(1)).recordPositionClosed(anyPosition(), anyString())
        }
    }

    @Test
    fun `concurrent double close creates only one close order and records one close`() {
        val pos = openPos()
        val claimCounter = AtomicInteger(0)
        // Track the latest saved position so findById reflects DB writes.
        // confirmCloseFill now re-reads from DB under mutex — without this,
        // the re-read always sees closeOrderId=null and skips finalization.
        val latestSaved = AtomicReference<Position?>(null)
        runBlocking {
            Mockito.`when`(positionRepo.claimForClose(1L)).thenAnswer { claimCounter.getAndIncrement() == 0 }
            Mockito
                .`when`(positionRepo.findById(1L))
                .thenAnswer { latestSaved.get() ?: pos.copy(pendingClose = true, closeOrderId = null) }
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
        runBlocking {
            Mockito.`when`(positionRepo.save(anyPosition())).thenAnswer { inv ->
                val arg = inv.getArgument<Position>(0)
                latestSaved.set(arg)
                arg
            }
        }
        stubFill()
        stubPlaceOrder(successResult("c1"))

        val barrier = CyclicBarrier(2)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val jobs =
            (1..2).map {
                scope.launch {
                    barrier.await()
                    engine.closePosition(pos.copy(), BigDecimal("90000"), CloseReason.STOP_LOSS)
                }
            }
        runBlocking { jobs.joinAll() }

        verifyPlaceOrder(1)
        runBlocking {
            Mockito.verify(tradeEventService, times(1)).recordPositionClosed(anyPosition(), anyString())
        }
    }

    @Test
    fun `double finalize of the same close order records a single close event`() {
        val pos = openPos()
        val claimCounter = AtomicInteger(0)
        val transitionCounter = AtomicInteger(0)
        val findCalls = AtomicInteger(0)
        runBlocking {
            Mockito.`when`(positionRepo.claimForClose(1L)).thenAnswer { claimCounter.getAndIncrement() == 0 }
            Mockito.`when`(positionRepo.findById(1L)).thenAnswer {
                if (findCalls.incrementAndGet() == 1) {
                    pos.copy()
                } else {
                    pos.copy(pendingClose = true, closeOrderId = "c1")
                }
            }
            Mockito
                .`when`(
                    positionRepo.transitionToClosed(
                        Mockito.anyLong(),
                        anyStatus(),
                        anyBigDecimal(),
                        anyCloseReason(),
                        anyBigDecimal(),
                    ),
                ).thenAnswer { transitionCounter.getAndIncrement() == 0 }
        }
        stubSaveReturnsArg()
        stubFill()
        stubPlaceOrder(successResult("c1"))

        // Поток 1 (claim) создаёт ордер и финализирует (transition -> true).
        // Поток 2 (реконсилятор, claim=false) сверяет тот же close-ордер и пробует
        // финализировать ещё раз (transition -> false) — побочные эффекты НЕ повторяются.
        runBlocking { engine.closePosition(pos, BigDecimal("90000"), CloseReason.STOP_LOSS) }
        runBlocking {
            engine.closePosition(
                pos.copy(pendingClose = true, closeOrderId = "c1"),
                BigDecimal("90000"),
                CloseReason.STOP_LOSS,
            )
        }

        verifyPlaceOrder(1)
        runBlocking {
            Mockito.verify(tradeEventService, times(1)).recordPositionClosed(anyPosition(), anyString())
            Mockito
                .verify(positionRepo, times(2))
                .transitionToClosed(Mockito.anyLong(), anyStatus(), anyBigDecimal(), anyCloseReason(), anyBigDecimal())
        }
    }

    @Test
    fun `repeat close while pending reconciles existing order without placing a new one`() {
        val pos = openPos()
        runBlocking {
            Mockito.`when`(positionRepo.claimForClose(1L)).thenReturn(false)
            Mockito.`when`(positionRepo.findById(1L)).thenReturn(pos.copy(pendingClose = true, closeOrderId = "c1"))
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
        stubFill()

        runBlocking { engine.closePosition(pos, BigDecimal("90000"), CloseReason.STOP_LOSS) }

        runBlocking {
            Mockito.verify(orderOutboxService, never()).placeOrder(
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
            )
            Mockito.verify(tradeEventService, times(1)).recordPositionClosed(anyPosition(), anyString())
        }
    }

    @Test
    fun `uncertain delivery keeps pendingClose so reconciliation can finish the close`() {
        val pos = openPos()
        runBlocking {
            Mockito.`when`(positionRepo.claimForClose(1L)).thenReturn(true)
            Mockito.`when`(positionRepo.findById(1L)).thenReturn(pos)
        }
        stubSaveReturnsArg()
        stubPlaceOrder(OrderOutboxService.PlaceOrderResult(UUID.randomUUID(), null, success = false, uncertain = true))

        runBlocking { engine.closePosition(pos, BigDecimal("90000"), CloseReason.STOP_LOSS) }

        runBlocking { Mockito.verify(positionRepo, times(1)).save(anyPosition()) }
        assertTrue(savedPos!!.pendingClose)
        assertNull(savedPos!!.closeOrderId)
        assertEquals(CloseReason.STOP_LOSS, savedPos!!.closeReason)
        verifyPlaceOrder(1)
    }

    @Test
    fun `definitive rejection releases the claim so position stays closable`() {
        val pos = openPos()
        runBlocking {
            Mockito.`when`(positionRepo.claimForClose(1L)).thenReturn(true)
            Mockito.`when`(positionRepo.findById(1L)).thenReturn(pos)
        }
        stubSaveReturnsArg()
        stubPlaceOrder(OrderOutboxService.PlaceOrderResult(UUID.randomUUID(), null, success = false))

        runBlocking { engine.closePosition(pos, BigDecimal("90000"), CloseReason.STOP_LOSS) }

        runBlocking { Mockito.verify(positionRepo, times(1)).releaseCloseClaim(1L) }
        runBlocking { Mockito.verify(positionRepo, never()).save(anyPosition()) }
        runBlocking { Mockito.verify(tradeEventService, never()).recordPositionClosed(anyPosition(), anyString()) }
        verifyPlaceOrder(1)
    }
}
