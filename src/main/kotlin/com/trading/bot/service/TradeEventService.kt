package com.trading.bot.service

import com.trading.bot.model.Position
import com.trading.bot.model.TradeEvent
import com.trading.bot.repository.TradeEventRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

/**
 * Сервис append-only лога торговых решений (audit trail).
 *
 * Каждое изменение позиции записывается в trade_events событием
 * (POSITION_OPENED / POSITION_UPDATED / POSITION_CLOSED) с JSON-снимком.
 * Позиции при этом по-прежнему хранятся в read-model positions —
 * события служат неизменяемым журналом для compliance и воспроизводимости.
 */
@Service
class TradeEventService(
    private val tradeEventRepo: TradeEventRepository,
    private val objectMapper: ObjectMapper,
) {
    private val logger = KotlinLogging.logger {}

    suspend fun recordPositionOpened(pos: Position) {
        append(pos, "POSITION_OPENED")
    }

    suspend fun recordPositionUpdated(pos: Position) {
        append(pos, "POSITION_UPDATED")
    }

    suspend fun recordPositionClosed(
        pos: Position,
        reason: String,
    ) {
        val snapshot = snapshot(pos) + ("closeReason" to reason)
        append(pos, "POSITION_CLOSED", snapshot)
    }

    private suspend fun append(
        pos: Position,
        type: String,
        payload: Map<String, Any?> = snapshot(pos),
    ) {
        try {
            val aggregateId = aggregateIdOf(pos.id)
            val event =
                TradeEvent(
                    aggregateId = aggregateId,
                    eventType = type,
                    payload = objectMapper.writeValueAsString(payload),
                    occurredAt = LocalDateTime.now(),
                    sequenceNumber = 0,
                )
            tradeEventRepo.append(event)
        } catch (e: Exception) {
            logger.warn(e) { "Trade event append failed: $type (ticker=${pos.ticker})" }
        }
    }

    private fun snapshot(pos: Position): Map<String, Any?> =
        mapOf(
            "positionId" to pos.id,
            "ticker" to pos.ticker,
            "direction" to pos.direction.name,
            "quantity" to pos.quantity,
            "entryPrice" to num(pos.entryPrice),
            "currentPrice" to num(pos.currentPrice),
            "stopLoss" to num(pos.stopLoss),
            "takeProfit" to num(pos.takeProfit),
            "pnl" to num(pos.pnl),
            "status" to pos.status.name,
            "instrumentType" to pos.instrumentType.name,
        )

    private fun num(v: BigDecimal?): Any? = v?.toPlainString()

    /**
     * Стабильный UUID агрегата из числового id позиции.
     */
    private fun aggregateIdOf(positionId: Long?): UUID = UUID.nameUUIDFromBytes("position:${positionId ?: -1L}".toByteArray())
}
