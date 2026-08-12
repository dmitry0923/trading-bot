package com.trading.bot.client

import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal

/**
 * Чистые функции WS-протокола Alor для ордеров (roadmap 13.8.2).
 *
 * Собирают JSON-команды и сопоставляют входящие сообщения с ожидаемым
 * исходом. Никакой I/O — полностью покрывается юнит-тестами.
 *
 * Протокол (alor.dev/ws, при недоступности документации проверен против
 * известного контракта):
 * - подписка на поток заявок: opcode `OrdersGetAndSubscribeV2`;
 * - размещение: opcode `AlorOrders` (payload — та же модель NewOrder, что и у
 *   REST `actions/limit`: portfolio, ticker, exchange, side, type, quantity,
 *   price/stopPrice, id = idempotency key);
 * - отмена: opcode `AlorCancelOrder`;
 * - ответы приходят событиями на потоке заявок: у события ордера поле `id` —
 *   клиентский id (idempotency key), `orderNumber` — номер на бирже, `status` —
 *   статус. Прямые ошибки могут приходить сообщением с `requestId` = guid команды.
 *
 * ВАЖНО: если протокол отличается от ожидаемого — событие не совпадёт,
 * команда уйдёт в [OrderDeliveryUncertainException], а outbox через State
 * Reconciliation (по idempotency key) гарантирует отсутствие double execution.
 */
object WsOrderMessages {
    private val mapper = ObjectMapper()

    /** Подписка на поток заявок портфеля (обязательна для приёма событий заявок). */
    fun subscribe(
        token: String,
        portfolio: String,
        exchange: String,
        guid: String,
    ): String =
        mapper.writeValueAsString(
            mapOf(
                "opcode" to "OrdersGetAndSubscribeV2",
                "guid" to guid,
                "token" to token,
                "portfolio" to portfolio,
                "exchange" to exchange,
                "format" to "Simple",
            ),
        )

    /** Команда размещения лимитной заявки. */
    fun placeLimit(
        token: String,
        portfolio: String,
        ticker: String,
        exchange: String,
        side: String,
        qty: Int,
        price: BigDecimal,
        idempotencyKey: String,
        guid: String,
    ): String =
        mapper.writeValueAsString(
            mapOf(
                "opcode" to "AlorOrders",
                "guid" to guid,
                "token" to token,
                "portfolio" to portfolio,
                "ticker" to ticker,
                "exchange" to exchange,
                "side" to side,
                "type" to "limit",
                "quantity" to qty,
                "price" to price.toPlainString(),
                "id" to idempotencyKey,
            ),
        )

    /** Команда размещения условной заявки (stop / take-profit). */
    fun placeConditional(
        token: String,
        portfolio: String,
        ticker: String,
        exchange: String,
        side: String,
        type: String,
        qty: Int,
        stopPrice: BigDecimal,
        idempotencyKey: String,
        guid: String,
    ): String =
        mapper.writeValueAsString(
            mapOf(
                "opcode" to "AlorOrders",
                "guid" to guid,
                "token" to token,
                "portfolio" to portfolio,
                "ticker" to ticker,
                "exchange" to exchange,
                "side" to side,
                "type" to type,
                "quantity" to qty,
                "stopPrice" to stopPrice.toPlainString(),
                "stopEndUnixTime" to 0,
                "id" to idempotencyKey,
            ),
        )

    /** Команда отмены заявки. */
    fun cancel(
        token: String,
        portfolio: String,
        exchange: String,
        orderId: String,
        idempotencyKey: String,
        guid: String,
    ): String =
        mapper.writeValueAsString(
            mapOf(
                "opcode" to "AlorCancelOrder",
                "guid" to guid,
                "token" to token,
                "portfolio" to portfolio,
                "exchange" to exchange,
                "orderId" to orderId,
                "id" to idempotencyKey,
            ),
        )

    /** Итог сопоставления входящего сообщения с ожидаемой командой. */
    sealed interface MatchResult {
        /** Сообщение не относится к данной команде — ждём дальше. */
        data object NotMatch : MatchResult

        /** Биржа приняла заявку — [orderNumber] на бирже. */
        data class Confirmed(
            val orderNumber: String,
        ) : MatchResult

        /** Биржа отклонила — повторная отправка бессмысленна. */
        data class Rejected(
            val reason: String,
        ) : MatchResult
    }

    /**
     * Сопоставляет входящее сообщение с размещённой заявкой.
     * Совпадение — событие с `id` == [idempotencyKey] и присвоенным `orderNumber`,
     * либо прямой ответ/ошибка с `requestId` == [guid]. Пока `orderNumber` не
     * присвоен (промежуточное событие) — ждём дальше ([MatchResult.NotMatch]).
     */
    fun matchPlace(
        json: String,
        idempotencyKey: String,
        guid: String,
    ): MatchResult {
        val j = parseOrNull(json) ?: return MatchResult.NotMatch
        val clientId = j.path("id").asString("")
        val requestId = j.path("requestId").asString("")
        if (clientId != idempotencyKey && requestId != guid) return MatchResult.NotMatch

        val status = j.path("status").asString("").lowercase()
        val error = j.path("error").asString("").takeIf { it.isNotBlank() }
        val requestStatus = j.path("requestStatus").asString("").lowercase()
        if (status.contains("reject") || requestStatus == "error" || error != null || requestStatus == "rejected") {
            return MatchResult.Rejected(error ?: "order rejected by Alor (status=$status)")
        }

        val orderNumber =
            j
                .path("orderNumber")
                .asString("")
                .ifBlank { j.path("orderNo").asString("") }
        return if (orderNumber.isNotBlank()) {
            MatchResult.Confirmed(orderNumber)
        } else {
            // Событие по нашему id, но orderNumber ещё не присвоен (промежуточное) — ждём.
            MatchResult.NotMatch
        }
    }

    /**
     * Сопоставляет входящее сообщение с отменой заявки.
     * Совпадение — событие заявки с `orderNumber` == [orderId] и финальным
     * статусом (cancelled/rejected), либо прямой ответ/ошибка с `requestId` == [guid].
     */
    fun matchCancel(
        json: String,
        orderId: String,
        guid: String,
    ): MatchResult {
        val j = parseOrNull(json) ?: return MatchResult.NotMatch
        val eventOrderId =
            j
                .path("orderNumber")
                .asString("")
                .ifBlank { j.path("orderNo").asString("") }
        val requestId = j.path("requestId").asString("")
        val isRequestAck = requestId == guid

        if (eventOrderId != orderId && !isRequestAck) return MatchResult.NotMatch

        val status = j.path("status").asString("").lowercase()
        val requestStatus = j.path("requestStatus").asString("").lowercase()
        val error = j.path("error").asString("").takeIf { it.isNotBlank() }
        val cancelled = status.contains("cancel") || requestStatus.contains("cancel") || requestStatus == "success"
        val rejected = status.contains("reject") || requestStatus.contains("reject") || requestStatus == "error" || error != null
        return when {
            cancelled -> MatchResult.Confirmed(orderId)
            rejected -> MatchResult.Rejected(error ?: "cancel rejected by Alor (status=$status)")
            else -> MatchResult.NotMatch
        }
    }

    /**
     * Разбор JSON без исключений: пустые/битые сообщения не должны ронять
     * обработчик канала ордеров (иначе обрыв по вине парсера).
     */
    private fun parseOrNull(json: String): tools.jackson.databind.JsonNode? =
        try {
            mapper.readTree(json)
        } catch (e: Exception) {
            null
        }
}
