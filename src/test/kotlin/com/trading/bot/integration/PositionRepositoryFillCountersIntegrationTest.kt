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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.DatabaseClient
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * Ветви [PositionRepository], не покрытые существующими интеграционными тестами
 * (скан-19: 43 из 104): сброс счётчиков защитных/закрывающих филлов (предусловие
 * `expectedOrderId` — защита от запоздалого reset со старой заявки), полный
 * round-trip всех опциональных колонок в [PositionRepository.toPosition],
 * негативные переходы claim/release/close и фильтры закрытых выборок.
 */
@Tag("integration")
class PositionRepositoryFillCountersIntegrationTest : AbstractTestContainerTest() {
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
        ticker: String = "AAA",
        status: PositionStatus = PositionStatus.OPEN,
        accountId: Long? = null,
        slOrderId: String? = null,
        tpOrderId: String? = null,
        closeOrderId: String? = null,
        cumulativeSl: Int = 0,
        cumulativeTp: Int = 0,
        cumulativeClose: Int = 0,
        pendingClose: Boolean = false,
        closedAt: LocalDateTime? = null,
    ) = Position(
        ticker = ticker,
        direction = PositionDirection.LONG,
        quantity = 10,
        entryPrice = BigDecimal("100"),
        instrumentType = InstrumentType.STOCK,
        status = status,
        accountId = accountId,
        closedAt = closedAt,
        openedAt = base,
        slOrderId = slOrderId,
        tpOrderId = tpOrderId,
        closeOrderId = closeOrderId,
        cumulativeSlFillQty = cumulativeSl,
        cumulativeTpFillQty = cumulativeTp,
        cumulativeCloseFillQty = cumulativeClose,
        pendingClose = pendingClose,
    )

    private fun account(name: String): Long =
        runBlocking {
            val saved = accountRepository.save(TradingAccount(name = name, alorPortfolio = "P-$name", enabled = false))
            saved.id!!
        }

    // ─── счётчики защитных филлов ──────────────────────────────────────

    @Test
    fun `protection fill counters are reset only for the matching order`() {
        runBlocking {
            val id = repo.save(position(slOrderId = "SL-1", tpOrderId = "TP-1", cumulativeSl = 3, cumulativeTp = 4)).id!!

            // Чужой (устаревший) id не должен обнулять счётчик новой заявки.
            repo.resetProtectionFillQty(id, PositionRepository.ProtectionSide.SL, expectedOrderId = "SL-OLD")
            var stored = repo.findByIdOrNull(id)!!
            assertEquals(3, stored.cumulativeSlFillQty, "запоздалый reset старой заявки игнорируется")

            repo.resetProtectionFillQty(id, PositionRepository.ProtectionSide.SL, expectedOrderId = "SL-1")
            stored = repo.findByIdOrNull(id)!!
            assertEquals(0, stored.cumulativeSlFillQty)
            assertEquals(4, stored.cumulativeTpFillQty, "TP-счётчик не затронут")

            repo.resetProtectionFillQty(id, PositionRepository.ProtectionSide.TP, expectedOrderId = "TP-1")
            assertEquals(0, repo.findByIdOrNull(id)!!.cumulativeTpFillQty)

            // Без expectedOrderId — безусловный сброс (только OPEN).
            repo.resetProtectionFillQty(id, PositionRepository.ProtectionSide.SL)
            assertEquals(0, repo.findByIdOrNull(id)!!.cumulativeSlFillQty)
        }
    }

    @Test
    fun `closed position protection counters are historical and never reset`() {
        runBlocking {
            val id =
                repo
                    .save(
                        position(
                            status = PositionStatus.CLOSED,
                            closedAt = base,
                            slOrderId = "SL-1",
                            cumulativeSl = 5,
                        ),
                    ).id!!

            repo.resetProtectionFillQty(id, PositionRepository.ProtectionSide.SL)
            repo.resetProtectionFillQty(id, PositionRepository.ProtectionSide.SL, expectedOrderId = "SL-1")

            assertEquals(5, repo.findByIdOrNull(id)!!.cumulativeSlFillQty, "у закрытой позиции счётчик — аудит")
        }
    }

    @Test
    fun `close fill counter reset honours the expected close order`() {
        runBlocking {
            val id = repo.save(position(closeOrderId = "CL-1", cumulativeClose = 6)).id!!

            repo.resetCloseFillQty(id, expectedOrderId = "CL-OLD")
            assertEquals(6, repo.findByIdOrNull(id)!!.cumulativeCloseFillQty)

            repo.resetCloseFillQty(id, expectedOrderId = "CL-1")
            assertEquals(0, repo.findByIdOrNull(id)!!.cumulativeCloseFillQty)

            // Безусловный сброс (id заявки не передан).
            repo.save(repo.findByIdOrNull(id)!!.also { it.cumulativeCloseFillQty = 2 })
            repo.resetCloseFillQty(id)
            assertEquals(0, repo.findByIdOrNull(id)!!.cumulativeCloseFillQty)
        }
    }

    @Test
    fun `closed position close fill counter is not reset`() {
        runBlocking {
            val id =
                repo.save(position(status = PositionStatus.CLOSED, closedAt = base, cumulativeClose = 4)).id!!

            repo.resetCloseFillQty(id)

            assertEquals(4, repo.findByIdOrNull(id)!!.cumulativeCloseFillQty)
        }
    }

    // ─── полный round-trip колонок ──────────────────────────────────────

    @Test
    fun `every optional column survives a round trip`() {
        runBlocking {
            val acc = account("RICH")
            val rich =
                position(accountId = acc, status = PositionStatus.CLOSED, closedAt = base).copy(
                    currentPrice = BigDecimal("101"),
                    closePrice = BigDecimal("102"),
                    stopLoss = BigDecimal("95"),
                    takeProfit = BigDecimal("110"),
                    leverage = BigDecimal("2.5"),
                    goPerContract = BigDecimal("500"),
                    marginUsed = BigDecimal("1000"),
                    liquidationPrice = BigDecimal("80"),
                    variationMargin = BigDecimal("12.5"),
                    stopLossPoints = 25,
                    trailingStopPrice = BigDecimal("99"),
                    pnl = BigDecimal("55"),
                    realizedPnl = BigDecimal("40"),
                    alorOrderId = "A-1",
                    closeOrderId = "CLOSE-1",
                    slOrderId = "SL-1",
                    tpOrderId = "TP-1",
                    slOrderPrice = BigDecimal("95"),
                    tpOrderPrice = BigDecimal("110"),
                    slPendingReplace = true,
                    tpPendingReplace = true,
                    slCancelPending = true,
                    tpCancelPending = true,
                    closeCancelPending = true,
                    pendingClose = true,
                    pendingEntry = true,
                    closeReason = CloseReason.TAKE_PROFIT,
                    cumulativeCloseFillQty = 7,
                    cumulativeSlFillQty = 2,
                    cumulativeTpFillQty = 3,
                    cycleId = "cycle-1",
                    fundingUnknown = true,
                )

            val id = repo.save(rich).id!!
            val stored = repo.findByIdOrNull(id) ?: error("position must exist")

            assertEquals("AAA", stored.ticker)
            assertEquals(PositionDirection.LONG, stored.direction)
            assertEquals(10, stored.quantity)
            assertEquals(0, BigDecimal("100").compareTo(stored.entryPrice))
            assertEquals(0, BigDecimal("101").compareTo(stored.currentPrice))
            assertEquals(0, BigDecimal("102").compareTo(stored.closePrice))
            assertEquals(0, BigDecimal("95").compareTo(stored.stopLoss))
            assertEquals(0, BigDecimal("110").compareTo(stored.takeProfit))
            assertEquals(0, BigDecimal("2.5").compareTo(stored.leverage))
            assertEquals(0, BigDecimal("500").compareTo(stored.goPerContract))
            assertEquals(0, BigDecimal("1000").compareTo(stored.marginUsed))
            assertEquals(0, BigDecimal("80").compareTo(stored.liquidationPrice))
            assertEquals(0, BigDecimal("12.5").compareTo(stored.variationMargin))
            assertEquals(25, stored.stopLossPoints)
            assertEquals(0, BigDecimal("99").compareTo(stored.trailingStopPrice))
            assertEquals(0, BigDecimal("55").compareTo(stored.pnl))
            assertEquals(0, BigDecimal("40").compareTo(stored.realizedPnl))
            assertEquals("A-1", stored.alorOrderId)
            assertEquals("CLOSE-1", stored.closeOrderId)
            assertEquals("SL-1", stored.slOrderId)
            assertEquals("TP-1", stored.tpOrderId)
            assertEquals(0, BigDecimal("95").compareTo(stored.slOrderPrice))
            assertEquals(0, BigDecimal("110").compareTo(stored.tpOrderPrice))
            assertTrue(stored.slPendingReplace)
            assertTrue(stored.tpPendingReplace)
            assertTrue(stored.slCancelPending)
            assertTrue(stored.tpCancelPending)
            assertTrue(stored.closeCancelPending)
            assertTrue(stored.pendingClose)
            assertTrue(stored.pendingEntry)
            assertEquals(CloseReason.TAKE_PROFIT, stored.closeReason)
            assertEquals(7, stored.cumulativeCloseFillQty)
            assertEquals(2, stored.cumulativeSlFillQty)
            assertEquals(3, stored.cumulativeTpFillQty)
            assertEquals("cycle-1", stored.cycleId)
            assertEquals(acc, stored.accountId)
            assertTrue(stored.fundingUnknown)
        }
    }

    @Test
    fun `minimal position falls back to column defaults`() {
        runBlocking {
            val id = repo.save(position()).id!!

            val stored = repo.findByIdOrNull(id) ?: error("position must exist")

            assertNull(stored.currentPrice)
            assertNull(stored.closePrice)
            assertNull(stored.stopLoss)
            assertNull(stored.takeProfit)
            assertNull(stored.leverage)
            assertNull(stored.pnl)
            assertNull(stored.closeReason)
            assertNull(stored.closedAt)
            assertNull(stored.cycleId)
            assertNull(stored.accountId)
            assertEquals(0, BigDecimal.ZERO.compareTo(stored.variationMargin))
            assertEquals(0, BigDecimal.ZERO.compareTo(stored.realizedPnl))
            assertEquals(0, stored.cumulativeSlFillQty)
            assertEquals(0, stored.cumulativeTpFillQty)
            assertEquals(0, stored.cumulativeCloseFillQty)
            assertFalse(stored.slPendingReplace)
            assertFalse(stored.tpPendingReplace)
            assertFalse(stored.slCancelPending)
            assertFalse(stored.tpCancelPending)
            assertFalse(stored.closeCancelPending)
            assertFalse(stored.pendingClose)
            assertFalse(stored.pendingEntry)
            assertFalse(stored.fundingUnknown)
        }
    }

    // ─── негативные переходы ────────────────────────────────────────────

    @Test
    fun `claim and release are no-ops for a closed position`() {
        runBlocking {
            val id = repo.save(position(status = PositionStatus.CLOSED, closedAt = base)).id!!

            assertFalse(repo.claimForClose(id), "закрытую позицию нельзя взять в закрытие")
            assertFalse(repo.releaseCloseClaim(id), "нечего освобождать у закрытой позиции")
            assertFalse(
                repo.transitionToClosed(
                    id,
                    PositionStatus.CLOSED,
                    BigDecimal("101"),
                    CloseReason.STRATEGY_CLOSE,
                    BigDecimal("10"),
                ),
                "повторный переход в CLOSED запрещён",
            )
        }
    }

    @Test
    fun `release keeps the claim while a close order id is known`() {
        runBlocking {
            val id = repo.save(position(pendingClose = true, closeOrderId = "CLOSE-1")).id!!

            assertFalse(
                repo.releaseCloseClaim(id),
                "при известном closeOrderId claim не снимается — реконсилятор доводит состояние",
            )
            assertFalse(repo.claimForClose(id), "повторный claim при pending_close невозможен")
        }
    }

    // ─── фильтры закрытых выборок ───────────────────────────────────────

    @Test
    fun `risk relevant includes open and reconciliation positions per account`() {
        runBlocking {
            val acc = account("RISK")
            repo.save(position("LEGACY"))
            repo.save(position("RECON", status = PositionStatus.RECONCILIATION_REQUIRED))
            repo.save(position("ACC", accountId = acc))
            repo.save(position("DONE", status = PositionStatus.CLOSED, accountId = acc, closedAt = base))

            assertEquals(setOf("LEGACY", "RECON"), repo.findRiskRelevant(null).map { it.ticker }.toSet())
            assertEquals(setOf("ACC"), repo.findRiskRelevant(acc).map { it.ticker }.toSet())
        }
    }

    @Test
    fun `closed finder honours ticker and time filters`() {
        runBlocking {
            repo.save(position("OLD", status = PositionStatus.CLOSED, closedAt = base.minusDays(5)))
            repo.save(position("NEW", status = PositionStatus.CLOSED, closedAt = base))
            repo.save(position("OPEN"))

            assertEquals(setOf("OLD", "NEW"), repo.findClosed().map { it.ticker }.toSet())
            assertEquals(listOf("NEW"), repo.findClosed(ticker = "NEW").map { it.ticker })
            assertEquals(
                listOf("NEW"),
                repo.findClosed(since = base.minusDays(1)).map { it.ticker },
                "старое закрытие вне окна",
            )
            assertEquals(
                listOf("NEW"),
                repo.findClosed(ticker = "NEW", since = base.minusDays(1)).map { it.ticker },
            )
            assertEquals(
                setOf("OLD", "NEW"),
                repo.findClosed(ticker = "  ").map { it.ticker }.toSet(),
                "пробельный тикер игнорируется",
            )
        }
    }
}
