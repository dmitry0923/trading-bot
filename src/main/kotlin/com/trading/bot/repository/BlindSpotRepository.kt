package com.trading.bot.repository

import com.trading.bot.infrastructure.db.bindOrNull
import com.trading.bot.infrastructure.db.require
import com.trading.bot.model.BlindSpotEntity
import io.r2dbc.spi.Row
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
class BlindSpotRepository(
    private val databaseClient: DatabaseClient,
) {
    private fun toBlindSpot(row: Row): BlindSpotEntity =
        BlindSpotEntity(
            id = row.get("id", Long::class.javaObjectType),
            ticker = row.require("ticker", String::class.java),
            conditionPattern = row.require("condition_pattern", String::class.java),
            lossRate = row.require("loss_rate", Double::class.javaObjectType),
            occurrenceCount = row.require("occurrence_count", Int::class.javaObjectType),
            recommendation = row.require("recommendation", String::class.java),
            isActive = row.require("is_active", Boolean::class.javaObjectType),
            detectedAt = row.require("detected_at", LocalDateTime::class.java),
            resolvedAt = row.get("resolved_at", LocalDateTime::class.java),
        )

    suspend fun findByIsActiveTrue(): List<BlindSpotEntity> =
        databaseClient
            .sql("SELECT * FROM blind_spots WHERE is_active = true")
            .map { row, _ -> toBlindSpot(row) }
            .all()
            .collectList()
            .awaitSingle()

    suspend fun findByTickerAndIsActiveTrue(ticker: String): List<BlindSpotEntity> {
        val sql = "SELECT * FROM blind_spots WHERE ticker = :ticker AND is_active = true"
        return databaseClient
            .sql(sql)
            .bind("ticker", ticker)
            .map { row, _ -> toBlindSpot(row) }
            .all()
            .collectList()
            .awaitSingle()
    }

    suspend fun save(entity: BlindSpotEntity): BlindSpotEntity =
        if (entity.id == null) {
            insert(entity)
        } else {
            update(entity)
            entity
        }

    private suspend fun insert(entity: BlindSpotEntity): BlindSpotEntity {
        val sql =
            """
            INSERT INTO blind_spots (ticker, condition_pattern, loss_rate, occurrence_count, recommendation, is_active, detected_at, resolved_at)
            VALUES (:ticker, :conditionPattern, :lossRate, :occurrenceCount, :recommendation, :isActive, :detectedAt, :resolvedAt)
            RETURNING id
            """.trimIndent()
        val id =
            databaseClient
                .sql(sql)
                .bind("ticker", entity.ticker)
                .bind("conditionPattern", entity.conditionPattern)
                .bind("lossRate", entity.lossRate)
                .bind("occurrenceCount", entity.occurrenceCount)
                .bind("recommendation", entity.recommendation)
                .bind("isActive", entity.isActive)
                .bind("detectedAt", entity.detectedAt)
                .bindOrNull("resolvedAt", entity.resolvedAt)
                .map { row, _ -> row.get("id", Long::class.javaObjectType)!! }
                .one()
                .awaitSingle()
        return entity.copy(id = id)
    }

    private suspend fun update(entity: BlindSpotEntity) {
        val sql =
            """
            UPDATE blind_spots SET
                ticker = :ticker, condition_pattern = :conditionPattern, loss_rate = :lossRate,
                occurrence_count = :occurrenceCount, recommendation = :recommendation,
                is_active = :isActive, detected_at = :detectedAt, resolved_at = :resolvedAt
            WHERE id = :id
            """.trimIndent()
        databaseClient
            .sql(sql)
            .bind("ticker", entity.ticker)
            .bind("conditionPattern", entity.conditionPattern)
            .bind("lossRate", entity.lossRate)
            .bind("occurrenceCount", entity.occurrenceCount)
            .bind("recommendation", entity.recommendation)
            .bind("isActive", entity.isActive)
            .bind("detectedAt", entity.detectedAt)
            .bindOrNull("resolvedAt", entity.resolvedAt)
            .bind("id", entity.id!!)
            .then()
            .awaitSingleOrNull()
    }

    suspend fun deleteAll() {
        databaseClient.sql("DELETE FROM blind_spots").then().awaitSingleOrNull()
    }
}
