package com.trading.bot.service

import com.trading.bot.client.AlorClient
import com.trading.bot.config.AlorConfig
import com.trading.bot.config.DistributedLockConfig
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.entity.OrderOutbox
import com.trading.bot.model.entity.OutboxStatus
import com.trading.bot.model.entity.Position
import com.trading.bot.repository.OrderOutboxRepository
import com.trading.bot.repository.PositionRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigDecimal
import java.util.UUID

/**
 * Unit-тесты outbox-доставки ордеров (источник гарантий защиты от double execution):
 *
 * - первый запрос (retryCount=0) не требует State Reconciliation;
 * - повторная доставка (retryCount>0) всегда предваряется reconcileOrderByIdempotencyKey:
 *   FOUND → markSent без переотправки; UNKNOWN → пропуск (uncertain); NOT_FOUND → повторная
 *   отправка с ТЕМ ЖЕ idempotency key;
 * - bounded retry: worker использует alorConfig.maxOrderRetries;
 * - определённый отказ (null orderNumber) → FAILED без uncertain; сетевой сбой → FAILED + uncertain.
 */
class OrderOutboxServiceTest {
    private val outboxRepo = Mockito.mock(OrderOutboxRepository::class.java)
    private val positionRepo = Mockito.mock(PositionRepository::class.java)
    private val alorClient = Mockito.mock(AlorClient::class.java)
    private val alorConfig = AlorConfig().apply { maxOrderRetries = 3 }
    private val objectMapper = jacksonObjectMapper()
    private val meterRegistry = SimpleMeterRegistry()
    private val distributedLockConfig = DistributedLockConfig().apply { enabled = false }
    private val distributedLockService =
        DistributedLockService(distributedLockConfig, Mockito.mock(ReactiveStringRedisTemplate::class.java), meterRegistry)
    private val tradingAccountService = Mockito.mock(TradingAccountService::class.java)
    private val outboxPublisher = Mockito.mock(OrderOutboxPublisher::class.java)
    private val service =
        OrderOutboxService(
            outboxRepo,
            positionRepo,
            alorClient,
            alorConfig,
            objectMapper,
            meterRegistry,
            distributedLockService,
            distributedLockConfig,
            tradingAccountService,
            outboxPublisher,
        )

    @BeforeEach
    fun setUp() {
        runBlocking {
            Mockito.`when`(tradingAccountService.portfolioOf(Mockito.isNull<Long>())).thenReturn(alorConfig.portfolio)
        }
    }

    private fun anyUuid(): UUID {
        Mockito.any(UUID::class.java)
        return UUID.randomUUID()
    }

    private fun anyBigDecimal(): BigDecimal {
        Mockito.any(BigDecimal::class.java)
        return BigDecimal.ZERO
    }

    private fun anyOutbox(): OrderOutbox {
        Mockito.any(OrderOutbox::class.java)
        return OrderOutbox(payloadJson = "")
    }

    private suspend fun stubSaveReturning(id: UUID) {
        Mockito.`when`(outboxRepo.save(anyOutbox())).thenAnswer { inv ->
            inv.getArgument<OrderOutbox>(0).copy(id = id)
        }
    }

    private suspend fun stubMarkSentRecording(sentOrders: MutableList<String>) {
        Mockito.`when`(outboxRepo.markSent(anyUuid(), Mockito.anyString())).thenAnswer { inv ->
            sentOrders += inv.getArgument<String>(1)
            null
        }
    }

    private suspend fun stubMarkFailedRecording(failedErrors: MutableList<String>) {
        Mockito.`when`(outboxRepo.markFailed(anyUuid(), Mockito.anyString())).thenAnswer { inv ->
            failedErrors += inv.getArgument<String>(1)
            null
        }
    }

    private fun outboxRow(
        retryCount: Int,
        key: String,
        type: String,
        price: String?,
        positionId: Long? = null,
        qty: Int = 1,
        side: String = "sell",
    ): OrderOutbox {
        val payload =
            objectMapper.writeValueAsString(
                mapOf(
                    "ticker" to "Si",
                    "side" to side,
                    "qty" to qty,
                    "price" to price,
                    "type" to type,
                    "idempotencyKey" to key,
                    "positionId" to positionId,
                    "closeReason" to "STOP_LOSS",
                ),
            )
        return OrderOutbox(
            id = UUID.randomUUID(),
            payloadJson = payload,
            status = OutboxStatus.FAILED,
            idempotencyKey = key,
            retryCount = retryCount,
            positionId = positionId,
        )
    }

    private suspend fun stubRetryable(rows: List<OrderOutbox>) {
        Mockito
            .`when`(
                outboxRepo.claimRetryable(
                    Mockito.anyInt(),
                    Mockito.anyInt(),
                    Mockito.anyInt(),
                    Mockito.anyInt(),
                    Mockito.anyInt(),
                ),
            ).thenReturn(rows)
    }

    private fun openPosition(): Position =
        Position(
            id = 1L,
            ticker = "Si",
            direction = PositionDirection.LONG,
            quantity = 10,
            entryPrice = BigDecimal("100"),
            status = PositionStatus.OPEN,
            pendingClose = true,
        )

