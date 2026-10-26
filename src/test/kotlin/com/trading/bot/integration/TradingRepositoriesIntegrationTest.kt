package com.trading.bot.integration

import com.trading.bot.model.StrategyAction
import com.trading.bot.model.entity.BotSettings
import com.trading.bot.model.entity.DeploymentApprovalRecord
import com.trading.bot.model.entity.ExperimentDecision
import com.trading.bot.model.entity.FrozenStrategyRecord
import com.trading.bot.model.entity.FundingHistoryRecord
import com.trading.bot.model.entity.Strategy
import com.trading.bot.model.entity.StrategyAdjustment
import com.trading.bot.model.entity.TradeEvent
import com.trading.bot.repository.DeploymentApprovalRepository
import com.trading.bot.repository.ExperimentDecisionRepository
import com.trading.bot.repository.FrozenStrategyRepository
import com.trading.bot.repository.FundingHistoryRepository
import com.trading.bot.repository.SettingsRepository
import com.trading.bot.repository.StrategyAdjustmentRepository
import com.trading.bot.repository.StrategyRepository
import com.trading.bot.repository.TradeEventRepository
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.DatabaseClient
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * Покрытие репозиториев, у которых не было интеграционных тестов: strategies,
 * strategy_adjustments, experiment_decisions, frozen_strategy, funding_history,
 * bot_settings. Testcontainers Postgres (миграции Liquibase применяет контекст).
 */
@Tag("integration")
class TradingRepositoriesIntegrationTest : AbstractTestContainerTest() {
    @Autowired
    lateinit var strategyRepository: StrategyRepository

    @Autowired
    lateinit var adjustmentRepository: StrategyAdjustmentRepository

    @Autowired
    lateinit var experimentRepository: ExperimentDecisionRepository

    @Autowired
    lateinit var frozenRepository: FrozenStrategyRepository

    @Autowired
    lateinit var fundingRepository: FundingHistoryRepository

    @Autowired
    lateinit var settingsRepository: SettingsRepository

    @Autowired
    lateinit var tradeEventRepository: TradeEventRepository

    @Autowired
    lateinit var deploymentApprovalRepository: DeploymentApprovalRepository

    @Autowired
    lateinit var databaseClient: DatabaseClient

    @BeforeEach
    fun cleanup() {
        runBlocking {
            listOf(
                "strategies",
                "strategy_adjustments",
                "experiment_decisions",
                "frozen_strategy",
                "funding_history",
                "bot_settings",
                "trade_events",
                "deployment_approval",
            ).forEach { table -> databaseClient.sql("DELETE FROM $table").then().awaitSingleOrNull() }
        }
    }

    private fun strategy(
        ticker: String,
        cycleId: String = "cycle-1",
        createdAt: LocalDateTime = LocalDateTime.of(2026, 8, 7, 10, 0),
    ) = Strategy(
        ticker = ticker,
        action = StrategyAction.BUY,
        targetPrice = BigDecimal("100.0"),
        quantity = 10,
        signalStrength = 0.7,
        reasoning = "reason",
        cycleId = cycleId,
        validUntil = createdAt.plusHours(1),
        createdAt = createdAt,
    )

