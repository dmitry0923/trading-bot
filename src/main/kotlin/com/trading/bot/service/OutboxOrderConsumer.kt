package com.trading.bot.service

import com.trading.bot.config.OutboxRabbitProperties
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import kotlinx.coroutines.runBlocking
import org.springframework.amqp.core.Message
import org.springframework.amqp.rabbit.annotation.RabbitListener
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Потребитель outbox-очереди (roadmap v2.3, раздел 13.8).
 *
 * Контракт:
 * - принимает только id строки (payload — полный, живёт в БД);
 * - НЕ исполняет ордер сам — вызывает существующий [OrderOutboxService.redispatchById]
 *   (тот же диспетчер, что и inline/DB-worker → единые reconciliation и идемпотентность);
 * - acknowledge mode AUTO (фабрика [com.trading.bot.config.OutboxRabbitConfig]): контейнер
 *   подтверждает сообщение после нормального возврата и отклоняет при исключении;
 * - PENDING → диспетчирует; SENT → ack (idempotent, уже доставлена);
 *   FAILED → ack без переотправки (повторные попытки — прерогатива DB-worker'а
 *   с backoff и State Reconciliation, чтобы не создавать конкурирующий цикл ретраев);
 * - исключение (строка не загрузилась/infra) → bounded retry контейнера
 *   (stateless, [com.trading.bot.config.OutboxRabbitConfig.outboxRabbitListenerContainerFactory]),
 *   после исчерпания [org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer]
 *   бросает `AmqpRejectAndDontRequeueException` → контейнер отклоняет сообщение без requeue
 *   (`defaultRequeueRejected=false`) → оно паркуется в dead-letter очередь,
 *   а строка в БД по-прежнему обрабатывается DB-worker'ом.
 *
 * Бин создаётся только при `app.outbox.rabbitmq.enabled=true` (одно условие с
 * [com.trading.bot.config.OutboxRabbitConfig], которое предоставляет фабрику контейнера).
 */
@Service
@ConditionalOnProperty(name = ["app.outbox.rabbitmq.enabled"], havingValue = "true")
class OutboxOrderConsumer(
    private val orderOutboxService: OrderOutboxService,
    private val properties: OutboxRabbitProperties,
    private val meterRegistry: MeterRegistry,
) {
    private val logger = KotlinLogging.logger {}

    @RabbitListener(
        queues = ["\${app.outbox.rabbitmq.queue}"],
        containerFactory = "outboxRabbitListenerContainerFactory",
    )
    fun onOrderMessage(message: Message) {
        val body = String(message.body)
        val outboxId =
            body.toUUIDOrNull() ?: run {
                meterRegistry.counter("outbox.consumed_invalid").increment()
                logger.warn { "Outbox consumer: invalid message body '$body' — rejecting to DLQ" }
                throw IllegalArgumentException("Invalid outbox id in message: $body")
            }
        try {
            runBlocking { orderOutboxService.redispatchById(outboxId) }
            meterRegistry.counter("outbox.consumed", Tags.of("outcome", "ok")).increment()
        } catch (e: Exception) {
            // Container retries bounded attempts, then rejects → DLQ.
            meterRegistry.counter("outbox.consumed_failed").increment()
            logger.error(e) { "Outbox consumer failed for $outboxId — will be retried/rejected" }
            throw e
        }
    }

    private fun String.toUUIDOrNull(): UUID? =
        try {
            UUID.fromString(this)
        } catch (_: IllegalArgumentException) {
            null
        }
}
