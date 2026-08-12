package com.trading.bot.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * Конфигурация RabbitMQ-транспорта outbox (prefix = "app.outbox.rabbitmq").
 *
 * Функция (roadmap v2.3, раздел 13.8): RabbitMQ — дополнительный канал доставки
 * outbox-строк: каждый сохранённый ордер публикуется в очередь, потребитель
 * доставляет его в Alor (идемпотентно по idempotencyKey). DB-worker остаётся
 * фолбэком, гарантии «никакого double execution» не меняются.
 *
 * @property enabled включать RabbitMQ-транспорт (выкл = прежний behaviour, Rabbit не нужен)
 * @property exchange обмен для публикации outbox-строк
 * @property queue очередь ордеров (привязка к [exchange] по [routingKey])
 * @property routingKey routing key публикации
 * @property dlx dead-letter exchange (сообщения, исчерпавшие обработку)
 * @property dlq очередь парковки неудачных доставок
 */
@Component
@ConfigurationProperties(prefix = "app.outbox.rabbitmq")
class OutboxRabbitProperties {
    var enabled: Boolean = false
    var exchange: String = "trading.outbox"
    var queue: String = "trading.outbox.orders"
    var routingKey: String = "order"
    var dlx: String = "trading.outbox.dlx"
    var dlq: String = "trading.outbox.orders.dlq"
}