    @Test
    fun `strategy repository saves, orders and updates order params`() {
        runBlocking {
            val first = strategyRepository.save(strategy("AAA", createdAt = LocalDateTime.of(2026, 8, 7, 10, 0)))
            val second = strategyRepository.save(strategy("BBB", createdAt = LocalDateTime.of(2026, 8, 7, 11, 0)))
            assertNotNull(first.id)
            assertNotNull(second.id)

            val recent = strategyRepository.findTop50ByOrderByCreatedAtDesc()
            assertEquals(listOf("BBB", "AAA"), recent.map { it.ticker })
            assertEquals("AAA", strategyRepository.findTopByTickerOrderByCreatedAtDesc("AAA")?.ticker)
            assertNull(strategyRepository.findTopByTickerOrderByCreatedAtDesc("NOPE"))
            assertEquals(listOf("AAA", "BBB"), strategyRepository.findByCycleId("cycle-1").map { it.ticker })

            val updated =
                strategyRepository.updateOrderParams(
                    cycleId = "cycle-1",
                    ticker = "AAA",
                    timeframe = "MINUTE_10",
                    quantity = 3,
                    stopLoss = BigDecimal("95.5"),
                    takeProfit = BigDecimal("110.0"),
                    trailingStop = true,
                )
            assertEquals(1, updated)
            val reloaded = strategyRepository.findByCycleId("cycle-1").first { it.ticker == "AAA" }
            assertEquals(3, reloaded.quantity)
            assertEquals(0, BigDecimal("95.5").compareTo(reloaded.stopLoss))
            assertEquals(0, BigDecimal("110.0").compareTo(reloaded.takeProfit))
            assertTrue(reloaded.trailingStop)

            assertEquals(
                1,
                strategyRepository.updateOrderParams("cycle-1", "AAA", "MINUTE_10", 1, null, null, false),
            )
            val cleared = strategyRepository.findByCycleId("cycle-1").first { it.ticker == "AAA" }
            assertNull(cleared.stopLoss)
            assertNull(cleared.takeProfit)

            assertEquals(0, strategyRepository.updateOrderParams("unknown-cycle", "AAA", "MINUTE_10", 1, null, null, false))
        }
    }

    @Test
    fun `order params of two timeframes in one cycle stay independent`() {
        runBlocking {
            val minute =
                strategyRepository.save(
                    strategy("AAA").copy(timeframe = "MINUTE_10", createdAt = LocalDateTime.of(2026, 8, 7, 10, 0)),
                )
            strategyRepository.save(
                strategy("AAA").copy(timeframe = "HOUR_1", createdAt = LocalDateTime.of(2026, 8, 7, 10, 1)),
            )
            assertNotNull(minute.id)

            // Раньше UPDATE матчил только cycle_id+ticker и фактические qty/SL/TP
            // одного таймфрейма перезаписывали строку другого.
            assertEquals(
                1,
                strategyRepository.updateOrderParams(
                    cycleId = "cycle-1",
                    ticker = "AAA",
                    timeframe = "MINUTE_10",
                    quantity = 3,
                    stopLoss = BigDecimal("95.5"),
                    takeProfit = BigDecimal("110.0"),
                    trailingStop = true,
                ),
            )
            val rows = strategyRepository.findByCycleId("cycle-1")
            val minuteRow = rows.first { it.timeframe == "MINUTE_10" }
            val hourRow = rows.first { it.timeframe == "HOUR_1" }
            assertEquals(3, minuteRow.quantity)
            assertEquals(0, BigDecimal("95.5").compareTo(minuteRow.stopLoss))
            assertTrue(minuteRow.trailingStop)
            assertEquals(10, hourRow.quantity)
            assertNull(hourRow.stopLoss)
            assertNull(hourRow.takeProfit)
            assertFalse(hourRow.trailingStop)
        }
    }

    @Test
    fun `strategy adjustment repository handles null values and updates`() {
        runBlocking {
            val inserted =
                adjustmentRepository.save(
                    StrategyAdjustment(
                        ticker = "AAA",
                        adjustmentType = "CONFIDENCE",
                        triggeredBy = "online-calibration",
                        reason = "first",
                    ),
                )
            assertNotNull(inserted.id)
            val updated =
                adjustmentRepository.save(
                    inserted.copy(oldValue = BigDecimal("0.5"), newValue = BigDecimal("0.6"), reason = "second"),
                )
            assertEquals(inserted.id, updated.id)

            val byTicker = adjustmentRepository.findByTickerOrderByCreatedAtDesc("AAA")
            assertEquals(1, byTicker.size)
            assertEquals("second", byTicker[0].reason)
            assertEquals(0, BigDecimal("0.6").compareTo(byTicker[0].newValue))
            assertEquals(1, adjustmentRepository.findAll().size)

            adjustmentRepository.deleteAll()
            assertTrue(adjustmentRepository.findAll().isEmpty())
        }
    }

