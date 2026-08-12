package com.trading.bot.repository

import com.trading.bot.infrastructure.db.require
import com.trading.bot.model.entity.MacroSnapshot
import io.r2dbc.spi.Row
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.LocalDateTime

@Repository
class MacroSnapshotRepository(
    private val databaseClient: DatabaseClient,
) {
    private fun toSnapshot(row: Row): MacroSnapshot =
        MacroSnapshot(
            id = row.get("id", Long::class.javaObjectType),
            capturedAt = row.require("captured_at", LocalDateTime::class.java),
            cbrRate = row.require("cbr_rate", BigDecimal::class.java),
            brentPrice = row.require("brent_price", BigDecimal::class.java),
            usdRub = row.require("usd_rub", BigDecimal::class.java),
        )

    suspend fun save(snapshot: MacroSnapshot): MacroSnapshot {
        val sql =
            """
            INSERT INTO macro_snapshots (captured_at, cbr_rate, brent_price, usd_rub)
            VALUES (:capturedAt, :cbrRate, :brentPrice, :usdRub)
            RETURNING id
            """.trimIndent()
        val id =
            databaseClient
                .sql(sql)
                .bind("capturedAt", snapshot.capturedAt)
                .bind("cbrRate", snapshot.cbrRate)
                .bind("brentPrice", snapshot.brentPrice)
                .bind("usdRub", snapshot.usdRub)
                .map { row, _ -> row.get("id", Long::class.javaObjectType)!! }
                .one()
                .awaitSingle()
        return snapshot.copy(id = id)
    }

    /**
     * Снапшоты в окне [from]..[to] (включительно), по возрастанию [capturedAt].
     * Один запрос покрывает всё окно позиций экспорта ML-датасета.
     */
    suspend fun findBetween(
        from: LocalDateTime,
        to: LocalDateTime,
    ): List<MacroSnapshot> {
        val sql =
            "SELECT * FROM macro_snapshots WHERE captured_at BETWEEN :from AND :to ORDER BY captured_at ASC"
        return databaseClient
            .sql(sql)
            .bind("from", from)
            .bind("to", to)
            .map { row, _ -> toSnapshot(row) }
            .all()
            .collectList()
            .awaitSingle()
    }

    suspend fun deleteAll() {
        databaseClient.sql("DELETE FROM macro_snapshots").then().awaitSingleOrNull()
    }
}
