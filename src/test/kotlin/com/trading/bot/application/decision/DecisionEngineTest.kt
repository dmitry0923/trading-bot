package com.trading.bot.application.decision

import com.trading.bot.application.MarketDataGate
import com.trading.bot.application.OrderBuilder
import com.trading.bot.client.AlorClient
import com.trading.bot.config.DistributedLockConfig
import com.trading.bot.domain.order.OrderParams
import com.trading.bot.domain.risk.EntryRequest
import com.trading.bot.domain.risk.PortfolioRiskEngine
import com.trading.bot.domain.risk.PortfolioRiskReport
import com.trading.bot.domain.risk.PortfolioRiskRequest
import com.trading.bot.domain.risk.PositionSizeResult
import com.trading.bot.domain.risk.RiskEngine
import com.trading.bot.domain.risk.RiskVerdict
import com.trading.bot.domain.signal.Signal
import com.trading.bot.model.InstrumentType
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.StrategyAction
import com.trading.bot.model.entity.Position
import com.trading.bot.repository.PositionRepository
import com.trading.bot.service.DegenerateCaseGuard
import com.trading.bot.service.DistributedLockService
import com.trading.bot.service.HigherTfTrendFilter
import com.trading.bot.service.MlEntryFilter
import com.trading.bot.service.TradingAccountService
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.math.BigDecimal
import java.time.Duration

/**
 * Unit-тесты единого оркестратора входа [DecisionEngine].
 *
 * Покрывают общий пайплайн на фейковом [FakeEntryProfile]:
 * - отказ на каждом этапе (нет профиля, stale данные, риск, pre/post-sizing,
 *   нулевой qty, портфельный блок);
 * - ENFORCED SCALE — пересчёт параметров заявки с уменьшенным qty;
 * - READ_ONLY — вход не блокируется и не масштабируется, только метрика;
 * - успешный проход: gateway получает корректные qty/direction/price,
 *   фиксируется recordStrategyExecution и onOpened.
 */
class DecisionEngineTest {
    private val marketDataGate = Mockito.mock(MarketDataGate::class.java)
    private val alorClient = Mockito.mock(AlorClient::class.java)
    private val orderBuilder = Mockito.mock(OrderBuilder::class.java)
    private val portfolioRiskEngine = Mockito.mock(PortfolioRiskEngine::class.java)
    private val positionRepo = Mockito.mock(PositionRepository::class.java)
    private val meterRegistry = SimpleMeterRegistry()
    private val distributedLockConfig = DistributedLockConfig().apply { enabled = false }
    private val lockRedis = Mockito.mock(StringRedisTemplate::class.java)

    @Suppress("UNCHECKED_CAST")
    private val lockValueOps = Mockito.mock(ValueOperations::class.java) as ValueOperations<String, String>
    private val distributedLockService = DistributedLockService(distributedLockConfig, lockRedis, meterRegistry)
    private val tradingAccountService = Mockito.mock(TradingAccountService::class.java)
    private val mlEntryFilter = Mockito.mock(MlEntryFilter::class.java)
    private val higherTfTrendFilter = Mockito.mock(HigherTfTrendFilter::class.java)
    private val degenerateCaseGuard = Mockito.mock(DegenerateCaseGuard::class.java)

    private var gatewayCalls = 0
    private var gatewayQty: Int = -1
    private var gatewayDirection: PositionDirection? = null
    private var gatewayPrice: BigDecimal? = null
    private var gatewayOpened: Position? = null

    @BeforeEach
    fun setUp() {
        gatewayCalls = 0
        gatewayQty = -1
        gatewayDirection = null
        gatewayPrice = null
        gatewayOpened = null
        Mockito.reset(mlEntryFilter)
        Mockito.reset(higherTfTrendFilter)
        Mockito.reset(degenerateCaseGuard)
        Mockito.`when`(lockRedis.opsForValue()).thenReturn(lockValueOps)
        Mockito.`when`(marketDataGate.isPriceDataFresh("Si")).thenReturn(true)
        runBlocking {
            Mockito.`when`(mlEntryFilter.shouldBlock(any())).thenReturn(null)
            Mockito.`when`(higherTfTrendFilter.shouldBlock(any())).thenReturn(null)
            Mockito.`when`(degenerateCaseGuard.blockReason(any(), any())).thenReturn(null)
            Mockito.`when`(alorClient.getLastPrice("Si")).thenReturn(BigDecimal("100"))
            Mockito.`when`(positionRepo.findByStatus(PositionStatus.OPEN)).thenReturn(emptyList())
            Mockito
                .`when`(portfolioRiskEngine.evaluate(anyPortfolioRiskRequest()))
                .thenReturn(PortfolioRiskReport(allowed = true))
        }
    }

