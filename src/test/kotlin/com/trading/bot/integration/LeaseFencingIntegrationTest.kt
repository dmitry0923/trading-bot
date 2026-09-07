package com.trading.bot.integration

import com.trading.bot.application.OrderExecutionEngine
import com.trading.bot.application.PnlCalculator
import com.trading.bot.client.AlorClient
import com.trading.bot.client.AlorClient.OrderExecution
import com.trading.bot.client.OrderPurpose
import com.trading.bot.config.AlorConfig
import com.trading.bot.model.InstrumentType
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.entity.Position
import com.trading.bot.model.entity.TradingAccount
import com.trading.bot.repository.OrderOutboxRepository
import com.trading.bot.repository.PositionRepository
import com.trading.bot.repository.TradingAccountRepository
import com.trading.bot.service.DistributedLockService
import com.trading.bot.service.DistributedLockService.LockExecutionResult
import com.trading.bot.service.OrderOutboxService
import com.trading.bot.service.TradeEventService
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import kotlin.coroutines.cancellation.CancellationException

/**
 * Fencing распределённого лока входа (P1-аудит 2026-09-07).
 *
 * Воспроизводит худший сценарий гонки, описанный в аудите:
 *
 * ```text
 *  T0  instance A: acquire Redis lease "position:account:7"
 *  T1  A vsходит в критическую секцию (риск-проверки)
 *  T2  A готовится к placement
 *  T3  lease НЕ продлён / ключ выбит (симуляция истечения TTL)
 *  T4  watchdog отменяет A (кооперативно — блок может продолжиться)
 *  T5  instance B: acquire тот же Redis-лок
 *  T6  B выполняет placement
 *  T7  A всё ещё «добегает» до необратимого действия (NonCancellable)
 * ```
 *
 * Два защитных контура против двойного физического входа:
 * 1. [DistributedLockService.runExclusiveFenced] передаёт блоку [LeaseFence] —
 *    A проверяет [LeaseFence.isHeld] перед необратимыми действиями и прерывается;
 * 2. БД-адмиссия [PositionRepository.reserveEntry] (уникальный слот на (ticker, account)).
 *
 * Проверяем на реальном Redis + Postgres: ПОСЛЕ потери lease A НЕ создаёт вход,
 * B (новый владелец) создаёт ровно один — итого ≤1 физический вход на сигнал.
 */
@Tag("integration")
class LeaseFencingIntegrationTest : AbstractTestContainerTest() {
    companion object {
        @DynamicPropertySource
        @JvmStatic
        @Suppress("unused")
        fun lockProperties(registry: DynamicPropertyRegistry) {
            // Real distributed lock over the container Redis; small TTL to make
            // renewal-loss observable quickly without slow tests.
            registry.add("distributed-lock.enabled") { "true" }
            registry.add("distributed-lock.position-open-ttl-seconds") { "5" }
        }

        private const val TICKER = "SBER"
    }

    private var accountId: Long = 0L
    private lateinit var lockName: String

    @Autowired
    lateinit var positionRepo: PositionRepository

    @Autowired
    lateinit var orderOutboxRepo: OrderOutboxRepository

    @Autowired
    lateinit var orderOutboxService: OrderOutboxService

    @Autowired
    lateinit var alorConfig: AlorConfig

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Autowired
    lateinit var meterRegistry: MeterRegistry

    @Autowired
    lateinit var tradeEventService: TradeEventService

    @Autowired
    lateinit var distributedLockService: DistributedLockService

    @Autowired
    lateinit var redisTemplate: StringRedisTemplate

    @Autowired
    lateinit var tradingAccountRepo: TradingAccountRepository

    @MockitoBean
    lateinit var alorClient: AlorClient

    private lateinit var engine: OrderExecutionEngine
    private val scope = CoroutineScope(SupervisorJob())

    @BeforeEach
    fun setup() {
        runBlocking {
            // Аккаунт нужен FK-ограничением fk_entry_reservations_account держит
            // entry_reservations.account_id на trading_accounts.id.
            val saved =
                tradingAccountRepo.save(
                    TradingAccount(
                        id = null,
                        name = "fence",
                        alorPortfolio = "FENCE",
                        enabled = true,
                    ),
                )
            accountId = requireNotNull(saved.id)
            lockName = "position:account:$accountId"
            positionRepo.deleteAll()
            positionRepo.releaseEntry(TICKER, accountId)
        }
        redisTemplate.delete("distributed-lock:$lockName")

        engine =
            OrderExecutionEngine(
                alorClient = alorClient,
                orderOutboxService = orderOutboxService,
                orderOutboxRepo = orderOutboxRepo,
                positionRepo = positionRepo,
                alorConfig = alorConfig,
                objectMapper = objectMapper,
                tradeEventService = tradeEventService,
                meterRegistry = meterRegistry,
                pnlCalculator = PnlCalculator.plain(),
                instrumentFilter = { true },
                metricPrefix = "fence",
                onEntryOpened = {},
                onPositionClosed = {},
                protectionOrdersEnabled = false,
                portfolioResolver = { "D12345" },
            )

        runBlocking {
            Mockito
                .`when`(
                    alorClient.placeLimitOrder(
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.anyInt(),
                        anyBigDecimal(),
                        Mockito.anyString(),
                        Mockito.anyString(),
                        anyPurpose(),
                    ),
                ).thenReturn("ord-fence")
            Mockito
                .`when`(
                    alorClient.verifyOrder(
                        Mockito.anyString(),
                        Mockito.nullable(BigDecimal::class.java),
                        Mockito.anyString(),
                    ),
                ).thenReturn(OrderExecution("FILLED", 1, BigDecimal("100")))
        }
    }

