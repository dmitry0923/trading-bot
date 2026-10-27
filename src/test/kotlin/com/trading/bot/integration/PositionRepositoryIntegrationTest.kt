package com.trading.bot.integration

import com.trading.bot.model.CloseReason
import com.trading.bot.model.InstrumentType
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.entity.Position
import com.trading.bot.repository.PositionRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal

@Tag("integration")
class PositionRepositoryIntegrationTest : AbstractTestContainerTest() {
    @Autowired
    lateinit var repo: PositionRepository

    @BeforeEach
    fun cleanup() {
        runBlocking { repo.deleteAll() }
    }

    private fun pos(
        ticker: String = "GAZP",
        direction: PositionDirection = PositionDirection.LONG,
        quantity: Int = 10,
        status: PositionStatus = PositionStatus.OPEN,
        pendingEntry: Boolean = false,
        pendingClose: Boolean = false,
        instrumentType: InstrumentType = InstrumentType.STOCK,
        cycleId: String? = null,
        openedAt: java.time.LocalDateTime = java.time.LocalDateTime.now(),
    ) = Position(
        ticker = ticker,
        direction = direction,
        quantity = quantity,
        entryPrice = BigDecimal("100.00"),
        status = status,
        pendingEntry = pendingEntry,
        pendingClose = pendingClose,
        instrumentType = instrumentType,
        cycleId = cycleId,
        openedAt = openedAt,
    )

    @Test
    fun `findPending returns only pendingEntry and pendingClose positions`() {
        runBlocking {
            repo.save(pos(ticker = "A", pendingEntry = true))
            repo.save(pos(ticker = "B", pendingClose = true))
            repo.save(pos(ticker = "C"))
            repo.save(pos(ticker = "D", pendingEntry = true, pendingClose = true))

            val pending = repo.findPending()
            assertEquals(3, pending.size)
            assertTrue(pending.all { it.pendingEntry || it.pendingClose })
            val tickers = pending.map { it.ticker }.toSet()
            assertTrue("A" in tickers)
            assertTrue("B" in tickers)
            assertTrue("D" in tickers)
            assertTrue("C" !in tickers)
        }
    }

    @Test
    fun `findPending returns empty when no pending positions`() {
        runBlocking {
            repo.save(pos(ticker = "A"))
            repo.save(pos(ticker = "B"))

            val pending = repo.findPending()
            assertTrue(pending.isEmpty())
        }
    }

    @Test
    fun `findPending excludes non-OPEN positions`() {
        runBlocking {
            repo.save(pos(ticker = "A", pendingEntry = true, status = PositionStatus.OPEN))
            repo.save(pos(ticker = "B", pendingEntry = true, status = PositionStatus.CLOSED))

            val pending = repo.findPending()
            assertEquals(1, pending.size)
            assertEquals("A", pending[0].ticker)
        }
    }

    @Test
    fun `findOpenStocks excludes futures`() {
        runBlocking {
            repo.save(pos(ticker = "GAZP", instrumentType = InstrumentType.STOCK))
            repo.save(pos(ticker = "Si", instrumentType = InstrumentType.FUTURES))
            repo.save(pos(ticker = "LKOH", instrumentType = InstrumentType.STOCK))

            val stocks = repo.findOpenStocks()
            assertEquals(2, stocks.size)
            assertTrue(stocks.all { it.instrumentType != InstrumentType.FUTURES })
            val tickers = stocks.map { it.ticker }.toSet()
            assertTrue("GAZP" in tickers)
            assertTrue("LKOH" in tickers)
            assertTrue("Si" !in tickers)
        }
    }

    @Test
    fun `findOpenStocks excludes closed positions`() {
        runBlocking {
            repo.save(pos(ticker = "A", instrumentType = InstrumentType.STOCK, status = PositionStatus.OPEN))
            repo.save(pos(ticker = "B", instrumentType = InstrumentType.STOCK, status = PositionStatus.CLOSED))

            val stocks = repo.findOpenStocks()
            assertEquals(1, stocks.size)
            assertEquals("A", stocks[0].ticker)
        }
    }

    @Test
    fun `findByCycleId returns only positions of the cycle`() {
        val t0 = java.time.LocalDateTime.of(2026, 9, 10, 10, 0, 0, 0)
        runBlocking {
            repo.save(pos(ticker = "A", cycleId = "cycle-1", openedAt = t0))
            repo.save(pos(ticker = "B", cycleId = "cycle-1", openedAt = t0.plusSeconds(1)))
            repo.save(pos(ticker = "C", cycleId = "cycle-2", openedAt = t0))
            repo.save(pos(ticker = "D", cycleId = null, openedAt = t0))

            val result = repo.findByCycleId("cycle-1")

            assertEquals(2, result.size)
            assertTrue(result.all { it.cycleId == "cycle-1" })
            assertEquals(listOf("A", "B"), result.map { it.ticker })
        }
    }

    @Test
    fun `stale save cannot reopen a closed position`() {
        runBlocking {
            val saved = repo.save(pos(ticker = "STALE"))
            val stale = repo.findById(saved.id!!)!!

            assertTrue(
                repo.transitionToClosed(saved.id, PositionStatus.CLOSED, BigDecimal("101"), CloseReason.TAKE_PROFIT, BigDecimal("10"), 1),
            )

            // Монитор/менеджер защиты мог прочитать позицию ДО закрытия и сохранить
            // её после: без terminal-guard строка «воскресала» (status=OPEN,
            // closed_at=NULL, слот входа уже освобождён) — скан-1 P1-4.
            stale.pendingClose = true
            repo.save(stale)

            val fresh = repo.findById(saved.id)!!
            assertEquals(PositionStatus.CLOSED, fresh.status, "закрытая позиция не должна переоткрываться")
            assertTrue(fresh.closedAt != null, "closed_at не должен обнуляться")
            assertEquals(false, fresh.pendingClose, "pendingClose закрытой позиции не восстанавливается")
        }
    }

