package com.trading.bot.service

import com.trading.bot.client.AlorClient
import com.trading.bot.config.AlorConfig
import com.trading.bot.model.OrderOutbox
import com.trading.bot.model.OutboxStatus
import com.trading.bot.repository.OrderOutboxRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.eq
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
    private val alorClient = Mockito.mock(AlorClient::class.java)
    private val alorConfig = AlorConfig().apply { maxOrderRetries = 3 }
    private val objectMapper = jacksonObjectMapper()
    private val meterRegistry = SimpleMeterRegistry()
    private val service = OrderOutboxService(outboxRepo, alorClient, alorConfig, objectMapper, meterRegistry)

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
    ): OrderOutbox {
        val payload =
            objectMapper.writeValueAsString(
                mapOf(
                    "ticker" to "Si",
                    "side" to "sell",
                    "qty" to 1,
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
        )
    }

    private suspend fun stubReconcile(result: AlorClient.OrderReconciliation) {
        Mockito
            .`when`(
                alorClient.reconcileOrderByIdempotencyKey(
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
                    ),
                ).thenReturn("ord-1")
            stubMarkSentRecording(sentOrders)

            val result = service.placeOrder("Si", "buy", 1, BigDecimal("92000"), "limit")

            assertTrue(result.success)
            assertEquals("ord-1", result.alorOrderId)
            Mockito
                .verify(alorClient, Mockito.never())
                .reconcileOrderByIdempotencyKey(Mockito.anyString(), Mockito.anyString(), Mockito.anyString())
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
                )
        }
        assertEquals(listOf("ord-m"), sentOrders)
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
            Mockito
                .`when`(outboxRepo.findRetryable(Mockito.anyInt(), Mockito.anyInt()))
                .thenReturn(listOf(outbox))
            stubReconcile(AlorClient.OrderReconciliation.Found("ord-9", 1, BigDecimal("92000")))
            stubMarkSentRecording(sentOrders)

            service.processPending()

            Mockito
                .verify(alorClient, Mockito.timeout(3000))
                .reconcileOrderByIdempotencyKey(Mockito.anyString(), Mockito.anyString(), Mockito.anyString())
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
                )
            Mockito
                .verify(alorClient, Mockito.never())
                .placeMarketOrder(Mockito.anyString(), Mockito.anyString(), Mockito.anyInt(), Mockito.anyString())
        }
        assertEquals(listOf("ord-9"), sentOrders)
    }

    @Test
    fun `reconcile UNKNOWN skips re-send and is uncertain`() {
        val outbox = outboxRow(retryCount = 1, key = "idem-1", type = "limit", price = "92000")
        runBlocking {
            Mockito
                .`when`(outboxRepo.findRetryable(Mockito.anyInt(), Mockito.anyInt()))
                .thenReturn(listOf(outbox))
            stubReconcile(AlorClient.OrderReconciliation.Unknown)

            service.processPending()

            Mockito
                .verify(alorClient, Mockito.timeout(3000))
                .reconcileOrderByIdempotencyKey(Mockito.anyString(), Mockito.anyString(), Mockito.anyString())
            Mockito
                .verify(alorClient, Mockito.never())
                .placeLimitOrder(
                    Mockito.anyString(),
                    Mockito.anyString(),
                    Mockito.anyInt(),
                    anyBigDecimal(),
                    Mockito.anyString(),
                )
            Mockito
                .verify(alorClient, Mockito.never())
                .placeMarketOrder(Mockito.anyString(), Mockito.anyString(), Mockito.anyInt(), Mockito.anyString())
            Mockito.verify(outboxRepo, Mockito.never()).markSent(anyUuid(), Mockito.anyString())
        }
    }

    @Test
    fun `reconcile NOT_FOUND re-sends with the same idempotency key`() {
        val outbox = outboxRow(retryCount = 1, key = "idem-1", type = "limit", price = "92000")
        val usedKeys = mutableListOf<String>()
        val sentOrders = mutableListOf<String>()
        runBlocking {
            Mockito
                .`when`(outboxRepo.findRetryable(Mockito.anyInt(), Mockito.anyInt()))
                .thenReturn(listOf(outbox))
            stubReconcile(AlorClient.OrderReconciliation.NotFound)
            Mockito
                .`when`(
                    alorClient.placeLimitOrder(
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.anyInt(),
                        anyBigDecimal(),
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
                )
            Mockito
                .verify(outboxRepo, Mockito.timeout(3000))
                .markSent(anyUuid(), Mockito.anyString())
            Mockito
                .verify(alorClient, Mockito.timeout(3000))
                .reconcileOrderByIdempotencyKey(Mockito.anyString(), Mockito.anyString(), Mockito.anyString())
        }
        assertEquals(listOf("idem-1"), usedKeys)
        assertEquals(listOf("ord-2"), sentOrders)
    }

    @Test
    fun `process pending respects maxOrderRetries bound`() {
        runBlocking {
            Mockito
                .`when`(outboxRepo.findRetryable(Mockito.anyInt(), Mockito.anyInt()))
                .thenReturn(emptyList())

            service.processPending()

            Mockito
                .verify(outboxRepo, Mockito.timeout(3000))
                .findRetryable(eq(3), Mockito.anyInt())
        }
    }
}
