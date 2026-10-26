package com.trading.bot.integration

import com.trading.bot.model.CloseReason
import com.trading.bot.model.InstrumentType
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.entity.Position
import com.trading.bot.model.entity.TradingAccount
import com.trading.bot.repository.PositionRepository
import com.trading.bot.repository.TradingAccountRepository
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
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Дополнительное покрытие веток [PositionRepository], не затронутых
 * [PositionRepositoryIntegrationTest]: account-scoped выборки, закрытые позиции и
 * агрегаты просадки, атомарные переходы закрытия, резервации входа и поиск по
 * биржевым orderId/funding-флагу.
 */
@Tag("integration")
class PositionRepositoryCoverageIntegrationTest : AbstractTestContainerTest() {
    @Autowired
    lateinit var repo: PositionRepository

    @Autowired
    lateinit var accountRepository: TradingAccountRepository

    @Autowired
    lateinit var databaseClient: DatabaseClient

    private val base: LocalDateTime = LocalDateTime.of(2026, 8, 7, 10, 0)

    @BeforeEach
    fun cleanup() {
        runBlocking {
            listOf("positions", "entry_reservations", "trading_accounts").forEach { table ->
                databaseClient.sql("DELETE FROM $table").then().awaitSingleOrNull()
            }
        }
    }

    private fun position(
        ticker: String,
        status: PositionStatus = PositionStatus.OPEN,
        accountId: Long? = null,
        pnl: BigDecimal? = null,
        closedAt: LocalDateTime? = null,
        openedAt: LocalDateTime = base,
    ) = Position(
        ticker = ticker,
        direction = PositionDirection.LONG,
        quantity = 10,
        entryPrice = BigDecimal("100"),
        instrumentType = InstrumentType.STOCK,
        status = status,
        accountId = accountId,
        pnl = pnl,
        closedAt = closedAt,
        openedAt = openedAt,
    )

    private fun account(name: String): Long =
        runBlocking {
            accountRepository
                .save(
                    TradingAccount(name = name, alorPortfolio = "P-$name", enabled = false),
                ).id!!
        }

    @Test
    fun `open positions are filtered by status and account`() {
        runBlocking {
            val acc = account("A")
            repo.save(position("AAA"))
            repo.save(position("BBB", accountId = acc))
            repo.save(position("CCC", status = PositionStatus.CLOSED, accountId = acc, closedAt = base))

            assertEquals(2, repo.findOpenCount())
            assertEquals(1, repo.findOpenCountByAccount(null))
            assertEquals(1, repo.findOpenCountByAccount(acc))
            assertEquals(listOf("AAA"), repo.findOpenByAccount(null).map { it.ticker })
            assertEquals(listOf("BBB"), repo.findOpenByAccount(acc).map { it.ticker })
            assertEquals(2, repo.countByAccount(acc))
            assertEquals(2, repo.findByStatus(PositionStatus.OPEN).size)
            assertEquals(1, repo.findByStatusAndTicker(PositionStatus.OPEN, "BBB").size)
            assertEquals(setOf("AAA", "BBB"), repo.findOpenTickersDistinct().toSet())
            assertTrue(repo.hasOpenPosition("AAA"))
            assertFalse(repo.hasOpenPosition("CCC"))
        }
    }

