package com.trading.bot.service

import com.trading.bot.config.OutboxRabbitProperties
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.amqp.core.Message
import org.springframework.amqp.core.MessageBuilder
import org.springframework.amqp.core.MessageProperties
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Unit-тесты потребителя outbox-очереди (acknowledge mode AUTO):
 *
 * - валидный id строки → диспетчится через [OrderOutboxService.redispatchById],
 *   исключение не бросается (контейнер подтвердит сообщение);
 * - невалидный body → исключение → bounded retry контейнера → DLQ;
 * - сбой диспетчеризации → исключение пробрасывается (retry/DLQ).
 */
class OutboxOrderConsumerTest {
    private val orderOutboxService = Mockito.mock(OrderOutboxService::class.java)
    private val properties = OutboxRabbitProperties()
    private val meterRegistry = SimpleMeterRegistry()
    private val consumer = OutboxOrderConsumer(orderOutboxService, properties, meterRegistry)

    private fun message(body: String): Message {
        val messageProperties = MessageProperties()
        messageProperties.deliveryTag = 1L
        return MessageBuilder
            .withBody(body.toByteArray(StandardCharsets.UTF_8))
            .andProperties(messageProperties)
            .build()
    }

    private suspend fun stubRedispatch(
        outcome: Boolean,
        orderId: String? = null,
    ) {
        // Mockito.any(...) возвращает null для non-primitive → регистрируем matcher
        // отдельно и передаём в вызов реальный UUID (как anyUuid() в OrderOutboxServiceTest).
        Mockito.any(UUID::class.java)
        Mockito
            .`when`(orderOutboxService.redispatchById(UUID.randomUUID()))
            .thenReturn(OrderOutboxService.PlaceOrderResult(UUID.randomUUID(), orderId, success = outcome))
    }

    @Test
    fun `valid id is redispatched without exception`() {
        val outboxId = UUID.randomUUID()
        kotlinx.coroutines.runBlocking { stubRedispatch(outcome = true, orderId = "ord-1") }

        consumer.onOrderMessage(message(outboxId.toString()))

        kotlinx.coroutines.runBlocking {
            Mockito.verify(orderOutboxService).redispatchById(outboxId)
        }
        assertFalse(meterRegistry.counter("outbox.consumed_failed").count() > 0)
    }

    @Test
    fun `invalid body throws to reject into DLQ`() {
        assertThrows(IllegalArgumentException::class.java) {
            consumer.onOrderMessage(message("not-a-uuid"))
        }
        // Диспетчер не вызывался — иначе сообщение диспетчировалось бы, а не парковалось.
        Mockito.verifyNoInteractions(orderOutboxService)
    }

    @Test
    fun `dispatch failure is rethrown for retry and DLQ`() {
        kotlinx.coroutines.runBlocking {
            Mockito.any(UUID::class.java)
            Mockito
                .`when`(orderOutboxService.redispatchById(UUID.randomUUID()))
                .thenThrow(RuntimeException("db down"))
        }

        assertThrows(RuntimeException::class.java) {
            consumer.onOrderMessage(message(UUID.randomUUID().toString()))
        }
        assertFalse(meterRegistry.counter("outbox.consumed", "outcome", "ok").count() > 0)
    }
}
