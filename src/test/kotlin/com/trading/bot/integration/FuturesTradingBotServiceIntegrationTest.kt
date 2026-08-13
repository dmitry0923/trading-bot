package com.trading.bot.integration

import com.trading.bot.application.MarketDataGate
import com.trading.bot.application.TradingBlockReason
import com.trading.bot.application.TradingGate
import com.trading.bot.application.TradingHoursGuard
import com.trading.bot.application.risk.FuturesRiskEngine
import com.trading.bot.client.AlorClient
import com.trading.bot.domain.signal.Signal
import com.trading.bot.event.TradingEventPublisher
import com.trading.bot.model.InstrumentType
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.StrategyAction
import com.trading.bot.model.entity.Position
import com.trading.bot.repository.DailyRiskSnapshotRepository
import com.trading.bot.repository.PositionRepository
import com.trading.bot.service.DrawdownProtectionService
import com.trading.bot.service.TradingHaltService
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.math.BigDecimal

/**
 * Интеграционный тест FuturesTradingBotService против реальной Postgres.
 *
 * Проверяет полный цикл фьючерсной торговли на Si:
 *   - вход через StrategyGeneratedEvent (risk-first, все futures-поля позиции);
 *   - мониторинг через PriceChangedEvent: LIQUIDATION_CRITICAL → немедленный market close;
 *   - P&L фьючерса = (close - entry) * qty * pointValue;
 *   - дневной лимит убытка блокирует новые входы (через DailyLossCircuitBreaker);
 *   - запрет входа при открытой позиции и вне торговых часов.
 *
 * AlorClient и TradingHoursGuard замоканы (детерминированные цены/часы),
 * всё остальное — реальные бины (Postgres, outbox, риск-движок).
 */
class FuturesTradingBotServiceIntegrationTest : AbstractTestContainerTest() {
    @Autowired
    lateinit var eventPublisher: TradingEventPublisher

    @Autowired
    lateinit var positionRepo: PositionRepository

    @Autowired
    lateinit var snapshotRepo: DailyRiskSnapshotRepository

    @Autowired
    lateinit var futuresRiskEngine: FuturesRiskEngine

    @Autowired
    lateinit var drawdownProtection: DrawdownProtectionService

    @Autowired
    lateinit var tradingGate: TradingGate

    @Autowired
    lateinit var tradingHaltService: TradingHaltService

    @Autowired
    lateinit var meterRegistry: MeterRegistry

    @MockitoBean
    lateinit var alorClient: AlorClient

    @MockitoBean
    lateinit var tradingHoursGuard: TradingHoursGuard

    @MockitoBean
    lateinit var marketDataGate: MarketDataGate

    @BeforeEach
    fun setup() {
        runBlocking { positionRepo.deleteAll() }
        snapshotRepo.deleteAll()
        // Сбрасываем персистентную остановку (critical liquidation выше может оставить halt).
        runBlocking { tradingHaltService.clear() }
        // пересчёт кэша drawdown от пустой БД (сбрасывает stale статус из предыдущего теста)
        runBlocking { drawdownProtection.computeStatus() }
        Mockito.`when`(tradingHoursGuard.isTradingAllowed()).thenReturn(true)
        // Данные всегда «свежие»: эти тесты проверяют риск-движок, а не MarketDataGate.
        Mockito.`when`(marketDataGate.isPriceDataFresh(Mockito.anyString())).thenReturn(true)
        runBlocking {
            Mockito.`when`(alorClient.getLastPrice("Si")).thenReturn(BigDecimal("92000"))
            Mockito
                .`when`(
                    alorClient.placeLimitOrder(
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.anyInt(),
                        anyBigDecimal(),
                        Mockito.anyString(),
                        Mockito.anyString(),
                    ),
                ).thenReturn("ord-limit-1")
            Mockito
                .`when`(
                    alorClient.placeMarketOrder(
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.anyInt(),
                        Mockito.anyString(),
                        Mockito.anyString(),
                    ),
                ).thenReturn("ord-market-1")
        }
    }

    @Test
    fun `futures entry creates position with full risk fields`() {
        eventPublisher.publishStrategyGenerated(signal(BigDecimal("92000")))

        awaitUntil { runBlocking { positionRepo.findByStatus(PositionStatus.OPEN).isNotEmpty() } }
        val pos = runBlocking { positionRepo.findByStatus(PositionStatus.OPEN).first() }

        assertEquals(InstrumentType.FUTURES, pos.instrumentType)
        assertEquals(1, pos.quantity)
        assertEquals(0, BigDecimal("92000").compareTo(pos.entryPrice))
        assertEquals(0, BigDecimal("2.0").compareTo(pos.leverage!!))
        assertEquals(0, BigDecimal("15000").compareTo(pos.goPerContract!!))
        assertEquals(0, BigDecimal("15000").compareTo(pos.marginUsed!!))
        assertEquals(0, BigDecimal("91999.50").compareTo(pos.stopLoss!!))
        assertEquals(0, BigDecimal("92001.00").compareTo(pos.takeProfit!!))
        assertEquals(0, BigDecimal("91985").compareTo(pos.liquidationPrice!!))
        assertEquals(50, pos.stopLossPoints)
        assertEquals(0, BigDecimal.ZERO.compareTo(pos.variationMargin))
        assertEquals("ord-limit-1", pos.alorOrderId)
        assertEquals(PositionStatus.OPEN, pos.status)
    }