    @Test
    fun `pending entry quantity can grow to the cumulative fill`() {
        runBlocking {
            // Частичный вход: сначала сохранён объём 1, сверка догоняет до 3.
            // Инвариант «только убывает» относится к подтверждённой позиции, не к pending_entry.
            val saved = repo.save(pos(ticker = "GROW", quantity = 1, pendingEntry = true))
            val filled = repo.findById(saved.id!!)!!
            filled.quantity = 3
            filled.pendingEntry = false
            repo.save(filled)

            assertEquals(3, repo.findById(saved.id)!!.quantity, "объём входа догоняется до фактического фила")
        }
    }

    @Test
    fun `pending entry quantity can shrink to the actual partial fill`() {
        runBlocking {
            // Заявка на 3, исполнилась на 1: пока строка pending_entry, объём обязан
            // УМЕНЬШАТЬСЯ, иначе БД покажет 3 против 1 на бирже → QTY_MISMATCH + halt.
            val saved = repo.save(pos(ticker = "SHRINK", quantity = 3, pendingEntry = true))
            val filled = repo.findById(saved.id!!)!!
            filled.quantity = 1
            filled.pendingEntry = true
            repo.save(filled)

            assertEquals(1, repo.findById(saved.id)!!.quantity, "частичный вход уменьшает объём")
        }
    }

    @Test
    fun `stale save cannot inflate the quantity of an open position`() {
        runBlocking {
            // Монитор цены держит detached-копию и сохраняет её после того, как
            // частичный фил защитной заявки уже уменьшил объём в БД. Полнострочный
            // UPDATE не должен возвращать старый объём: иначе следующее закрытие
            // закрывает больше, чем открыто (скан-4 residual).
            val saved = repo.save(pos(ticker = "QTY", quantity = 10))
            val stale = repo.findById(saved.id!!)!!
            val fresh = repo.findById(saved.id)!!
            fresh.quantity = 6
            repo.save(fresh)

            stale.quantity = 10
            repo.save(stale)

            assertEquals(6, repo.findById(saved.id)!!.quantity, "объём позиции монотонно убывает")
        }
    }

    @Test
    fun `resolveReconciliation reopens or closes a stuck position and is idempotent`() {
        runBlocking {
            val stuck =
                repo.save(
                    pos(ticker = "Si", status = PositionStatus.RECONCILIATION_REQUIRED, pendingClose = true),
                )

            assertTrue(repo.resolveReconciliation(stuck.id!!, PositionStatus.OPEN))
            val reopened = repo.findById(stuck.id)
            assertEquals(PositionStatus.OPEN, reopened.status)
            assertFalse(reopened.pendingClose, "флаги ожидания сбрасываются при возврате в учёт")

            // Повтор по уже разрешённой строке — no-op: статус не перетирается.
            assertFalse(repo.resolveReconciliation(stuck.id, PositionStatus.CLOSED))

            val stuck2 = repo.save(pos(ticker = "Si", status = PositionStatus.RECONCILIATION_REQUIRED))
            assertTrue(repo.resolveReconciliation(stuck2.id!!, PositionStatus.CLOSED, CloseReason.RECONCILE_PHANTOM))
            val closed = repo.findById(stuck2.id)
            assertEquals(PositionStatus.CLOSED, closed.status)
            assertEquals(CloseReason.RECONCILE_PHANTOM, closed.closeReason)
            assertNotNull(closed.closedAt, "ручное закрытие фиксирует время закрытия")
        }
    }

    @Test
    fun `resolveReconciliation rejects any target except OPEN or CLOSED`() {
        runBlocking {
            val stuckId = repo.save(pos(status = PositionStatus.RECONCILIATION_REQUIRED)).id!!
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { repo.resolveReconciliation(stuckId, PositionStatus.TAKE_PROFIT) }
            }
            assertEquals(PositionStatus.RECONCILIATION_REQUIRED, repo.findById(stuckId).status)
        }
    }

    @Test
    fun `findRiskRelevant returns open and reconciliation-required positions only`() {
        runBlocking {
            repo.save(pos(ticker = "A"))
            repo.save(pos(ticker = "B", status = PositionStatus.RECONCILIATION_REQUIRED))
            repo.save(pos(ticker = "C", status = PositionStatus.CLOSED))

            val relevant = repo.findRiskRelevant(null)

            assertEquals(setOf("A", "B"), relevant.map { it.ticker }.toSet())
        }
    }

    @Test
    fun `reserveEntry is blocked by a reconciliation-required position of the same ticker`() {
        runBlocking {
            repo.save(pos(ticker = "Si", status = PositionStatus.RECONCILIATION_REQUIRED))

            // Состояние на бирже неизвестно — второй вход по тому же тикеру запрещён
            // (fail-closed), иначе возможна двойная экспозиция (скан-10 P2).
            assertNull(repo.reserveEntry("Si", PositionDirection.LONG, null))
        }
    }
}
