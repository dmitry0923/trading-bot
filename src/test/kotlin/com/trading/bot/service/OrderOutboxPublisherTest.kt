package com.trading.bot.service

import com.trading.bot.config.OutboxRabbitProperties
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.eq
import org.springframework.amqp.rabbit.core.RabbitTemplate
import java.util.UUID

/**
 * Unit-тесты best-effort publisher'а outbox-очереди:
 *
 * - при выключенном RabbitMQ-транспорте публикация не выполняется (no-op);
 * - при включённом — id строки публикуется в exchange по routing key;
 * - сбой RabbitTemplate (брокер недоступен) не роняет вызывающего: инкрементит
 *   `outbox.publish_failed`, строка остаётся в БД и доставится DB-worker'ом (фолбэк).
 */
class OrderOutboxPublisherTest {
    private val rabbitTemplate = Mockito.mock(RabbitTemplate::class.java)
    private val properties = OutboxRabbitProperties()
    private val meterRegistry = SimpleMeterRegistry()

    @Test
    fun `disabled transport does not publish`() {
        properties.enabled = false
        val publisher = OrderOutboxPublisher(rabbitTemplate, properties, meterRegistry)

        publisher.publish(UUID.randomUUID())

        Mockito
            .verify(rabbitTemplate, Mockito.never())
            .convertAndSend(Mockito.anyString(), Mockito.anyString(), Mockito.anyString())
    }

    @Test
    fun `enabled transport publishes outbox id`() {
        properties.enabled = true
        val publisher = OrderOutboxPublisher(rabbitTemplate, properties, meterRegistry)
        val outboxId = UUID.randomUUID()

        publisher.publish(outboxId)

        Mockito
            .verify(rabbitTemplate)
            .convertAndSend(eq(properties.exchange), eq(properties.routingKey), eq(outboxId.toString()))
        assertEquals(1.0, meterRegistry.counter("outbox.published").count())
    }

    @Test
    fun `broker failure is swallowed and counted`() {
        properties.enabled = true
        Mockito
            .`when`(rabbitTemplate.convertAndSend(Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
            .thenThrow(RuntimeException("broker unreachable"))
        val publisher = OrderOutboxPublisher(rabbitTemplate, properties, meterRegistry)

        publisher.publish(UUID.randomUUID())

        assertEquals(1.0, meterRegistry.counter("outbox.publish_failed").count())
        assertEquals(0.0, meterRegistry.counter("outbox.published").count())
    }

    @Test
    fun `publish failure does not throw`() {
        properties.enabled = true
        Mockito
            .`when`(rabbitTemplate.convertAndSend(Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
            .thenThrow(RuntimeException("broker unreachable"))
        val publisher = OrderOutboxPublisher(rabbitTemplate, properties, meterRegistry)

        // Не должно бросать: сбой публикации — best-effort, доставку берёт DB-worker.
        publisher.publish(UUID.randomUUID())
    }
}
