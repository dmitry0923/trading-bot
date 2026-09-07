package com.trading.bot.application

import com.trading.bot.client.AlorClient
import com.trading.bot.client.AlorClient.OrderExecution
import com.trading.bot.config.AlorConfig
import com.trading.bot.model.InstrumentType
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.entity.Position
import com.trading.bot.repository.OrderOutboxRepository
import com.trading.bot.repository.PositionRepository
import com.trading.bot.service.DistributedLockService.LeaseFence
import com.trading.bot.service.OrderOutboxService
import com.trading.bot.service.TradeEventService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.util.UUID

/**
 * Fencing (P1-аудит 2026-09-07): [OrderExecutionEngine.placeEntryOrder] обязан
 * прервать вход, если lease распределённого лока потеряна — до резервации слота
 * (fence #1) и непосредственно перед созданием outbox-строки (fence #2),
 * снимая слот при второй проверке.
 *
 * Абсолютную гарантию «1 логический вход → ≤1 физический ордер» даёт уникальный
 * слот [PositionRepository.reserveEntry]; эти тесты проверяют, что протухшая lease
 * НЕ доходит до создания outbox/ордера ни на одном из двух рубежей.
 */
class OrderExecutionEngineLeaseFenceTest {
    private val alorClient = Mockito.mock(AlorClient::class.java)
    private val orderOutboxService = Mockito.mock(OrderOutboxService::class.java)
    private val orderOutboxRepo = Mockito.mock(OrderOutboxRepository::class.java)
    private val positionRepo = Mockito.mock(PositionRepository::class.java)
    private val alorConfig = Mockito.mock(AlorConfig::class.java)
    private val objectMapper = JsonMapper.builder().build()
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

    private fun stubReserve() {
        runBlocking {
            Mockito
                .`when`(positionRepo.reserveEntry(anyString(), anyDirection(), anyLong()))
                .thenReturn(1L)
        }
    }

    private fun stubOutbox() {
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
                ).thenReturn(OrderOutboxService.PlaceOrderResult(UUID.randomUUID(), "alor-1", success = true))
        }
    }

    private fun stubVerifyOrder() {
        runBlocking {
            Mockito
                .`when`(alorClient.verifyOrder(anyString(), Mockito.nullable(BigDecimal::class.java), anyString()))
                .thenReturn(OrderExecution("FILLED", 3, BigDecimal("92000")))
        }
    }

    private fun stubSaveReturnsArg() {
        runBlocking {
            Mockito
                .`when`(positionRepo.save(anyPosition()))
                .thenAnswer { inv -> inv.getArgument<Position>(0) }
        }
    }

    @Suppress("ReplaceCallWithBinaryOperator")
    private fun anyString(): String {
        Mockito.any(String::class.java)
        return "Si"
    }

    @Suppress("ReplaceCallWithBinaryOperator")
    private fun anyPosition(): Position {
        Mockito.any(Position::class.java)
        return pos(null, false, BigDecimal.ZERO, 1)
    }

    @Suppress("ReplaceCallWithBinaryOperator")
    private fun anyBigDecimal(): BigDecimal {
        Mockito.any(BigDecimal::class.java)
        return BigDecimal.ZERO
    }

    @Suppress("ReplaceCallWithBinaryOperator")
    private fun anyDirection(): PositionDirection {
        Mockito.any(PositionDirection::class.java)
        return PositionDirection.LONG
    }

    @Suppress("ReplaceCallWithBinaryOperator")
    private fun anyLong(): Long {
        Mockito.nullable(Long::class.java)
        return 0L
    }

    private fun verifyOutboxNeverCreated() {
        runBlocking {
            Mockito
                .verify(orderOutboxService, Mockito.never())
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

    @Test
    fun `entry aborts before slot reservation when lease fence reports lost`() {
        val fence = Mockito.mock(LeaseFence::class.java)
        runBlocking { Mockito.`when`(fence.isHeld()).thenReturn(false) }

        val result =
            runBlocking {
                engine.placeEntryOrder("Si", PositionDirection.LONG, 3, BigDecimal("92000"), buildPosition = { o, p, pr, q ->
                    pos(o, p, pr, q)
                }, fence = fence)
            }

        assertNull(result, "вход прерывается, если lease потеряна ещё до резервации слота")
        runBlocking { Mockito.verify(positionRepo, Mockito.never()).reserveEntry(anyString(), anyDirection(), anyLong()) }
        verifyOutboxNeverCreated()
    }

    @Test
    fun `entry aborts before outbox when lease fence lost after slot reservation`() {
        val fence = Mockito.mock(LeaseFence::class.java)
        runBlocking {
            // fence #1 (до резервации) — жива; fence #2 (перед outbox) — потеряна.
            Mockito.`when`(fence.isHeld()).thenReturn(true, false)
        }
        stubReserve()

        val result =
            runBlocking {
                engine.placeEntryOrder("Si", PositionDirection.LONG, 3, BigDecimal("92000"), buildPosition = { o, p, pr, q ->
                    pos(o, p, pr, q)
                }, fence = fence)
            }

        assertNull(result, "вход прерывается перед outbox при потере lease после резервации")
        runBlocking {
            Mockito.verify(positionRepo).reserveEntry(anyString(), anyDirection(), anyLong())
            Mockito.verify(positionRepo).releaseEntry(anyString(), anyLong())
        }
        verifyOutboxNeverCreated()
    }

    @Test
    fun `entry proceeds when lease fence is alive`() {
        val fence = Mockito.mock(LeaseFence::class.java)
        runBlocking { Mockito.`when`(fence.isHeld()).thenReturn(true) }
        stubReserve()
        stubOutbox()
        stubVerifyOrder()
        stubSaveReturnsArg()

        val result =
            runBlocking {
                engine.placeEntryOrder("Si", PositionDirection.LONG, 3, BigDecimal("92000"), buildPosition = { o, p, pr, q ->
                    pos(o, p, pr, q)
                }, fence = fence)
            }

        assertNotNull(result, "живая lease — вход проходит до конца (позиция открыта)")
    }

    private fun pos(
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
}