    private suspend fun stubCloseReconcilePositions(exchangeQty: Long) {
        Mockito
            .`when`(positionRepo.findById(1L))
            .thenReturn(openPosition())
        Mockito
            .`when`(alorClient.getPositions(Mockito.anyString()))
            .thenReturn(
                AlorClient.ReconcileResult.Ok(
                    listOf(AlorClient.ExchangePosition(ticker = "Si", qty = exchangeQty, avgPrice = BigDecimal("100"))),
                ),
            )
    }

    private suspend fun stubReconcile(result: AlorClient.OrderReconciliation) {
        Mockito
            .`when`(
                alorClient.reconcileOrderByIdempotencyKey(
                    Mockito.anyString(),
                    Mockito.anyString(),
                    Mockito.anyString(),
                    Mockito.anyString(),
                ),
            ).thenReturn(result)
    }

    @Test
    fun `first send places limit order and marks sent without reconciliation`() {
        val outboxId = UUID.randomUUID()
        val sentOrders = mutableListOf<String>()
        runBlocking {
            stubSaveReturning(outboxId)
            Mockito
                .`when`(
                    alorClient.placeLimitOrder(
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.anyInt(),
                        anyBigDecimal(),
                        Mockito.anyString(),
                        Mockito.anyString(),
                    ),
                ).thenReturn("ord-1")
            stubMarkSentRecording(sentOrders)

            val result = service.placeOrder("Si", "buy", 1, BigDecimal("92000"), "limit")

            assertTrue(result.success)
            assertEquals("ord-1", result.alorOrderId)
            Mockito
                .verify(alorClient, Mockito.never())
                .reconcileOrderByIdempotencyKey(Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyString())
            Mockito
                .verify(outboxRepo)
                .markSent(anyUuid(), Mockito.anyString())
        }
        assertEquals(listOf("ord-1"), sentOrders)
    }

    @Test
    fun `market type routes to placeMarketOrder`() {
        val outboxId = UUID.randomUUID()
        val sentOrders = mutableListOf<String>()
        runBlocking {
            stubSaveReturning(outboxId)
            Mockito
                .`when`(
                    alorClient.placeMarketOrder(
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.anyInt(),
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.anyBoolean(),
                    ),
                ).thenReturn("ord-m")
            stubMarkSentRecording(sentOrders)

            val result = service.placeOrder("Si", "sell", 1, null, "market")

            assertTrue(result.success)
            assertEquals("ord-m", result.alorOrderId)
            Mockito
                .verify(alorClient, Mockito.never())
                .placeLimitOrder(
                    Mockito.anyString(),
                    Mockito.anyString(),
                    Mockito.anyInt(),
                    anyBigDecimal(),
                    Mockito.anyString(),
                    Mockito.anyString(),
                )
        }
        assertEquals(listOf("ord-m"), sentOrders)
    }

    @Test
    fun `liquidation market close passes forceMarket true`() {
        val outboxId = UUID.randomUUID()
        runBlocking {
            stubSaveReturning(outboxId)
            Mockito
                .`when`(
                    alorClient.placeMarketOrder(
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.anyInt(),
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.eq(true),
                    ),
                ).thenReturn("ord-m")
            stubMarkSentRecording(mutableListOf())

            val result =
                service.placeOrder(
                    "Si",
                    "sell",
                    1,
                    null,
                    "market",
                    closeReason = "LIQUIDATION_CRITICAL",
                )

            assertTrue(result.success)
            assertEquals("ord-m", result.alorOrderId)
        }
    }

    @Test
    fun `emergency stop market close passes forceMarket true`() {
        val outboxId = UUID.randomUUID()
        runBlocking {
            stubSaveReturning(outboxId)
            Mockito
                .`when`(
                    alorClient.placeMarketOrder(
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.anyInt(),
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.eq(true),
                    ),
                ).thenReturn("ord-m")
            stubMarkSentRecording(mutableListOf())

            val result =
                service.placeOrder(
                    "Si",
                    "sell",
                    1,
                    null,
                    "market",
                    closeReason = "EMERGENCY_STOP",
                )

            assertTrue(result.success)
            assertEquals("ord-m", result.alorOrderId)
        }
    }

    @Test
    fun `regular stop loss market close does not force market`() {
        val outboxId = UUID.randomUUID()
        runBlocking {
            stubSaveReturning(outboxId)
            Mockito
                .`when`(
                    alorClient.placeMarketOrder(
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.anyInt(),
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.eq(false),
                    ),
                ).thenReturn("ord-m")
            stubMarkSentRecording(mutableListOf())

            val result =
                service.placeOrder(
                    "Si",
                    "sell",
                    1,
                    null,
                    "market",
                    closeReason = "STOP_LOSS",
                )

            assertTrue(result.success)
            assertEquals("ord-m", result.alorOrderId)
        }
    }