    @Test
    fun `experiment decision repository saves, filters and marks results`() {
        runBlocking {
            val base = LocalDateTime.of(2026, 8, 7, 10, 0)
            experimentRepository.save(
                ExperimentDecision(
                    cycleId = "cycle-1",
                    experimentId = "exp",
                    arm = "CONTROL",
                    ticker = "AAA",
                    action = "BUY",
                    targetPrice = BigDecimal("100"),
                    quantity = 5,
                    signalStrength = 0.65,
                    reasoning = "control",
                    isPaper = false,
                    executed = true,
                    decidedAt = base,
                ),
            )
            experimentRepository.save(
                ExperimentDecision(
                    cycleId = "cycle-1",
                    experimentId = "exp",
                    arm = "VARIANT",
                    ticker = "AAA",
                    action = "SELL",
                    isPaper = true,
                    decidedAt = base.plusMinutes(1),
                ),
            )

            val control = experimentRepository.findByCycleIdArmAndTicker("cycle-1", "CONTROL", "AAA").singleOrNull()
            assertEquals(0.65, control?.signalStrength)
            assertEquals(5, control?.quantity)
            assertTrue(experimentRepository.findByCycleIdArmAndTicker("cycle-1", "MISSING", "AAA").isEmpty())

            val recent = experimentRepository.findRecent(10)
            assertEquals(listOf("VARIANT", "CONTROL"), recent.map { it.arm })
            assertEquals(1, experimentRepository.findRecent(0).size)

            experimentRepository.markResult("cycle-1", "CONTROL", "AAA", null, BigDecimal("12.5"))
            val marked = experimentRepository.findByCycleIdArmAndTicker("cycle-1", "CONTROL", "AAA").single()
            assertTrue(marked.closed)
            assertEquals(0, BigDecimal("12.5").compareTo(marked.resultPnl))
        }
    }

    @Test
    fun `experiment results are marked per ticker and timeframe`() {
        runBlocking {
            val base = LocalDateTime.of(2026, 8, 7, 10, 0)
            // Один цикл, два тикера: старая выборка `.one()` падала бы здесь
            // (IncorrectResultSize) и результат не записывался ни одной руке.
            for ((index, ticker) in listOf("AAA", "BBB").withIndex()) {
                experimentRepository.save(
                    ExperimentDecision(
                        cycleId = "cycle-1",
                        experimentId = "exp",
                        arm = "CONTROL",
                        ticker = ticker,
                        timeframe = "MINUTE_10",
                        action = "BUY",
                        quantity = 5,
                        isPaper = false,
                        decidedAt = base.plusMinutes(index.toLong()),
                    ),
                )
                experimentRepository.save(
                    ExperimentDecision(
                        cycleId = "cycle-1",
                        experimentId = "exp",
                        arm = "VARIANT",
                        ticker = ticker,
                        timeframe = "MINUTE_10",
                        action = "BUY",
                        quantity = 5,
                        isPaper = true,
                        decidedAt = base.plusMinutes(index.toLong() + 10),
                    ),
                )
            }

            assertEquals(1, experimentRepository.findByCycleIdArmAndTicker("cycle-1", "CONTROL", "AAA").size)

            experimentRepository.markResult("cycle-1", "CONTROL", "AAA", "MINUTE_10", BigDecimal("100"))
            experimentRepository.markResult("cycle-1", "VARIANT", "AAA", "MINUTE_10", BigDecimal("-100"))

            val aaaControl = experimentRepository.findByCycleIdArmAndTicker("cycle-1", "CONTROL", "AAA").single()
            val aaaVariant = experimentRepository.findByCycleIdArmAndTicker("cycle-1", "VARIANT", "AAA").single()
            val bbbControl = experimentRepository.findByCycleIdArmAndTicker("cycle-1", "CONTROL", "BBB").single()
            val bbbVariant = experimentRepository.findByCycleIdArmAndTicker("cycle-1", "VARIANT", "BBB").single()

            assertEquals(0, BigDecimal("100").compareTo(aaaControl.resultPnl))
            assertEquals(0, BigDecimal("-100").compareTo(aaaVariant.resultPnl))
            assertTrue(aaaControl.closed && aaaVariant.closed)
            // Чужая рука/тикер не тронуты.
            assertFalse(bbbControl.closed)
            assertFalse(bbbVariant.closed)
            assertNull(bbbControl.resultPnl)
            assertNull(bbbVariant.resultPnl)
        }
    }