    private fun gateway(): ExecutionGateway =
        { _, direction, qty, entryPrice, _, buildPosition ->
            gatewayCalls++
            gatewayQty = qty
            gatewayDirection = direction
            gatewayPrice = entryPrice
            gatewayOpened = buildPosition("ord-1", false, entryPrice, qty)
            gatewayOpened
        }

    private fun engine(profile: FakeEntryProfile = FakeEntryProfile()): DecisionEngine =
        DecisionEngine(
            marketDataGate,
            alorClient,
            orderBuilder,
            portfolioRiskEngine,
            positionRepo,
            meterRegistry,
            listOf(profile),
            distributedLockService,
            distributedLockConfig,
            tradingAccountService,
            mlEntryFilter,
            higherTfTrendFilter,
            degenerateCaseGuard,
        )

    private fun signal(action: StrategyAction = StrategyAction.BUY): Signal =
        Signal(
            ticker = "Si",
            action = action,
            targetPrice = BigDecimal("101"),
            signalStrength = 0.8,
            reasoning = "test",
            timeframe = "MINUTE_10",
            cycleId = "cycle-1",
        )

    private fun anyPortfolioRiskRequest(): PortfolioRiskRequest {
        Mockito.any(PortfolioRiskRequest::class.java)
        return PortfolioRiskRequest(
            candidateTicker = "Si",
            candidateDirection = PositionDirection.LONG,
            candidateNotionalRub = BigDecimal.ZERO,
            openPositions = emptyList(),
            aum = BigDecimal.ZERO,
        )
    }

    private fun rejectMetric(reason: String): Double =
        meterRegistry
            .counter("test.risk.reject", Tags.of("ticker", "Si", "reason", reason))
            .count()

    @Test
    fun `no matching profile skips entry`() {
        val profile = FakeEntryProfile(matchesTicker = "SBER")
        runBlocking {
            engine(profile).openPosition(signal(), gateway())
        }

        assertEquals(0, gatewayCalls)
        assertTrue(profile.buildEntryRequestCalls == 0)
    }

    @Test
    fun `stale market data blocks entry and records metric`() {
        Mockito.`when`(marketDataGate.isPriceDataFresh("Si")).thenReturn(false)
        runBlocking {
            engine().openPosition(signal(), gateway())
        }

        assertEquals(0, gatewayCalls)
        assertEquals(
            1.0,
            meterRegistry
                .counter("test.entry.rejected", Tags.of("ticker", "Si", "reason", "STALE_DATA"))
                .count(),
        )
    }

    @Test
    fun `risk engine rejection blocks entry`() {
        val profile = FakeEntryProfile(riskVerdict = RiskVerdict.Rejected("limit hit"))
        runBlocking {
            engine(profile).openPosition(signal(), gateway())
        }

        assertEquals(0, gatewayCalls)
        assertEquals(1.0, rejectMetric("limit hit"))
    }

    @Test
    fun `ml filter rejection blocks entry after risk approval`() {
        val profile = FakeEntryProfile(riskVerdict = RiskVerdict.Allowed)
        runBlocking {
            Mockito.`when`(mlEntryFilter.shouldBlock(any())).thenReturn("ML filter: win probability 0.3 below threshold 0.5")
        }

        runBlocking {
            engine(profile).openPosition(signal(), gateway())
        }

        assertEquals(0, gatewayCalls)
        assertEquals(1.0, rejectMetric("ML_FILTER"))
        runBlocking {
            Mockito.verify(mlEntryFilter).shouldBlock(any())
        }
    }

    @Test
    fun `ml filter pass-through when filter allows`() {
        runBlocking {
            engine().openPosition(signal(), gateway())
        }

        assertEquals(1, gatewayCalls)
        assertEquals(PositionDirection.LONG, gatewayDirection)
    }

    @Test
    fun `higher tf filter rejection blocks entry after risk and ml approval`() {
        val profile = FakeEntryProfile(riskVerdict = RiskVerdict.Allowed)
        runBlocking {
            Mockito
                .`when`(higherTfTrendFilter.shouldBlock(any()))
                .thenReturn("Higher-TF filter: higher-TF trend DOWN opposes BUY")
        }

        runBlocking {
            engine(profile).openPosition(signal(), gateway())
        }

        assertEquals(0, gatewayCalls)
        assertEquals(1.0, rejectMetric("MTF_FILTER"))
        runBlocking {
            Mockito.verify(higherTfTrendFilter).shouldBlock(any())
        }
    }

    @Test
    fun `higher tf filter pass-through when filter allows`() {
        runBlocking {
            engine().openPosition(signal(), gateway())
        }

        assertEquals(1, gatewayCalls)
        assertEquals(PositionDirection.LONG, gatewayDirection)
    }