    @Test
    fun `definitive rejection is failed but not uncertain`() {
        val outboxId = UUID.randomUUID()
        val failedErrors = mutableListOf<String>()
        runBlocking {
            stubSaveReturning(outboxId)
            stubMarkFailedRecording(failedErrors)

            val result = service.placeOrder("Si", "buy", 1, BigDecimal("92000"), "limit")

            assertFalse(result.success)
            assertNull(result.alorOrderId)
            assertFalse(result.uncertain)
            Mockito.verify(outboxRepo).markFailed(anyUuid(), Mockito.anyString())
        }
        assertEquals(listOf("Order rejected by Alor (no orderNumber)"), failedErrors)
    }

    @Test
    fun `delivery exception is failed and uncertain`() {
        val outboxId = UUID.randomUUID()
        val failedErrors = mutableListOf<String>()
        runBlocking {
            stubSaveReturning(outboxId)
            Mockito
                .`when`(
                    alorClient.placeLimitOrder(
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.anyInt(),
                        anyBigDecimal(),
                        Mockito.anyString(),
                        Mockito.anyString(),
                    ),
                ).thenThrow(RuntimeException("network timeout"))
            stubMarkFailedRecording(failedErrors)

            val result = service.placeOrder("Si", "buy", 1, BigDecimal("92000"), "limit")

            assertFalse(result.success)
            assertNull(result.alorOrderId)
            assertTrue(result.uncertain)
            Mockito.verify(outboxRepo).markFailed(anyUuid(), Mockito.anyString())
        }
        assertEquals(listOf("network timeout"), failedErrors)
    }

    @Test
    fun `reconcile FOUND marks sent without re-sending`() {
        val outbox = outboxRow(retryCount = 1, key = "idem-1", type = "limit", price = "92000")
        val sentOrders = mutableListOf<String>()
        runBlocking {
            stubRetryable(listOf(outbox))
            stubReconcile(AlorClient.OrderReconciliation.Found("ord-9", 1, BigDecimal("92000")))
            stubMarkSentRecording(sentOrders)

            service.processPending()

            Mockito
                .verify(alorClient, Mockito.timeout(3000))
                .reconcileOrderByIdempotencyKey(Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyString())
            Mockito
                .verify(outboxRepo, Mockito.timeout(3000))
                .markSent(anyUuid(), Mockito.anyString())
            Mockito
                .verify(alorClient, Mockito.never())
                .placeLimitOrder(
                    Mockito.anyString(),
                    Mockito.anyString(),
                    Mockito.anyInt(),
                    anyBigDecimal(),
                    Mockito.anyString(),
                    Mockito.anyString(),
                )
            Mockito
                .verify(alorClient, Mockito.never())
                .placeMarketOrder(
                    Mockito.anyString(),
                    Mockito.anyString(),
                    Mockito.anyInt(),
                    Mockito.anyString(),
                    Mockito.anyString(),
                    Mockito.anyBoolean(),
                )
        }
        assertEquals(listOf("ord-9"), sentOrders)
    }

    @Test
    fun `reconcile UNKNOWN skips re-send and is uncertain`() {
        val outbox = outboxRow(retryCount = 1, key = "idem-1", type = "limit", price = "92000")
        runBlocking {
            stubRetryable(listOf(outbox))
            stubReconcile(AlorClient.OrderReconciliation.Unknown)

            service.processPending()

            Mockito
                .verify(alorClient, Mockito.timeout(3000))
                .reconcileOrderByIdempotencyKey(Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyString())
            Mockito
                .verify(alorClient, Mockito.never())
                .placeLimitOrder(
                    Mockito.anyString(),
                    Mockito.anyString(),
                    Mockito.anyInt(),
                    anyBigDecimal(),
                    Mockito.anyString(),
                    Mockito.anyString(),
                )
            Mockito
                .verify(alorClient, Mockito.never())
                .placeMarketOrder(
                    Mockito.anyString(),
                    Mockito.anyString(),
                    Mockito.anyInt(),
                    Mockito.anyString(),
                    Mockito.anyString(),
                    Mockito.anyBoolean(),
                )
            Mockito.verify(outboxRepo, Mockito.never()).markSent(anyUuid(), Mockito.anyString())
        }
    }

    @Test
    fun `reconcile NOT_FOUND re-sends with the same idempotency key`() {
        val outbox = outboxRow(retryCount = 1, key = "idem-1", type = "limit", price = "92000")
        val usedKeys = mutableListOf<String>()
        val sentOrders = mutableListOf<String>()
        runBlocking {
            stubRetryable(listOf(outbox))
            stubReconcile(AlorClient.OrderReconciliation.NotFound)
            Mockito
                .`when`(
                    alorClient.placeLimitOrder(
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.anyInt(),
                        anyBigDecimal(),
                        Mockito.anyString(),
                        Mockito.anyString(),
                    ),
                ).thenAnswer { inv ->
                    usedKeys += inv.getArgument<String>(4)
                    "ord-2"
                }
            stubMarkSentRecording(sentOrders)

            service.processPending()

            Mockito
                .verify(alorClient, Mockito.timeout(3000))
                .placeLimitOrder(
                    Mockito.anyString(),
                    Mockito.anyString(),
                    Mockito.anyInt(),
                    anyBigDecimal(),
                    Mockito.anyString(),
                    Mockito.anyString(),
                )
            Mockito
                .verify(outboxRepo, Mockito.timeout(3000))
                .markSent(anyUuid(), Mockito.anyString())
            Mockito
                .verify(alorClient, Mockito.timeout(3000))
                .reconcileOrderByIdempotencyKey(Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyString())
        }
        assertEquals(listOf("idem-1"), usedKeys)
        assertEquals(listOf("ord-2"), sentOrders)
    }

