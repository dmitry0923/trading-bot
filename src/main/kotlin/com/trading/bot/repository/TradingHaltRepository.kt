package com.trading.bot.repository

import com.trading.bot.infrastructure.db.require
import com.trading.bot.model.entity.TradingHaltRecord
import io.r2dbc.spi.Row
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.time.Instant

/**
 * R2DBC-репозиторий последней глобальной остановки торговли (таблица trading_halt).
 *
 * Хранит ОДНУ строку (id = 1): последний halt перезаписывается. Методы suspend —
 * вызываются не на горячем пути входов (кэшируются в [com.trading.bot.service.TradingHaltService]).
 */
@Repository
class TradingHaltRepository(
    private val databaseClient: DatabaseClient,
) {
    private fun toTradingHaltRecord(row: Row): TradingHaltRecord =
        TradingHaltRecord(
            id = row.get("id", Long::class.javaObjectType),
            reason = row.require("reason", String::class.java),
            source = row.require("source", String::class.java),
            detail = row.require("detail", String::class.java),
            haltedAt = row.require("halted_at", Instant::class.java),
        )

    /**
     * Upsert последней остановки (одна строка, id = 1).
     */
    suspend fun save(record: TradingHaltRecord) {
        val sql =
            """
            INSERT INTO trading_halt (id, reason, source, detail, halted_at, updated_at)
            VALUES (1, :reason, :source, :detail, :haltedAt, NOW())
            ON CONFLICT (id) DO UPDATE SET
                reason = EXCLUDED.reason,
                source = EXCLUDED.source,
                detail = EXCLUDED.detail,
                halted_at = EXCLUDED.halted_at,
                updated_at = NOW()
            """.trimIndent()
        databaseClient
            .sql(sql)
            .bind("reason", record.reason)
            .bind("source", record.source)
            .bind("detail", record.detail)
            .bind("haltedAt", record.haltedAt)
            .then()
            .awaitSingleOrNull()
    }

    /**
     * Последняя зафиксированная остановка или null.
     */
    suspend fun latest(): TradingHaltRecord? {
        val sql = "SELECT * FROM trading_halt WHERE id = 1"
        return databaseClient
            .sql(sql)
            .map { row, _ -> toTradingHaltRecord(row) }
            .one()
            .awaitSingleOrNull()
    }

    /**
     * Очищает сохранённую остановку (ручное включение торговли).
     */
    suspend fun deleteAll() {
        databaseClient.sql("DELETE FROM trading_halt WHERE id = 1").then().awaitSingleOrNull()
    }
}