    @Test
    fun `degenerate case rejection blocks entry after freshness check`() {
        val profile = FakeEntryProfile()
        runBlocking {
            Mockito.`when`(degenerateCaseGuard.blockReason(any(), any())).thenReturn("WIDE_SPREAD")
        }

        runBlocking {
            engine(profile).openPosition(signal(), gateway())
        }

        assertEquals(0, gatewayCalls)
        assertEquals(1.0, rejectMetric("WIDE_SPREAD"))
        runBlocking {
            Mockito.verify(degenerateCaseGuard).blockReason(eq("Si"), eq("MINUTE_10"))
        }
    }

    @Test
    fun `degenerate case pass-through when guard allows`() {
        runBlocking {
            engine().openPosition(signal(), gateway())
        }

        assertEquals(1, gatewayCalls)
        assertEquals(PositionDirection.LONG, gatewayDirection)
    }

    @Test
    fun `pre-sizing rejection blocks entry`() {
        val profile = FakeEntryProfile(preSizingReason = "correlation")
        runBlocking {
            engine(profile).openPosition(signal(), gateway())
        }

        assertEquals(0, gatewayCalls)
        assertEquals(1.0, rejectMetric("correlation"))
    }

    @Test
    fun `post-sizing rejection blocks entry`() {
        val profile = FakeEntryProfile(postSizingReason = "exposure")
        runBlocking {
            engine(profile).openPosition(signal(), gateway())
        }

        assertEquals(0, gatewayCalls)
        assertEquals(1.0, rejectMetric("exposure"))
    }

    @Test
    fun `zero quantity from order builder blocks entry`() {
        val profile =
            FakeEntryProfile(
                size =
                    PositionSizeResult(
                        quantity = 0,
                        marginRequired = BigDecimal.ZERO,
                        riskAmount = BigDecimal.ZERO,
                        liquidationPrice = null,
                        reason = "kelly=0",
                    ),
            )
        runBlocking {
            engine(profile).openPosition(signal(), gateway())
        }

        assertEquals(0, gatewayCalls)
    }

    @Test
    fun `enforced portfolio block prevents entry`() {
        runBlocking {
            Mockito
                .`when`(portfolioRiskEngine.evaluate(anyPortfolioRiskRequest()))
                .thenReturn(PortfolioRiskReport(allowed = false, reasons = listOf("VAR", "HHI")))
        }
        runBlocking {
            engine().openPosition(signal(), gateway())
        }

        assertEquals(0, gatewayCalls)
        assertEquals(1.0, rejectMetric("VAR|HHI"))
    }

    @Test
    fun `enforced portfolio scale recomputes order params with reduced qty`() {
        runBlocking {
            Mockito
                .`when`(portfolioRiskEngine.evaluate(anyPortfolioRiskRequest()))
                .thenReturn(PortfolioRiskReport(allowed = true, scaleDownFactor = BigDecimal("0.5")))
        }
        val profile = FakeEntryProfile()
        runBlocking {
            engine(profile).openPosition(signal(), gateway())
        }

        assertEquals(1, gatewayCalls)
        assertEquals(2, gatewayQty)
        assertEquals(2, profile.lastSizeForOrderParams?.quantity)
        assertEquals(
            1.0,
            meterRegistry.counter("test.portfolio.scaled", Tags.of("ticker", "Si")).count(),
        )
    }

    @Test
    fun `read-only mode never blocks or scales the entry`() {
        runBlocking {
            Mockito
                .`when`(portfolioRiskEngine.evaluate(anyPortfolioRiskRequest()))
                .thenReturn(
                    PortfolioRiskReport(
                        allowed = false,
                        reasons = listOf("VAR"),
                        scaleDownFactor = BigDecimal("0.1"),
                    ),
                )
        }
        runBlocking {
            engine(FakeEntryProfile(mode = PortfolioMode.READ_ONLY)).openPosition(signal(), gateway())
        }

        assertEquals(1, gatewayCalls)
        assertEquals(5, gatewayQty)
        assertEquals(
            1.0,
            meterRegistry.counter("futures.portfolio.readonly", Tags.of("reasons", "VAR")).count(),
        )
    }

    @Test
    fun `successful entry passes qty direction price and triggers side effects`() {
        val profile = FakeEntryProfile()
        runBlocking {
            engine(profile).openPosition(signal(), gateway())
        }

        assertEquals(1, gatewayCalls)
        assertEquals(5, gatewayQty)
        assertEquals(PositionDirection.LONG, gatewayDirection)
        assertEquals(0, BigDecimal("100").compareTo(gatewayPrice))
        assertEquals(1, profile.onOpenedCalls)
        runBlocking {
            Mockito.verify(orderBuilder).recordStrategyExecution(any(), any())
        }
    }

    @Test
    fun `sell signal opens short position`() {
        runBlocking {
            engine().openPosition(signal(action = StrategyAction.SELL), gateway())
        }

        assertEquals(1, gatewayCalls)
        assertEquals(PositionDirection.SHORT, gatewayDirection)
        assertTrue(gatewayOpened != null)
    }