    @Test
    fun `process pending respects maxOrderRetries bound`() {
        runBlocking {
            stubRetryable(emptyList())

            service.processPending()

            Mockito
                .verify(outboxRepo, Mockito.timeout(3000))
                .claimRetryable(eq(3), Mockito.anyInt(), eq(10), eq(120), Mockito.anyInt())
        }
    }

    @Test
    fun `reconcile NOT_FOUND but close confirmed by position delta does not re-send`() {
        val outbox = outboxRow(retryCount = 1, key = "idem-c1", type = "market", price = null, positionId = 1L, qty = 6)
        val sentOrders = mutableListOf<String>()
        runBlocking {
            stubRetryable(listOf(outbox))
            stubReconcile(AlorClient.OrderReconciliation.NotFound)
            stubCloseReconcilePositions(exchangeQty = 4L)
            stubMarkSentRecording(sentOrders)

            service.processPending()

            Mockito
                .verify(alorClient, Mockito.timeout(3000))
                .getPositions(Mockito.anyString())
            Mockito
                .verify(alorClient, Mockito.never())
                .placeMarketOrder(
                    Mockito.anyString(),
                    Mockito.anyString(),
                    Mockito.anyInt(),
                    Mockito.anyString(),
                    Mockito.anyString(),
                    Mockito.anyBoolean(),
                )
            Mockito
                .verify(outboxRepo, Mockito.timeout(3000))
                .markSent(anyUuid(), Mockito.anyString())
        }
        // Сентится fallback orderNumber = idempotency key, а не реальный ордер.
        assertEquals(listOf("idem-c1"), sentOrders)
    }

    @Test
    fun `reconcile NOT_FOUND with partially reduced position skips re-send as uncertain`() {
        val outbox = outboxRow(retryCount = 1, key = "idem-c2", type = "market", price = null, positionId = 1L, qty = 6)
        runBlocking {
            stubRetryable(listOf(outbox))
            stubReconcile(AlorClient.OrderReconciliation.NotFound)
            stubCloseReconcilePositions(exchangeQty = 7L)

            service.processPending()

            Mockito
                .verify(alorClient, Mockito.timeout(3000))
                .getPositions(Mockito.anyString())
            Mockito
                .verify(alorClient, Mockito.never())
                .placeMarketOrder(
                    Mockito.anyString(),
                    Mockito.anyString(),
                    Mockito.anyInt(),
                    Mockito.anyString(),
                    Mockito.anyString(),
                    Mockito.anyBoolean(),
                )
            Mockito.verify(outboxRepo, Mockito.never()).markSent(anyUuid(), Mockito.anyString())
        }
    }

    @Test
    fun `reconcile NOT_FOUND with unchanged position re-sends close order`() {
        val outbox = outboxRow(retryCount = 1, key = "idem-c3", type = "market", price = null, positionId = 1L, qty = 6)
        val sentOrders = mutableListOf<String>()
        runBlocking {
            stubRetryable(listOf(outbox))
            stubReconcile(AlorClient.OrderReconciliation.NotFound)
            stubCloseReconcilePositions(exchangeQty = 10L)
            Mockito
                .`when`(
                    alorClient.placeMarketOrder(
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.anyInt(),
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.anyBoolean(),
                    ),
                ).thenReturn("ord-c3")
            stubMarkSentRecording(sentOrders)

            service.processPending()

            Mockito
                .verify(alorClient, Mockito.timeout(3000))
                .placeMarketOrder(
                    Mockito.anyString(),
                    Mockito.anyString(),
                    Mockito.anyInt(),
                    Mockito.anyString(),
                    Mockito.anyString(),
                    Mockito.anyBoolean(),
                )
            Mockito
                .verify(outboxRepo, Mockito.timeout(3000))
                .markSent(anyUuid(), Mockito.anyString())
        }
        assertEquals(listOf("ord-c3"), sentOrders)
    }