    @Test
    fun `closed positions and PnL aggregates respect ticker account and window filters`() {
        runBlocking {
            val acc = account("A")
            repo.save(position("AAA", status = PositionStatus.CLOSED, pnl = BigDecimal("100"), closedAt = base))
            repo.save(position("AAA", status = PositionStatus.CLOSED, pnl = BigDecimal("-150"), closedAt = base.plusDays(1)))
            repo.save(position("AAA", status = PositionStatus.CLOSED, pnl = BigDecimal("100"), closedAt = base.plusDays(2)))
            repo.save(position("BBB", status = PositionStatus.CLOSED, pnl = BigDecimal("5"), closedAt = base, accountId = acc))
            repo.save(position("BBB", status = PositionStatus.CLOSED, pnl = BigDecimal("20"), closedAt = base.plusDays(1), accountId = acc))

            assertEquals(5, repo.findClosed().size)
            assertEquals(3, repo.findClosed(ticker = "AAA").size)
            assertEquals(5, repo.findClosed(since = base).size)
            assertEquals(3, repo.findClosedSince(base.plusDays(1)).size)
            assertEquals(3, repo.findClosedByAccountSince(null, base).size)
            assertEquals(2, repo.findClosedByAccountSince(acc, base).size)
            assertEquals(3, repo.findClosedByTickerSince("AAA", base).size)
            assertEquals(2, repo.findClosedByTickerAndAccountSince("BBB", acc, base).size)

            val global = repo.findClosedAggregates(null)
            assertEquals(0, BigDecimal("50").compareTo(global.totalRealized))
            assertEquals(0, BigDecimal("100").compareTo(global.peakRealized))

            val scoped = repo.findClosedAggregates(acc)
            assertEquals(0, BigDecimal("25").compareTo(scoped.totalRealized))
            assertEquals(0, BigDecimal("25").compareTo(scoped.peakRealized))
        }
    }

    @Test
    fun `close claim transition and release are atomic`() {
        runBlocking {
            val saved = repo.save(position("AAA").copy(closeOrderId = "old-close"))

            assertTrue(repo.claimForClose(saved.id!!))
            assertFalse(repo.claimForClose(saved.id!!))
            assertFalse(repo.releaseCloseClaim(saved.id!!), "claim with known closeOrderId must not be released")

            assertTrue(
                repo.transitionToClosed(
                    saved.id!!,
                    PositionStatus.CLOSED,
                    BigDecimal("101"),
                    CloseReason.STRATEGY_CLOSE,
                    BigDecimal("10.5"),
                    7,
                ),
            )
            assertFalse(
                repo.transitionToClosed(
                    saved.id!!,
                    PositionStatus.CLOSED,
                    BigDecimal("101"),
                    CloseReason.STRATEGY_CLOSE,
                    BigDecimal("10.5"),
                ),
            )

            val closed = repo.findById(saved.id!!)
            assertEquals(PositionStatus.CLOSED, closed.status)
            assertEquals(0, BigDecimal("101").compareTo(closed.closePrice))
            assertEquals(CloseReason.STRATEGY_CLOSE, closed.closeReason)
            assertEquals(7, closed.cumulativeCloseFillQty)
            assertFalse(closed.pendingClose)
            assertNull(closed.closeOrderId)
            assertNotNull(closed.closedAt)

            val released = repo.save(position("BBB").copy(pendingClose = true))
            assertTrue(repo.releaseCloseClaim(released.id!!))
            assertFalse(repo.releaseCloseClaim(released.id!!))
        }
    }

    @Test
    fun `entry reservation is atomic released and cleaned only when stale`() {
        runBlocking {
            val first = repo.reserveEntry("AAA", PositionDirection.LONG, null)
            assertNotNull(first)
            assertNull(repo.reserveEntry("AAA", PositionDirection.LONG, null), "second reservation must be rejected")
            repo.releaseEntry("AAA", null)
            assertNotNull(repo.reserveEntry("AAA", PositionDirection.LONG, null))

            repo.save(position("BBB"))
            assertNull(repo.reserveEntry("BBB", PositionDirection.LONG, null), "open position must hold the slot")

            assertNotNull(repo.reserveEntry("CCC", PositionDirection.LONG, null))
            assertEquals(0, repo.cleanupStaleEntryReservations(maxAgeMs = 10L * 24 * 60 * 60 * 1000))
            assertNull(repo.reserveEntry("CCC", PositionDirection.LONG, null))

            // Осиротевшая резервация (краш между резервацией и созданием позиции):
            // вставляем строку «два дня назад» напрямую, чтобы не зависеть от часов БД.
            databaseClient
                .sql(
                    "INSERT INTO entry_reservations (ticker, account_id, direction, created_at) " +
                        "VALUES ('OLD', NULL, 'LONG', :staleAt)",
                ).bind("staleAt", LocalDateTime.now().minusDays(2))
                .then()
                .awaitSingleOrNull()
            assertEquals(1, repo.cleanupStaleEntryReservations(maxAgeMs = 60_000))
            assertNull(repo.reserveEntry("CCC", PositionDirection.LONG, null))

            repo.releaseEntry("CCC", null)
            assertNotNull(repo.reserveEntry("CCC", PositionDirection.LONG, null))
        }
    }