    @Test
    fun `frozen strategy repository upserts nullable fields and deletes`() {
        runBlocking {
            frozenRepository.save(
                FrozenStrategyRecord(
                    ticker = "CNYRUBF",
                    slPercent = 1.5,
                    tpPercent = 3.0,
                    slPoints = 150,
                    tpPoints = 300,
                    confidenceThreshold = 0.6,
                    leverage = 2.0,
                    riskPerTradePercent = 5.0,
                    futuresMaxContractsPerPosition = 2,
                    strategyVersion = "v1",
                    gitCommitSha = "abc",
                    updatedAt = Instant.parse("2026-08-07T10:00:00Z"),
                ),
            )
            val loaded = frozenRepository.find("CNYRUBF")
            assertEquals(2, loaded?.futuresMaxContractsPerPosition)
            assertEquals("v1", loaded?.strategyVersion)

            frozenRepository.save(
                FrozenStrategyRecord(
                    ticker = "CNYRUBF",
                    leverage = 3.0,
                    strategyVersion = "v2",
                ),
            )
            val upserted = frozenRepository.find("CNYRUBF")
            assertNull(upserted?.slPercent)
            assertNull(upserted?.tpPoints)
            assertNull(upserted?.gitCommitSha)
            assertEquals(3.0, upserted?.leverage)
            assertEquals(1, frozenRepository.latest().size)

            frozenRepository.delete("CNYRUBF")
            assertNull(frozenRepository.find("CNYRUBF"))
            assertTrue(frozenRepository.latest().isEmpty())
        }
    }