    @Test
    fun `reconcile NOT_FOUND with failed positions REST skips re-send as uncertain`() {
        val outbox = outboxRow(retryCount = 1, key = "idem-c4", type = "market", price = null, positionId = 1L, qty = 6)
        runBlocking {
            Mockito.`when`(positionRepo.findById(1L)).thenReturn(openPosition())
            Mockito
                .`when`(alorClient.getPositions(Mockito.anyString()))
                .thenReturn(AlorClient.ReconcileResult.Failed)
            stubRetryable(listOf(outbox))
            stubReconcile(AlorClient.OrderReconciliation.NotFound)

            service.processPending()

            Mockito
                .verify(alorClient, Mockito.timeout(3000))
                .getPositions(Mockito.anyString())
            Mockito
                .verify(alorClient, Mockito.never())
                .placeMarketOrder(
                    Mockito.anyString(),
                    Mockito.anyString(),
                    Mockito.anyInt(),
                    Mockito.anyString(),
                    Mockito.anyString(),
                    Mockito.anyBoolean(),
                )
            Mockito.verify(outboxRepo, Mockito.never()).markSent(anyUuid(), Mockito.anyString())
        }
    }

    @Test
    fun `placeOrder publishes outbox id after save`() {
        val outboxId = UUID.randomUUID()
        runBlocking {
            stubSaveReturning(outboxId)
            Mockito
                .`when`(
                    alorClient.placeLimitOrder(
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.anyInt(),
                        anyBigDecimal(),
                        Mockito.anyString(),
                        Mockito.anyString(),
                    ),
                ).thenReturn("ord-1")
            stubMarkSentRecording(mutableListOf())

            service.placeOrder("Si", "buy", 1, BigDecimal("92000"), "limit")

            Mockito.verify(outboxPublisher).publish(outboxId)
        }
    }

    @Test
    fun `rabbit redispatch on PENDING sends and marks sent`() {
        val outbox =
            outboxRow(retryCount = 0, key = "idem-rabbit", type = "limit", price = "92000")
                .copy(status = OutboxStatus.PENDING)
        val sentOrders = mutableListOf<String>()
        runBlocking {
            Mockito.`when`(outboxRepo.findById(checkNotNull(outbox.id))).thenReturn(outbox)
            Mockito
                .`when`(
                    alorClient.placeLimitOrder(
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.anyInt(),
                        anyBigDecimal(),
                        Mockito.anyString(),
                        Mockito.anyString(),
                    ),
                ).thenReturn("ord-rabbit")
            stubMarkSentRecording(sentOrders)

            val result = service.redispatchById(checkNotNull(outbox.id))

            assertTrue(result.success)
            assertEquals("ord-rabbit", result.alorOrderId)
            Mockito
                .verify(alorClient)
                .placeLimitOrder(
                    Mockito.anyString(),
                    Mockito.anyString(),
                    Mockito.anyInt(),
                    anyBigDecimal(),
                    Mockito.anyString(),
                    Mockito.anyString(),
                )
        }
        assertEquals(listOf("ord-rabbit"), sentOrders)
    }

    @Test
    fun `rabbit redispatch on SENT acks without re-sending`() {
        val outbox =
            outboxRow(retryCount = 1, key = "idem-rabbit2", type = "limit", price = "92000")
                .copy(status = OutboxStatus.SENT, alorOrderId = "ord-existing")
        runBlocking {
            Mockito.`when`(outboxRepo.findById(checkNotNull(outbox.id))).thenReturn(outbox)

            val result = service.redispatchById(checkNotNull(outbox.id))

            assertTrue(result.success)
            assertEquals("ord-existing", result.alorOrderId)
            Mockito
                .verify(alorClient, Mockito.never())
                .placeLimitOrder(
                    Mockito.anyString(),
                    Mockito.anyString(),
                    Mockito.anyInt(),
                    anyBigDecimal(),
                    Mockito.anyString(),
                    Mockito.anyString(),
                )
            Mockito.verify(outboxRepo, Mockito.never()).markSent(anyUuid(), Mockito.anyString())
        }
    }

    @Test
    fun `rabbit redispatch on FAILED skips - DB worker owns retries`() {
        val outbox = outboxRow(retryCount = 1, key = "idem-rabbit3", type = "limit", price = "92000")
        runBlocking {
            Mockito.`when`(outboxRepo.findById(checkNotNull(outbox.id))).thenReturn(outbox)

            val result = service.redispatchById(checkNotNull(outbox.id))

            assertFalse(result.success)
            Mockito
                .verify(alorClient, Mockito.never())
                .placeLimitOrder(
                    Mockito.anyString(),
                    Mockito.anyString(),
                    Mockito.anyInt(),
                    anyBigDecimal(),
                    Mockito.anyString(),
                    Mockito.anyString(),
                )
            Mockito.verify(outboxRepo, Mockito.never()).markSent(anyUuid(), Mockito.anyString())
        }
    }

    @Test
    fun `rabbit redispatch on PROCESSING skips - row is in-flight by DB worker`() {
        val outbox =
            outboxRow(retryCount = 0, key = "idem-rabbit4", type = "limit", price = "92000")
                .copy(status = OutboxStatus.PROCESSING)
        runBlocking {
            Mockito.`when`(outboxRepo.findById(checkNotNull(outbox.id))).thenReturn(outbox)

            val result = service.redispatchById(checkNotNull(outbox.id))

            assertFalse(result.success)
            Mockito
                .verify(alorClient, Mockito.never())
                .placeLimitOrder(
                    Mockito.anyString(),
                    Mockito.anyString(),
                    Mockito.anyInt(),
                    anyBigDecimal(),
                    Mockito.anyString(),
                    Mockito.anyString(),
                )
            Mockito.verify(outboxRepo, Mockito.never()).markSent(anyUuid(), Mockito.anyString())
        }
    }