    @Suppress("ReplaceCallWithBinaryOperator")
    private fun anyBigDecimal(): BigDecimal {
        Mockito.any(BigDecimal::class.java)
        return BigDecimal.ZERO
    }

    @Suppress("ReplaceCallWithBinaryOperator")
    private fun anyPurpose(): OrderPurpose {
        Mockito.any(OrderPurpose::class.java)
        return OrderPurpose.ENTRY
    }

    @Test
    fun `lease holder A aborts entry after lease loss while B opens exactly one`() {
        val started = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        var resultA: LockExecutionResult? = null
        var createdA: Position? = null

        // A: захватывает лок, доходит до «placement-окна» и ждёт, пока тест выбьет lease.
        val jobA =
            scope.launch {
                resultA =
                    distributedLockService.runExclusiveFenced(
                        name = lockName,
                        ttlSeconds = 5,
                        failOpenOnError = false,
                    ) { fence ->
                        val held = requireNotNull(fence)
                        started.complete(Unit)
                        // Кооперативная отмена: проглатываем, имитируя незавершённую
                        // необратимую операцию (запрос к брокеру, не реагирующий на cancel()).
                        try {
                            proceed.await()
                        } catch (_: CancellationException) {
                            // lease отозвана watchdog'ом прямо во время ожидания —
                            // продолжаем как «некооперативный» клиент.
                        }
                        withContext(NonCancellable) {
                            createdA =
                                engine.placeEntryOrder(
                                    TICKER,
                                    PositionDirection.LONG,
                                    qty = 1,
                                    entryPrice = BigDecimal("100"),
                                    accountId = accountId,
                                    buildPosition = { orderId, pending, fillPrice, qty ->
                                        pos(orderId, pending, fillPrice, qty)
                                    },
                                    fence = held,
                                )
                        }
                        if (!held.isHeld()) {
                            // Добежали ДО необратимой точки с протухшей lease —
                            // корректная сигнатура результата: LEASE_LOST.
                            throw CancellationException("lease lost; placement marked aborted")
                        }
                    }
            }
        runBlocking { started.await() }

        // Тест «выбивает» lease: ключ удаляется (симуляция непродления/renew-сбоя).
        redisTemplate.delete("distributed-lock:$lockName")
        runBlocking { proceed.complete(Unit) }

        // B: новый владелец того же лока входит и открывает позицию.
        var resultB: LockExecutionResult? = null
        var createdB: Position? = null
        val jobB =
            scope.launch {
                resultB =
                    distributedLockService.runExclusiveFenced(
                        name = lockName,
                        ttlSeconds = 5,
                        failOpenOnError = false,
                    ) { fence ->
                        val held = requireNotNull(fence)
                        withContext(NonCancellable) {
                            createdB =
                                engine.placeEntryOrder(
                                    TICKER,
                                    PositionDirection.LONG,
                                    qty = 1,
                                    entryPrice = BigDecimal("100"),
                                    accountId = accountId,
                                    buildPosition = { orderId, pending, fillPrice, qty ->
                                        pos(orderId, pending, fillPrice, qty)
                                    },
                                    fence = held,
                                )
                        }
                    }
            }

        runBlocking {
            try {
                jobA.join()
            } catch (_: CancellationException) {
                // jobA сам кинул — join throws при передачи исключения; игнорируем.
            }
            jobB.join()
        }

        assertNull(createdA, "A (протухшая lease) НЕ создаёт физический вход — fence прервал placement")
        assertEquals(LockExecutionResult.LEASE_LOST, resultA, "runExclusiveFenced у A завершается как LEASE_LOST")

        assertNotNull(createdB, "B (новый владелец lease) открывает позицию")
        assertEquals(LockExecutionResult.COMPLETED, resultB, "у B блок выполнен полностью")

        val open =
            runBlocking {
                positionRepo
                    .findByStatus(PositionStatus.OPEN)
                    .filter { it.ticker == TICKER }
            }
        assertEquals(1, open.size, "ровно один физический вход на сигнал для (SBER, account=7)")
        assertEquals(accountId, open.single().accountId)
    }

    private fun pos(
        orderId: String?,
        pending: Boolean,
        fillPrice: BigDecimal,
        qty: Int,
    ): Position =
        Position(
            ticker = TICKER,
            direction = PositionDirection.LONG,
            quantity = qty,
            entryPrice = fillPrice,
            instrumentType = InstrumentType.STOCK,
            status = PositionStatus.OPEN,
            alorOrderId = orderId,
            pendingEntry = pending,
            accountId = accountId,
        )
}
