package com.trading.bot.integration

import com.trading.bot.model.entity.FundingHistoryRecord
import com.trading.bot.repository.FundingHistoryRepository
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.DatabaseClient
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Идемпотентная запись funding_history: повторная загрузка того же
 * (ticker, clearing_date) обязана **обновлять** значения.
 *
 * `ON CONFLICT DO NOTHING` молча оставлял старые данные, поэтому исправленная
 * конвертация (например, множитель lotSize для GLDRUBF/IMOEXF/SBERF/GAZPF)
 * не доезжала до БД — перезагрузка отчитывалась об успехе.
 */
@Tag("integration")
class FundingHistoryRepositoryIntegrationTest : AbstractTestContainerTest() {
    @Autowired
    lateinit var repo: FundingHistoryRepository

    @Autowired
    lateinit var databaseClient: DatabaseClient

    private val ticker = "FIXFUND"

    @BeforeEach
    fun cleanup() {
        runBlocking {
            databaseClient
                .sql("DELETE FROM funding_history WHERE ticker = :ticker")
                .bind("ticker", ticker)
                .then()
                .awaitSingleOrNull()
        }
    }

    @Test
    fun `re-loading the same date with a corrected value updates the row`() {
        val date = LocalDate.parse("2026-09-30")
        runBlocking {
            repo.saveAll(listOf(record(date, raw = "0.006904", value = "6.904000")))
            repo.saveAll(listOf(record(date, raw = "6.904600", value = "6904.600000", source = "MOEX-FIX")))
        }

        val rows = runBlocking { repo.findValuesBetween(ticker, date, date) }

        assertEquals(1, rows.size, "дата должна остаться одной строкой (unique key сохранён)")
        assertEquals(0, BigDecimal("6904.600000").compareTo(rows.getValue(date)))
    }

    @Test
    fun `saveAll reports both inserted and updated rows`() {
        val date = LocalDate.parse("2026-09-29")
        val first = runBlocking { repo.saveAll(listOf(record(date, raw = "0.00256", value = "2.560000"))) }
        val second = runBlocking { repo.saveAll(listOf(record(date, raw = "5.120000", value = "5120.000000"))) }

        assertEquals(1, first)
        assertEquals(1, second, "upsert обновляет строку — она обязана попасть в счётчик записанных")
    }

    private fun record(
        date: LocalDate,
        raw: String,
        value: String,
        source: String = "MOEX",
    ): FundingHistoryRecord =
        FundingHistoryRecord(
            ticker = ticker,
            clearingDate = date,
            rawValue = BigDecimal(raw),
            valueRubPerContract = BigDecimal(value),
            source = source,
        )
}