    @Test
    fun `critical liquidation distance closes position at market`() {
        val opened =
            runBlocking {
                positionRepo.save(
                    Position(
                        ticker = "Si",
                        direction = PositionDirection.LONG,
                        quantity = 1,
                        entryPrice = BigDecimal("92000"),
                        currentPrice = BigDecimal("92000"),
                        stopLoss = BigDecimal("91999.50"),
                        takeProfit = BigDecimal("92001.00"),
                        instrumentType = InstrumentType.FUTURES,
                        leverage = BigDecimal("2.0"),
                        goPerContract = BigDecimal("15000"),
                        marginUsed = BigDecimal("15000"),
                        liquidationPrice = BigDecimal("91985"),
                        variationMargin = BigDecimal.ZERO,
                        stopLossPoints = 50,
                        status = PositionStatus.OPEN,
                    ),
                )
            }

        // 91986: остаток буфера 1/15 = 6.7% < 10% → CRITICAL → market close
        runBlocking {
            Mockito
                .`when`(
                    alorClient.verifyOrder(
                        Mockito.anyString(),
                        Mockito.any(BigDecimal::class.java),
                        Mockito.anyString(),
                    ),
                ).thenReturn(AlorClient.OrderExecution("FILLED", 1, BigDecimal("91986")))
        }
        eventPublisher.publishPriceChanged("Si", BigDecimal("91986"))

        awaitUntil {
            runBlocking { positionRepo.findById(opened.id!!).status != PositionStatus.OPEN }
        }
        val closed = runBlocking { positionRepo.findById(opened.id!!) }

        assertEquals(PositionStatus.CLOSED, closed.status)
        assertEquals("LIQUIDATION_CRITICAL", closed.closeReason)
        assertEquals(0, BigDecimal("91986").compareTo(closed.closePrice!!))
        // P&L = (91986 - 92000) * 1000 * 1 = -14 000 ₽
        assertEquals(0, BigDecimal("-14000").compareTo(closed.pnl!!))
        assertTrue(drawdownProtection.isDailyLossLimitReached())
    }

    @Test
    fun `daily loss limit blocks new entry`() {
        drawdownProtection.updateDailyPnl(BigDecimal("-5000"))
        assertTrue(drawdownProtection.isDailyLossLimitReached())

        // Единая точка отключения: TradingGate блокирует до попадания в риск-движок.
        awaitUntil { !tradingGate.isTradingEnabled() }
        val status = runBlocking { tradingGate.getStatus() }
        assertFalse(status.enabled)
        assertTrue(
            status.blocks.any {
                it.reason == TradingBlockReason.DRAWDOWN_PROTECTION ||
                    it.reason == TradingBlockReason.DAILY_LOSS_LIMIT
            },
        )

        eventPublisher.publishStrategyGenerated(signal(BigDecimal("92000")))
        Thread.sleep(200) // дать async-обработчику отработать
        assertTrue(runBlocking { positionRepo.findByStatus(PositionStatus.OPEN) }.isEmpty())
    }

    @Test
    fun `entry rejected when position already open`() {
        runBlocking {
            positionRepo.save(
                Position(
                    ticker = "Si",
                    direction = PositionDirection.LONG,
                    quantity = 1,
                    entryPrice = BigDecimal("92000"),
                    instrumentType = InstrumentType.FUTURES,
                    liquidationPrice = BigDecimal("91985"),
                    status = PositionStatus.OPEN,
                ),
            )
        }

        eventPublisher.publishStrategyGenerated(signal(BigDecimal("92000")))

        awaitUntil {
            meterRegistry.counter("risk.entry.rejected", Tags.of("reason", "MAX_POSITIONS")).count() >= 1.0
        }
        assertEquals(1, runBlocking { positionRepo.findByStatus(PositionStatus.OPEN) }.size)
    }

    @Test
    fun `entry rejected outside trading hours`() {
        Mockito.`when`(tradingHoursGuard.isTradingAllowed()).thenReturn(false)

        // Единая точка отключения: вне торговых часов gate блокирует новые входы.
        assertFalse(tradingGate.isTradingEnabled())
        val status = runBlocking { tradingGate.getStatus() }
        assertTrue(status.blocks.any { it.reason == TradingBlockReason.OUTSIDE_HOURS })

        eventPublisher.publishStrategyGenerated(signal(BigDecimal("92000")))
        Thread.sleep(200) // дать async-обработчику отработать
        assertTrue(runBlocking { positionRepo.findByStatus(PositionStatus.OPEN) }.isEmpty())
    }

    private fun anyBigDecimal(): BigDecimal {
        Mockito.any(BigDecimal::class.java)
        return BigDecimal.ZERO
    }

    private fun signal(target: BigDecimal): Signal =
        Signal(
            ticker = "Si",
            action = StrategyAction.BUY,
            targetPrice = target,
            confidence = 0.9,
            reasoning = "integration test",
            timeframe = "MINUTE_10",
            cycleId = "test-cycle",
        )

    private fun awaitUntil(
        timeoutMs: Long = 10_000,
        intervalMs: Long = 100,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(intervalMs)
        }
        assertTrue(condition(), "condition not met within ${timeoutMs}ms")
    }
}