    @Test
    fun `funding history is idempotent and filters by ticker and date window`() {
        runBlocking {
            val records =
                listOf(
                    FundingHistoryRecord(
                        ticker = "AAA",
                        clearingDate = LocalDate.of(2026, 8, 5),
                        rawValue = BigDecimal("0.1"),
                        valueRubPerContract = BigDecimal("10"),
                    ),
                    FundingHistoryRecord(
                        ticker = "AAA",
                        clearingDate = LocalDate.of(2026, 8, 6),
                        rawValue = BigDecimal("0.2"),
                        valueRubPerContract = BigDecimal("20"),
                    ),
                    FundingHistoryRecord(
                        ticker = "BBB",
                        clearingDate = LocalDate.of(2026, 8, 5),
                        rawValue = BigDecimal("0.3"),
                        valueRubPerContract = BigDecimal("30"),
                    ),
                )
            assertEquals(3, fundingRepository.saveAll(records))
            // Повторная запись тех же дат больше НЕ «пустая»: upsert обновляет
            // raw_value/value (иначе исправленная спека lotSize не доезжала до БД и
            // ошибка множителя оставалась навсегда) — скан-1 P2.
            assertEquals(3, fundingRepository.saveAll(records), "repeated save updates rows, never duplicates")
            assertEquals(2, fundingRepository.findValuesBetween("AAA", LocalDate.of(2026, 8, 5), LocalDate.of(2026, 9, 1)).size)
            assertEquals(0, fundingRepository.saveAll(emptyList()))

            assertTrue(fundingRepository.hasData("AAA"))
            assertTrue(!fundingRepository.hasData("CCC"))

            val window = fundingRepository.findValuesBetween("AAA", LocalDate.of(2026, 8, 5), LocalDate.of(2026, 8, 6))
            assertEquals(2, window.size)
            assertEquals(0, BigDecimal("10").compareTo(window[LocalDate.of(2026, 8, 5)]))
            assertEquals(1, fundingRepository.findValuesBetween("AAA", LocalDate.of(2026, 8, 6), LocalDate.of(2026, 8, 6)).size)
            assertTrue(fundingRepository.findValuesBetween("CCC", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 9)).isEmpty())
        }
    }

    @Test
    fun `settings repository round-trips and updates global settings`() {
        runBlocking {
            assertNull(settingsRepository.loadSettings())

            val settings = BotSettings(tradingMode = "LIVE", maxOpenPositions = 7, timeframes = listOf("HOUR_1"))
            settingsRepository.saveSettings(settings)
            val loaded = settingsRepository.loadSettings()
            assertEquals("LIVE", loaded?.tradingMode)
            assertEquals(7, loaded?.maxOpenPositions)
            assertEquals(listOf("HOUR_1"), loaded?.timeframes)

            settingsRepository.saveSettings(settings.copy(tradingMode = "SIMULATION", maxOpenPositions = 2))
            val updated = settingsRepository.loadSettings()
            assertEquals("SIMULATION", updated?.tradingMode)
            assertEquals(2, updated?.maxOpenPositions)
        }
    }

    @Test
    fun `trade events append with per aggregate sequence and read back`() {
        runBlocking {
            val first = UUID.randomUUID()
            val second = UUID.randomUUID()
            val t0 = LocalDateTime.of(2026, 8, 7, 10, 0)
            tradeEventRepository.append(event(first, "POSITION_OPENED", t0))
            tradeEventRepository.append(event(first, "POSITION_CLOSED", t0.plusMinutes(1)))
            tradeEventRepository.append(event(second, "POSITION_OPENED", t0.plusMinutes(2)))

            val events = tradeEventRepository.findByAggregateId(first)
            assertEquals(listOf(1L, 2L), events.map { it.sequenceNumber })
            assertEquals(listOf("POSITION_OPENED", "POSITION_CLOSED"), events.map { it.eventType })
            assertTrue(events[0].payload.contains("\"pnl\""), "jsonb payload must round-trip: ${events[0].payload}")

            val all = tradeEventRepository.findByAggregateIds(listOf(first, second))
            assertEquals(3, all.size)
            assertEquals(t0.plusMinutes(2), all.last().occurredAt)
            assertTrue(tradeEventRepository.findByAggregateIds(emptyList()).isEmpty())
        }
    }

    @Test
    fun `deployment approval upserts nullable fields and lists rows`() {
        runBlocking {
            deploymentApprovalRepository.save(
                DeploymentApprovalRecord(
                    ticker = "CNYRUBF",
                    status = "LIVE_ALLOWED",
                    frozenConfidenceThreshold = 0.6,
                    paramsHash = "hash-1",
                    approvedAt = Instant.parse("2026-08-07T10:00:00Z"),
                ),
            )
            deploymentApprovalRepository.save(
                DeploymentApprovalRecord(
                    ticker = "CNYRUBF",
                    status = "REVOKED",
                    approvedAt = Instant.parse("2026-08-08T10:00:00Z"),
                ),
            )

            val rows = deploymentApprovalRepository.latest()
            assertEquals(1, rows.size)
            assertEquals("REVOKED", rows[0].status)
            assertNull(rows[0].frozenConfidenceThreshold)
            assertNull(rows[0].paramsHash)
            assertEquals(Instant.parse("2026-08-08T10:00:00Z"), rows[0].approvedAt)
        }
    }

    private fun event(
        aggregateId: UUID,
        type: String,
        at: LocalDateTime,
    ) = TradeEvent(
        aggregateId = aggregateId,
        eventType = type,
        payload = """{"pnl":"1.5"}""",
        occurredAt = at,
        sequenceNumber = 0,
    )
}