    @Test
    fun `rabbit redispatch on missing row returns failure`() {
        val missingId = UUID.randomUUID()
        runBlocking {
            Mockito.`when`(outboxRepo.findById(missingId)).thenReturn(null)

            val result = service.redispatchById(missingId)

            assertFalse(result.success)
            Mockito
                .verify(alorClient, Mockito.never())
                .placeLimitOrder(
                    Mockito.anyString(),
                    Mockito.anyString(),
                    Mockito.anyInt(),
                    anyBigDecimal(),
                    Mockito.anyString(),
                    Mockito.anyString(),
                )
        }
    }

    @Test
    fun `stop type routes to placeStopOrder with stopPrice`() {
        val outboxId = UUID.randomUUID()
        val sentOrders = mutableListOf<String>()
        runBlocking {
            stubSaveReturning(outboxId)
            Mockito
                .`when`(
                    alorClient.placeStopOrder(
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.anyInt(),
                        anyBigDecimal(),
                        Mockito.anyString(),
                        Mockito.anyString(),
                    ),
                ).thenReturn("ord-stop")
            stubMarkSentRecording(sentOrders)

            val result = service.placeOrder("Si", "sell", 2, null, "stop", stopPrice = BigDecimal("91500"))

            assertTrue(result.success)
            assertEquals("ord-stop", result.alorOrderId)
            Mockito
                .verify(alorClient)
                .placeStopOrder(
                    Mockito.anyString(),
                    Mockito.anyString(),
                    Mockito.eq(2),
                    eq(BigDecimal("91500")),
                    Mockito.anyString(),
                    Mockito.anyString(),
                )
            Mockito
                .verify(alorClient, Mockito.never())
                .placeLimitOrder(
                    Mockito.anyString(),
                    Mockito.anyString(),
                    Mockito.anyInt(),
                    anyBigDecimal(),
                    Mockito.anyString(),
                    Mockito.anyString(),
                )
            Mockito
                .verify(alorClient, Mockito.never())
                .placeMarketOrder(
                    Mockito.anyString(),
                    Mockito.anyString(),
                    Mockito.anyInt(),
                    Mockito.anyString(),
                    Mockito.anyString(),
                    Mockito.anyBoolean(),
                )
        }
        assertEquals(listOf("ord-stop"), sentOrders)
    }

    @Test
    fun `take-profit type routes to placeTakeProfitOrder with stopPrice`() {
        val outboxId = UUID.randomUUID()
        val sentOrders = mutableListOf<String>()
        runBlocking {
            stubSaveReturning(outboxId)
            Mockito
                .`when`(
                    alorClient.placeTakeProfitOrder(
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.anyInt(),
                        anyBigDecimal(),
                        Mockito.anyString(),
                        Mockito.anyString(),
                    ),
                ).thenReturn("ord-tp")
            stubMarkSentRecording(sentOrders)

            val result = service.placeOrder("Si", "buy", 1, null, "take-profit", stopPrice = BigDecimal("92500"))

            assertTrue(result.success)
            assertEquals("ord-tp", result.alorOrderId)
            Mockito
                .verify(alorClient)
                .placeTakeProfitOrder(
                    Mockito.anyString(),
                    Mockito.anyString(),
                    Mockito.eq(1),
                    eq(BigDecimal("92500")),
                    Mockito.anyString(),
                    Mockito.anyString(),
                )
        }
        assertEquals(listOf("ord-tp"), sentOrders)
    }

    @Test
    fun `stop type without stopPrice marks failed`() {
        val outboxId = UUID.randomUUID()
        val failedErrors = mutableListOf<String>()
        runBlocking {
            stubSaveReturning(outboxId)
            stubMarkFailedRecording(failedErrors)

            val result = service.placeOrder("Si", "sell", 1, null, "stop")

            assertFalse(result.success)
            Mockito
                .verify(alorClient, Mockito.never())
                .placeStopOrder(
                    Mockito.anyString(),
                    Mockito.anyString(),
                    Mockito.anyInt(),
                    anyBigDecimal(),
                    Mockito.anyString(),
                    Mockito.anyString(),
                )
        }
        assertEquals(listOf("Order rejected by Alor (no orderNumber)"), failedErrors)
    }

