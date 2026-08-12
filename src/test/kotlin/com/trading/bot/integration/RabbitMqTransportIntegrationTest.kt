package com.trading.bot.integration

import com.trading.bot.client.AlorClient
import com.trading.bot.config.OutboxRabbitProperties
import com.trading.bot.model.entity.OrderOutbox
import com.trading.bot.model.entity.OutboxStatus
import com.trading.bot.repository.OrderOutboxRepository
import com.trading.bot.service.OrderOutboxPublisher
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.testcontainers.containers.RabbitMQContainer
import org.testcontainers.junit.jupiter.Container
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigDecimal
import java.util.UUID

/**
 * Интеграционный тест RabbitMQ-транспорта outbox (roadmap v2.3, раздел 13.8).
 *
 * Полная цепочка против реальных Postgres + RabbitMQ:
 *   - строка сохраняется в outbox (PENDING) напрямую в БД (inline-dispatch НЕ вызывается,
 *     чтобы единственным путём доставки был именно консьюмер);
 *   - publisher публикует её id в очередь;
 *   - консьюмер получает сообщение, диспетчирует через OrderOutboxService.redispatchById
 *     (мокнутый AlorClient → sim orderId), строка становится SENT;
 *   - невалидное сообщение после bounded retry паркуется в DLQ.
 *
 * RabbitMQ-транспорт включается вручную через @DynamicPropertySource
 * (в src/test/resources/application.yml он по умолчанию выключен).
 */
class RabbitMqTransportIntegrationTest : AbstractTestContainerTest() {
    companion object {
        private val logger = KotlinLogging.logger {}

        @Container
        val rabbitmq = RabbitMQContainer("rabbitmq:3-management-alpine")

        @DynamicPropertySource
        @JvmStatic
        fun registerRabbitProperties(registry: DynamicPropertyRegistry) {
            // Windows/Docker: явный 127.0.0.1 (IPv4) — детерминированные соединения.
            registry.add("spring.rabbitmq.host") { "127.0.0.1" }
            registry.add("spring.rabbitmq.port") { rabbitmq.amqpPort.toString() }
            registry.add("spring.rabbitmq.username", rabbitmq::getAdminUsername)
            registry.add("spring.rabbitmq.password", rabbitmq::getAdminPassword)
            registry.add("app.outbox.rabbitmq.enabled") { "true" }
        }
    }

    @Autowired
    lateinit var outboxRepo: OrderOutboxRepository

    @Autowired
    lateinit var rabbitTemplate: RabbitTemplate

    @Autowired
    lateinit var outboxPublisher: OrderOutboxPublisher

    @Autowired
    lateinit var properties: OutboxRabbitProperties

    @MockitoBean
    lateinit var alorClient: AlorClient

    @BeforeEach
    fun setup() {
        runBlocking { outboxRepo.deleteAll() }
        drain(properties.queue)
        drain(properties.dlq)
        runBlocking {
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
                ).thenReturn("ord-rabbit-int")
        }
    }

    @Test
    fun `published pending outbox row is consumed and dispatched to SENT`() {
        val outbox =
            runBlocking {
                outboxRepo.save(
                    OrderOutbox(
                        payloadJson = payload("Si", "buy", 1, "92000", "limit", "idem-int-1"),
                        idempotencyKey = "idem-int-1",
                    ),
                )
            }
        val id = outbox.id ?: error("saved outbox has no id")

        outboxPublisher.publish(id)

        val sent = awaitSent(id)
        assertEquals(OutboxStatus.SENT, sent.status)
        assertEquals("ord-rabbit-int", sent.alorOrderId)
        runBlocking {
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
    }

    @Test
    fun `already sent row is acked and not re-dispatched`() {
        val outbox =
            runBlocking {
                outboxRepo.save(
                    OrderOutbox(
                        payloadJson = payload("Si", "buy", 1, "92000", "limit", "idem-int-2"),
                        idempotencyKey = "idem-int-2",
                    ),
                )
            }
        val id = outbox.id ?: error("saved outbox has no id")
        runBlocking { outboxRepo.markSent(id, "ord-already") }

        outboxPublisher.publish(id)

        // Row остаётся SENT с прежним orderId — консьюмер не переотправлял.
        Thread.sleep(2_000)
        val row = runBlocking { outboxRepo.findById(id) }
        assertEquals(OutboxStatus.SENT, row?.status)
        assertEquals("ord-already", row?.alorOrderId)
    }

    @Test
    fun `invalid message is dead-lettered after bounded retries`() {
        rabbitTemplate.convertAndSend(properties.exchange, properties.routingKey, "not-a-uuid")

        val dead = rabbitTemplate.receive(properties.dlq, 20_000)

        assertNotNull(dead, "Invalid message must land in DLQ")
        assertEquals("not-a-uuid", String(dead!!.body))
    }

    private fun anyBigDecimal(): BigDecimal {
        Mockito.any(BigDecimal::class.java)
        return BigDecimal.ZERO
    }

    private fun payload(
        ticker: String,
        side: String,
        qty: Int,
        price: String,
        type: String,
        idempotencyKey: String,
    ): String =
        jacksonObjectMapper().writeValueAsString(
            mapOf(
                "ticker" to ticker,
                "side" to side,
                "qty" to qty,
                "price" to price,
                "type" to type,
                "idempotencyKey" to idempotencyKey,
                "positionId" to null,
                "purpose" to "entry",
                "accountId" to null,
            ),
        )

    private fun awaitSent(id: UUID): OrderOutbox {
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            val row = runBlocking { outboxRepo.findById(id) }
            if (row != null && row.status == OutboxStatus.SENT) return row
            Thread.sleep(250)
        }
        val actual = runBlocking { outboxRepo.findById(id) }
        error("Outbox $id not SENT within timeout (status=${actual?.status})")
    }

    private fun drain(queue: String) {
        var message = rabbitTemplate.receive(queue, 200)
        var drained = 0
        while (message != null && drained < 100) {
            drained++
            val body = String(message.body)
            logger.info { "Drained stale message from $queue: $body" }
            message = rabbitTemplate.receive(queue, 200)
        }
    }
}
