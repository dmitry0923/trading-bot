package com.trading.bot.service

import com.trading.bot.model.InstrumentType
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.entity.Position
import com.trading.bot.model.entity.TradeEvent
import com.trading.bot.repository.TradeEventRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.stubbing.Answer
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

/**
 * TradeEventService: JSON-снимки позиций в append-only журнале должны нести
 * cycleId (lineage) — по нему реконструкция решения связывает сделку с
 * полной цепочкой LLM-агентов.
 *
 * Suspend-методы моков (append) не поддерживают ArgumentCaptor/Mockito-verify
 * на non-null возвратах Unit — события записываются через doAnswer-стаб.
 */
class TradeEventServiceTest {
    private val tradeEventRepo = Mockito.mock(TradeEventRepository::class.java)
    private val objectMapper = ObjectMapper()
    private val recordedEvents = mutableListOf<TradeEvent>()

    private fun anyTradeEvent(): TradeEvent {
        Mockito.any(TradeEvent::class.java)
        return TradeEvent(
            aggregateId = UUID.nameUUIDFromBytes("dummy".toByteArray()),
            eventType = "dummy",
            payload = "{}",
            occurredAt = LocalDateTime.MIN,
            sequenceNumber = 0,
        )
    }

    private fun shouldRecordAppends() {
        val answer: Answer<Any?> =
            Answer { invocation ->
                recordedEvents.add(invocation.getArgument(0) as TradeEvent)
                Unit
            }
        runBlocking {
            Mockito
                .doAnswer(answer)
                .`when`(tradeEventRepo)
                .append(anyTradeEvent())
        }
    }

    private fun service() = TradeEventService(tradeEventRepo, objectMapper)

    private fun position(cycleId: String?) =
        Position(
            id = 42,
            ticker = "CNYRUBF",
            direction = PositionDirection.LONG,
            quantity = 1,
            entryPrice = BigDecimal("110.00"),
            cycleId = cycleId,
            instrumentType = InstrumentType.FUTURES,
            openedAt = LocalDateTime.now(),
        )

    @Test
    fun `recordPositionOpened writes cycleId into payload snapshot`() {
        shouldRecordAppends()
        runBlocking { service().recordPositionOpened(position("cycle-7")) }

        val payload = recordedEvents.single().payload
        assertEquals("cycle-7", objectMapper.readTree(payload).path("cycleId").asString())
        assertEquals("42", objectMapper.readTree(payload).path("positionId").asString())
    }

    @Test
    fun `recordPositionOpen handles null cycleId`() {
        shouldRecordAppends()
        runBlocking { service().recordPositionOpened(position(null)) }

        val payload = recordedEvents.single().payload
        assertEquals(true, objectMapper.readTree(payload).path("cycleId").isNull)
    }

    @Test
    fun `recordPositionClosed keeps cycleId and adds close reason`() {
        shouldRecordAppends()
        runBlocking { service().recordPositionClosed(position("cycle-7"), "SL") }

        val event = objectMapper.readTree(recordedEvents.single().payload)
        assertEquals("cycle-7", event.path("cycleId").asString())
        assertEquals("SL", event.path("closeReason").asString())
    }
}
