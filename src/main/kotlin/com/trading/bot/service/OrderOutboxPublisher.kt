package com.trading.bot.service

import com.trading.bot.config.OutboxRabbitProperties
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Best-effort публикация outbox-строк в RabbitMQ (roadmap v2.3, раздел 13.8).
 *
 * Вызывается сразу после сохранения строки (status = PENDING). Если RabbitMQ
 * выключен/недоступен — строка не теряется: её подхватит DB-worker
 * ([OrderOutboxService.processPending]) как фолбэк.
 *
 * Публикуется только [UUID] строки: полный payload живёт в БД (source of truth),
 * потребитель [OutboxOrderConsumer] загружает строку по id и диспетчирует её.
 */
@Service
class OrderOutboxPublisher(
    private val rabbitTemplate: RabbitTemplate,
    private val properties: OutboxRabbitProperties,
    private val meterRegistry: MeterRegistry,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Публикует id строки в очередь ордеров. Сбой публикации НЕ роняет вызывающего:
     * DB-worker остаётся фолбэком (best-effort transport, гарантии доставки —
     * в БД-строке, а не в очереди).
     */
    fun publish(outboxId: UUID) {
        if (!properties.enabled) return
        try {
            rabbitTemplate.convertAndSend(properties.exchange, properties.routingKey, outboxId.toString())
            meterRegistry.counter("outbox.published").increment()
            logger.info { "Outbox published to queue: $outboxId" }
        } catch (e: Exception) {
            meterRegistry.counter("outbox.publish_failed").increment()
            logger.warn(e) { "Outbox publish failed for $outboxId — DB worker is the fallback" }
        }
    }
}