    @Test
    fun `non entry actions are ignored`() {
        runBlocking {
            engine().openPosition(signal(action = StrategyAction.HOLD), gateway())
        }

        assertEquals(0, gatewayCalls)
        assertNull(gatewayOpened)
    }

    @Test
    fun `distributed lock contention skips entry`() {
        distributedLockConfig.enabled = true
        Mockito
            .`when`(
                lockValueOps.setIfAbsent(
                    Mockito.any(String::class.java),
                    Mockito.any(String::class.java),
                    Mockito.any(Duration::class.java),
                ),
            ).thenReturn(false)
        runBlocking {
            engine().openPosition(signal(), gateway())
        }

        assertEquals(0, gatewayCalls)
    }

    @Test
    fun `distributed lock acquired allows entry`() {
        distributedLockConfig.enabled = true
        Mockito
            .`when`(
                lockValueOps.setIfAbsent(
                    Mockito.any(String::class.java),
                    Mockito.any(String::class.java),
                    Mockito.any(Duration::class.java),
                ),
            ).thenReturn(true)
        runBlocking {
            engine().openPosition(signal(), gateway())
        }

        assertEquals(1, gatewayCalls)
        assertEquals(5, gatewayQty)
    }

    @Test
    fun `distributed lock disabled runs without redis`() {
        distributedLockConfig.enabled = false
        runBlocking {
            engine().openPosition(signal(), gateway())
        }

        assertEquals(1, gatewayCalls)
    }

    private class FakeEntryProfile(
        var riskVerdict: RiskVerdict = RiskVerdict.Allowed,
        var preSizingReason: String? = null,
        var postSizingReason: String? = null,
        var size: PositionSizeResult =
            PositionSizeResult(
                quantity = 5,
                marginRequired = BigDecimal.ZERO,
                riskAmount = BigDecimal.ZERO,
                liquidationPrice = null,
                reason = null,
            ),
        var orderParams: OrderParams = OrderParams(direction = PositionDirection.LONG, quantity = 5),
        var mode: PortfolioMode = PortfolioMode.ENFORCED,
        var matchesTicker: String = "Si",
    ) : EntryProfile {
        var buildEntryRequestCalls = 0
        var onOpenedCalls = 0
        var lastSizeForOrderParams: PositionSizeResult? = null

        override val instrumentType: InstrumentType = InstrumentType.FUTURES
        override val metricPrefix: String = "test"

        override val riskEngine: RiskEngine =
            object : RiskEngine {
                override suspend fun canEnter(request: EntryRequest): RiskVerdict = riskVerdict
            }

        override fun matches(ticker: String): Boolean = ticker == matchesTicker

        override suspend fun buildEntryRequest(
            signal: Signal,
            entryPrice: BigDecimal,
            openPositions: List<Position>,
            accountId: Long?,
        ): EntryRequest {
            buildEntryRequestCalls++
            return EntryRequest(
                ticker = signal.ticker,
                action = signal.action,
                entryPrice = entryPrice,
                direction = signal.direction(),
                portfolioMoney = BigDecimal("100000"),
                currentGo = BigDecimal.ZERO,
                openPositions = openPositions,
                accountId = accountId,
            )
        }

        override suspend fun preSizingChecks(
            ticker: String,
            openPositions: List<Position>,
        ): String? = preSizingReason

        override suspend fun sizePosition(
            signal: Signal,
            entryPrice: BigDecimal,
            request: EntryRequest,
        ): PositionSizeResult = size

        override suspend fun postSizingChecks(
            direction: PositionDirection,
            entryPrice: BigDecimal,
            size: PositionSizeResult,
            openPositions: List<Position>,
        ): String? = postSizingReason

        override fun buildOrderParams(
            ticker: String,
            direction: PositionDirection,
            entryPrice: BigDecimal,
            size: PositionSizeResult,
            request: EntryRequest,
        ): OrderParams {
            lastSizeForOrderParams = size
            return orderParams.copy(quantity = size.quantity)
        }

        override fun portfolioMode(): PortfolioMode = mode

        override fun buildPosition(
            signal: Signal,
            params: OrderParams,
            orderId: String?,
            pending: Boolean,
            fillPrice: BigDecimal,
            qty: Int,
        ): Position =
            Position(
                ticker = signal.ticker,
                direction = params.direction,
                quantity = qty,
                entryPrice = fillPrice,
                instrumentType = instrumentType,
                status = PositionStatus.OPEN,
            )

        override suspend fun onOpened(
            signal: Signal,
            opened: Position,
            params: OrderParams,
            size: PositionSizeResult,
        ) {
            onOpenedCalls++
        }
    }
}
