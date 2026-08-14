package com.trading.bot.application

import com.trading.bot.client.AlorClient
import com.trading.bot.model.InstrumentType
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.entity.Position
import com.trading.bot.repository.OrderOutboxRepository
import com.trading.bot.repository.PositionRepository
import com.trading.bot.service.OrderOutboxService
import com.trading.bot.service.TradeEventService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigDecimal
import java.util.UUID

/**
 * Unit-тест идемпотентной отмены при перевыставлении защитных заявок SL/TP (P1).
 *
 * При UNKNOWN/UNCERTAIN отмене engine ретраит на следующем цикле С ТЕМ ЖЕ
 * idempotency-ключом (`prot-cancel-<orderId>`) — биржа дедуплицирует повторную
 * отмену, двойного снятия/зависания нет. UNCERTAIN не снимает флаг
 * slPendingReplace/tpPendingReplace.
 */
class OrderExecutionEngineProtectionReplaceTest {
    private val alorClient = Mockito.mock(AlorClient::class.java)
    private val orderOutboxService = Mockito.mock(OrderOutboxService::class.java)
    private val orderOutboxRepo = Mockito.mock(OrderOutboxRepository::class.java)
    private val positionRepo = Mockito.mock(PositionRepository::class.java)
    private val objectMapper = jacksonObjectMapper()
    private val tradeEventService = Mockito.mock(TradeEventService::class.java)
    private val meterRegistry = SimpleMeterRegistry()

    private val engine =
        OrderExecutionEngine(
            alorClient = alorClient,
            orderOutboxService = orderOutboxService,
            orderOutboxRepo = orderOutboxRepo,
            positionRepo = positionRepo,
            alorConfig = Mockito.mock(com.trading.bot.config.AlorConfig::class.java),
            objectMapper = objectMapper,
            tradeEventService = tradeEventService,
            meterRegistry = meterRegistry,
            pnlCalculator = PnlCalculator.plain(),
            instrumentFilter = { true },
            metricPrefix = "test",
            onEntryOpened = {},
            onPositionClosed = {},
            protectionOrdersEnabled = true,
            portfolioResolver = { "D12345" },
        )

    private fun anyString(): String {
        Mockito.any(String::class.java)
        return "x"
    }

    private fun anyPosition(): Position {
        Mockito.any(Position::class.java)
        return pendingReplacePos()
    }

    private fun pendingReplacePos(): Position =
        Position(
            id = 1L,
            ticker = "Si",
            direction = PositionDirection.LONG,
            quantity = 1,
            entryPrice = BigDecimal("150000"),
            instrumentType = InstrumentType.FUTURES,
            status = PositionStatus.OPEN,
            slOrderId = "old-sl",
            slOrderPrice = BigDecimal("149000"),
            slPendingReplace = true,
            tpOrderId = "old-tp",
            tpOrderPrice = BigDecimal("151000"),
        )

    private fun stubVerificationInconclusive() {
        runBlocking {
            Mockito
                .`when`(alorClient.verifyOrder(Mockito.anyString(), Mockito.isNull(), anyString()))
                .thenReturn(null)
        }
    }

    private fun stubNewProtectionPlacementSafe() {
        runBlocking {
            Mockito
                .`when`(
                    orderOutboxService.placeOrder(
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.anyInt(),
                        Mockito.any(),
                        Mockito.anyString(),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.anyString(),
                        Mockito.any(),
                    ),
                ).thenReturn(OrderOutboxService.PlaceOrderResult(UUID.randomUUID(), null, success = false))
        }
    }

    @Test
    fun `uncertain cancel keeps replace pending and retry reuses same idempotency key`() {
        stubVerificationInconclusive()
        stubNewProtectionPlacementSafe()
        runBlocking {
            Mockito
                .`when`(alorClient.cancelOrder(anyString(), anyString(), anyString()))
                .thenReturn(AlorClient.CancelResult.UNCERTAIN)
                .thenReturn(AlorClient.CancelResult.CONFIRMED)
            Mockito.`when`(positionRepo.save(anyPosition())).thenAnswer { inv -> inv.getArgument<Position>(0) }
        }

        val pos = pendingReplacePos()
        runBlocking {
            engine.reconcilePosition(pos)
        }
        assertTrue(pos.slPendingReplace)
        assertEquals("old-sl", pos.slOrderId)

        runBlocking {
            engine.reconcilePosition(pos)
        }
        assertFalse(pos.slPendingReplace)

        val cancelKeys =
            Mockito
                .mockingDetails(alorClient)
                .invocations
                .filter { it.method.name == "cancelOrder" }
                .map { it.arguments[1] as String }
        assertEquals(listOf("prot-cancel-old-sl", "prot-cancel-old-sl"), cancelKeys)
    }
}