    @Test
    fun `placeCancelOrder confirmed marks sent with cancelled and publishes`() {
        val outboxId = UUID.randomUUID()
        val sentOrders = mutableListOf<String>()
        runBlocking {
            stubSaveReturning(outboxId)
            Mockito
                .`when`(alorClient.cancelOrder(Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
                .thenReturn(AlorClient.CancelResult.CONFIRMED)
            stubMarkSentRecording(sentOrders)

            val result = service.placeCancelOrder(positionId = 5L, orderId = "order-123")

            assertTrue(result.success)
            assertEquals("cancelled", result.alorOrderId)
            Mockito.verify(outboxPublisher).publish(outboxId)
            Mockito.verify(alorClient).cancelOrder(eq("order-123"), Mockito.anyString(), Mockito.anyString())
        }
        assertEquals(listOf("cancelled"), sentOrders)
    }

    @Test
    fun `cancel REJECTED is treated as cancelled`() {
        val outboxId = UUID.randomUUID()
        runBlocking {
            stubSaveReturning(outboxId)
            Mockito
                .`when`(alorClient.cancelOrder(Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
                .thenReturn(AlorClient.CancelResult.REJECTED)
            stubMarkSentRecording(mutableListOf())

            val result = service.placeCancelOrder(positionId = 5L, orderId = "order-456")

            assertTrue(result.success)
            assertEquals("cancelled", result.alorOrderId)
        }
    }

    @Test
    fun `cancel UNCERTAIN marks failed definitive`() {
        val outboxId = UUID.randomUUID()
        runBlocking {
            stubSaveReturning(outboxId)
            Mockito
                .`when`(alorClient.cancelOrder(Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
                .thenReturn(AlorClient.CancelResult.UNCERTAIN)
            stubMarkFailedRecording(mutableListOf())

            val result = service.placeCancelOrder(positionId = 5L, orderId = "order-789")

            assertFalse(result.success)
            assertNull(result.alorOrderId)
            assertFalse(result.uncertain)
            Mockito.verify(outboxRepo).markFailed(anyUuid(), Mockito.anyString())
        }
    }

    @Test
    fun `unknown order type marks failed without touching exchange`() {
        val outbox = outboxRow(retryCount = 0, key = "idem-weird", type = "weird", price = "92000")
        val failedErrors = mutableListOf<String>()
        runBlocking {
            stubRetryable(listOf(outbox))
            stubMarkFailedRecording(failedErrors)

            service.processPending()

            Mockito
                .verify(outboxRepo, Mockito.timeout(3000))
                .markFailed(anyUuid(), Mockito.anyString())
            Mockito
                .verify(alorClient, Mockito.never())
                .placeLimitOrder(
                    Mockito.anyString(),
                    Mockito.anyString(),
                    Mockito.anyInt(),
                    anyBigDecimal(),
                    Mockito.anyString(),
                    Mockito.anyString(),
                )
            Mockito
                .verify(alorClient, Mockito.never())
                .placeMarketOrder(
                    Mockito.anyString(),
                    Mockito.anyString(),
                    Mockito.anyInt(),
                    Mockito.anyString(),
                    Mockito.anyString(),
                    Mockito.anyBoolean(),
                )
            Mockito
                .verify(alorClient, Mockito.never())
                .placeStopOrder(
                    Mockito.anyString(),
                    Mockito.anyString(),
                    Mockito.anyInt(),
                    anyBigDecimal(),
                    Mockito.anyString(),
                    Mockito.anyString(),
                )
            Mockito
                .verify(alorClient, Mockito.never())
                .cancelOrder(Mockito.anyString(), Mockito.anyString(), Mockito.anyString())
        }
        assertEquals(listOf("Order rejected by Alor (no orderNumber)"), failedErrors)
    }

    @Test
    fun `malformed payload is caught by worker without crashing`() {
        val outbox =
            OrderOutbox(
                id = UUID.randomUUID(),
                payloadJson = "not-a-valid-json",
                status = OutboxStatus.FAILED,
                idempotencyKey = "idem-bad",
                retryCount = 0,
            )
        runBlocking {
            stubRetryable(listOf(outbox))

            service.processPending()

            Mockito
                .verify(outboxRepo, Mockito.timeout(3000))
                .claimRetryable(Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt())
            Mockito.verify(outboxRepo, Mockito.never()).markFailed(anyUuid(), Mockito.anyString())
            Mockito
                .verify(alorClient, Mockito.never())
                .placeLimitOrder(
                    Mockito.anyString(),
                    Mockito.anyString(),
                    Mockito.anyInt(),
                    anyBigDecimal(),
                    Mockito.anyString(),
                    Mockito.anyString(),
                )
        }
    }

    @Test
    fun `dispatch throws when outbox row has no id`() {
        runBlocking {
            Mockito.`when`(outboxRepo.save(anyOutbox())).thenReturn(OrderOutbox(payloadJson = ""))
            Mockito
                .`when`(
                    alorClient.placeLimitOrder(
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.anyInt(),
                        anyBigDecimal(),
                        Mockito.anyString(),
                        Mockito.anyString(),
                    ),
                ).thenReturn("ord-1")
        }

        assertThrows(IllegalStateException::class.java) {
            runBlocking { service.placeOrder("Si", "buy", 1, BigDecimal("92000"), "limit") }
        }

        runBlocking {
            Mockito.verify(outboxPublisher, Mockito.never()).publish(any())
            Mockito.verify(outboxRepo, Mockito.never()).markSent(anyUuid(), Mockito.anyString())
        }
    }

    @Test
    fun `resolvePortfolio uses outbox accountId column`() {
        val outboxId = UUID.randomUUID()
        val portfolios = mutableListOf<String>()
        runBlocking {
            Mockito.`when`(tradingAccountService.portfolioOf(7L)).thenReturn("PORTFOLIO-7")
            stubSaveReturning(outboxId)
            Mockito
                .`when`(
                    alorClient.placeLimitOrder(
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.anyInt(),
                        anyBigDecimal(),
                        Mockito.anyString(),
                        Mockito.anyString(),
                    ),
                ).thenAnswer { inv ->
                    portfolios += inv.getArgument<String>(5)
                    "ord"
                }
            stubMarkSentRecording(mutableListOf())

            service.placeOrder("Si", "buy", 1, BigDecimal("92000"), "limit", accountId = 7L)
        }
        assertEquals(listOf("PORTFOLIO-7"), portfolios)
    }

    @Test
    fun `resolvePortfolio falls back to payload accountId for legacy rows`() {
        val payload =
            objectMapper.writeValueAsString(
                mapOf(
                    "ticker" to "Si",
                    "side" to "buy",
                    "qty" to 1,
                    "price" to "92000",
                    "type" to "limit",
                    "idempotencyKey" to "legacy-account",
                    "accountId" to 42L,
                ),
            )
        val outbox =
            OrderOutbox(
                id = UUID.randomUUID(),
                payloadJson = payload,
                status = OutboxStatus.FAILED,
                idempotencyKey = "legacy-account",
                retryCount = 0,
            )
        val portfolios = mutableListOf<String>()
        runBlocking {
            Mockito.`when`(tradingAccountService.portfolioOf(42L)).thenReturn("PORTFOLIO-42")
            stubRetryable(listOf(outbox))
            Mockito
                .`when`(
                    alorClient.placeLimitOrder(
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.anyInt(),
                        anyBigDecimal(),
                        Mockito.anyString(),
                        Mockito.anyString(),
                    ),
                ).thenAnswer { inv ->
                    portfolios += inv.getArgument<String>(5)
                    "ord"
                }
            stubMarkSentRecording(mutableListOf())

            service.processPending()

            Mockito.verify(tradingAccountService, Mockito.timeout(3000)).portfolioOf(42L)
            Mockito
                .verify(alorClient, Mockito.timeout(3000))
                .placeLimitOrder(
                    Mockito.anyString(),
                    Mockito.anyString(),
                    Mockito.anyInt(),
                    anyBigDecimal(),
                    Mockito.anyString(),
                    Mockito.anyString(),
                )
        }
        assertEquals(listOf("PORTFOLIO-42"), portfolios)
    }

    @Test
    fun `resolvePortfolio falls back to position accountId`() {
        val payload =
            objectMapper.writeValueAsString(
                mapOf(
                    "ticker" to "Si",
                    "side" to "buy",
                    "qty" to 1,
                    "price" to "92000",
                    "type" to "limit",
                    "idempotencyKey" to "legacy-pos",
                    "positionId" to 1L,
                ),
            )
        val outbox =
            OrderOutbox(
                id = UUID.randomUUID(),
                payloadJson = payload,
                status = OutboxStatus.FAILED,
                idempotencyKey = "legacy-pos",
                retryCount = 0,
                positionId = 1L,
            )
        val portfolios = mutableListOf<String>()
        runBlocking {
            Mockito.`when`(tradingAccountService.portfolioOf(9L)).thenReturn("PORTFOLIO-9")
            Mockito.`when`(positionRepo.findById(1L)).thenReturn(openPosition().copy(accountId = 9L))
            stubRetryable(listOf(outbox))
            Mockito
                .`when`(
                    alorClient.placeLimitOrder(
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.anyInt(),
                        anyBigDecimal(),
                        Mockito.anyString(),
                        Mockito.anyString(),
                    ),
                ).thenAnswer { inv ->
                    portfolios += inv.getArgument<String>(5)
                    "ord"
                }
            stubMarkSentRecording(mutableListOf())

            service.processPending()

            Mockito.verify(tradingAccountService, Mockito.timeout(3000)).portfolioOf(9L)
            Mockito
                .verify(alorClient, Mockito.timeout(3000))
                .placeLimitOrder(
                    Mockito.anyString(),
                    Mockito.anyString(),
                    Mockito.anyInt(),
                    anyBigDecimal(),
                    Mockito.anyString(),
                    Mockito.anyString(),
                )
        }
        assertEquals(listOf("PORTFOLIO-9"), portfolios)
    }

    @Test
    fun `worker catches exception from claimRetryable`() {
        runBlocking {
            Mockito
                .`when`(
                    outboxRepo.claimRetryable(
                        Mockito.anyInt(),
                        Mockito.anyInt(),
                        Mockito.anyInt(),
                        Mockito.anyInt(),
                        Mockito.anyInt(),
                    ),
                ).thenThrow(RuntimeException("db unavailable"))

            service.processPending()

            Mockito
                .verify(outboxRepo, Mockito.timeout(3000))
                .claimRetryable(Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt())
        }
    }
}