    @Test
    fun `order id lookups and funding unknown flag round-trip`() {
        runBlocking {
            val saved =
                repo.save(
                    position("AAA").copy(
                        alorOrderId = "ord-entry",
                        closeOrderId = "ord-close",
                        slOrderId = "ord-sl",
                        tpOrderId = "ord-tp",
                        cycleId = "cycle-1",
                    ),
                )

            assertEquals(saved.id, repo.findByAlorOrderId("ord-entry")?.id)
            assertEquals(saved.id, repo.findByCloseOrderId("ord-close")?.id)
            assertEquals(saved.id, repo.findBySlOrderId("ord-sl")?.id)
            assertEquals(saved.id, repo.findByTpOrderId("ord-tp")?.id)
            assertNull(repo.findByAlorOrderId("missing"))
            assertEquals(1, repo.findByCycleId("cycle-1").size)
            assertEquals(1, repo.findAll().size)

            repo.setFundingUnknown(saved.id!!, true)
            assertTrue(repo.findByAlorOrderId("ord-entry")!!.fundingUnknown)
            repo.setFundingUnknown(saved.id!!, false)
            assertFalse(repo.findByAlorOrderId("ord-entry")!!.fundingUnknown)
        }
    }

    @Test
    fun `cleanupStaleEntryReservations compares against DB time so fresh reservations survive`() {
        runBlocking {
            assertNotNull(repo.reserveEntry("FRESH", PositionDirection.LONG, null))

            // created_at резервации — дефолт БД NOW(), поэтому и порог возраста обязан считаться
            // временем БД. Порог из JVM при зоне приложения впереди зоны БД (МСК против UTC)
            // удалял СВЕЖИЕ резервации → слот освобождался → возможен повторный вход.
            assertEquals(0, repo.cleanupStaleEntryReservations(maxAgeMs = 30L * 60 * 1000))
            assertNull(
                repo.reserveEntry("FRESH", PositionDirection.LONG, null),
                "свежая резервация должна остаться занятой",
            )
        }
    }

    @Test
    fun `new position persists opened_at in Moscow business time`() {
        runBlocking {
            val saved =
                repo.save(
                    Position(
                        ticker = "MSK-OPEN",
                        direction = PositionDirection.LONG,
                        quantity = 1,
                        entryPrice = BigDecimal("100"),
                        instrumentType = InstrumentType.STOCK,
                    ),
                )

            val stored = repo.findById(saved.id!!).openedAt
            val skew = Duration.between(stored, LocalDateTime.now(ZoneId.of("Europe/Moscow"))).abs()
            assertTrue(skew.seconds < 60, "opened_at должен быть временем МСК (расхождение ${skew.seconds}s)")
        }
    }

    @Test
    fun `transitionToClosed writes closed_at in Moscow business time`() {
        runBlocking {
            val saved = repo.save(position("MSK-CLOSE"))
            val moscowClock = Clock.fixed(Instant.parse("2026-10-26T09:15:00Z"), ZoneId.of("Europe/Moscow"))
            val repositoryWithFixedClock = PositionRepository(databaseClient, moscowClock)

            assertTrue(
                repositoryWithFixedClock.transitionToClosed(
                    saved.id!!,
                    PositionStatus.CLOSED,
                    BigDecimal("110"),
                    CloseReason.STOP_LOSS,
                    BigDecimal("10"),
                ),
            )

            // 09:15 UTC = 12:15 МСК: закрытие обязано попасть в московское окно авто-стопа.
            assertEquals(LocalDateTime.of(2026, 10, 26, 12, 15), repo.findById(saved.id!!).closedAt)
        }
    }
}
