package com.trading.bot.config

import org.springframework.amqp.core.Binding
import org.springframework.amqp.core.BindingBuilder
import org.springframework.amqp.core.DirectExchange
import org.springframework.amqp.core.Exchange
import org.springframework.amqp.core.Queue
import org.springframework.amqp.core.QueueBuilder
import org.springframework.amqp.rabbit.annotation.EnableRabbit
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory
import org.springframework.amqp.rabbit.connection.ConnectionFactory
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer
import org.springframework.amqp.support.converter.SimpleMessageConverter
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Топология RabbitMQ для outbox (roadmap v2.3, раздел 13.8):
 *
 * - `exchange` (DirectExchange) + `queue` — основной канал: publisher кладёт
 *   сообщение с id outbox-строки, потребитель [com.trading.bot.service.OutboxOrderConsumer]
 *   забирает и доставляет ордер в Alor.
 * - `queue` настроена на dead-letter (`x-dead-letter-exchange = dlx`): сообщение,
 *   отвергнутое потребителем (nack без requeue или необработанное исключение),
 *   паркуется в `dlq` — это сторожевой «паркинг», строка в БД при этом остаётся
 *   FAILED и по-прежнему ретраится DB-worker'ом с backoff.
 *
 * Весь биндинг создаётся только при `app.outbox.rabbitmq.enabled=true`
 * (по умолчанию выключено — RabbitMQ не требуется, поведение прежнее).
 */
@Configuration
@EnableRabbit
@ConditionalOnProperty(name = ["app.outbox.rabbitmq.enabled"], havingValue = "true")
class OutboxRabbitConfig(
    private val properties: OutboxRabbitProperties,
) {
    @Bean
    fun outboxExchange(): Exchange = DirectExchange(properties.exchange, true, false)

    @Bean
    fun outboxDlExchange(): Exchange = DirectExchange(properties.dlx, true, false)

    @Bean
    fun outboxQueue(): Queue =
        QueueBuilder
            .durable(properties.queue)
            .withArgument("x-dead-letter-exchange", properties.dlx)
            .build()

    @Bean
    fun outboxDlq(): Queue = QueueBuilder.durable(properties.dlq).build()

    @Bean
    fun outboxBinding(): Binding =
        BindingBuilder
            .bind(outboxQueue())
            .to(outboxExchange())
            .with(properties.routingKey)
            .noargs()

    @Bean
    fun outboxDlBinding(): Binding =
        BindingBuilder
            .bind(outboxDlq())
            .to(outboxDlExchange())
            .with(properties.routingKey)
            .noargs()

    /**
     * Контейнер потребителя outbox-очереди: AUTO ack + bounded retry.
     * - AcknowledgeMode.AUTO — контейнер сам подтверждает сообщение после нормального
     *   возврата [com.trading.bot.service.OutboxOrderConsumer] и отклоняет при исключении;
     * - stateless retry (3 попытки, backoff 1s → 2s → 10s): после исчерпания
     *   [RejectAndDontRequeueRecoverer] бросает `AmqpRejectAndDontRequeueException`;
     *   `defaultRequeueRejected=false` → контейнер отклоняет без requeue → сообщение
     *   попадает в DLQ (`x-dead-letter-exchange` очереди [outboxQueue]).
     * - SimpleMessageConverter — body передаётся байтами (id строки), не JSON.
     *
     * MANUAL не используется: при ручном ack контейнер не отклоняет сообщение после
     * исчерпания ретраев (отклонение требует `AmqpRejectAndDontRequeueException`
     * c `rejectManual=true`), поэтому poison-сообщение застревало бы unacked в очереди.
     */
    @Bean
    fun outboxRabbitListenerContainerFactory(connectionFactory: ConnectionFactory): SimpleRabbitListenerContainerFactory =
        SimpleRabbitListenerContainerFactory().apply {
            setConnectionFactory(connectionFactory)
            setAcknowledgeMode(org.springframework.amqp.core.AcknowledgeMode.AUTO)
            setDefaultRequeueRejected(false)
            setMessageConverter(SimpleMessageConverter())
            setAdviceChain(
                RetryInterceptorBuilder
                    .stateless()
                    .maxRetries(2)
                    .backOffOptions(1_000, 2.0, 10_000)
                    .recoverer(RejectAndDontRequeueRecoverer())
                    .build(),
            )
        }
}
