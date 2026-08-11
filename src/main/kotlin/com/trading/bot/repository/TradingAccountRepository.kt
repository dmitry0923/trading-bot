package com.trading.bot.repository

import com.trading.bot.infrastructure.db.bindOrNull
import com.trading.bot.infrastructure.db.require
import com.trading.bot.model.entity.TradingAccount
import io.r2dbc.spi.Row
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * R2DBC-репозиторий торговых аккаунтов (multi-account, roadmap v2.2).
 * Пустая таблица = legacy single-account режим (AlorConfig.portfolio).
 */
@Repository
class TradingAccountRepository(
    private val databaseClient: DatabaseClient,
) {
    private fun toAccount(row: Row): TradingAccount =
        TradingAccount(
            id = row.get("id", Long::class.javaObjectType),
            name = row.require("name", String::class.java),
            alorPortfolio = row.require("alor_portfolio", String::class.java),
            exchange = row.require("exchange", String::class.java),
            enabled = row.require("enabled", Boolean::class.javaObjectType),
            aumRub = row.get("aum_rub", BigDecimal::class.java),
            maxOpenPositions = row.get("max_open_positions", Int::class.javaObjectType),
            maxDailyLossRub = row.get("max_daily_loss_rub", BigDecimal::class.java),
            weight = row.require("weight", Int::class.javaObjectType),
            createdAt = row.require("created_at", LocalDateTime::class.java),
            updatedAt = row.require("updated_at", LocalDateTime::class.java),
        )

    suspend fun findAll(): List<TradingAccount> =
        databaseClient
            .sql("SELECT * FROM trading_accounts ORDER BY id ASC")
            .map { row, _ -> toAccount(row) }
            .all()
            .collectList()
            .awaitSingle()

    suspend fun findEnabled(): List<TradingAccount> {
        val sql = "SELECT * FROM trading_accounts WHERE enabled = TRUE ORDER BY id ASC"
        return databaseClient
            .sql(sql)
            .map { row, _ -> toAccount(row) }
            .all()
            .collectList()
            .awaitSingle()
    }

    suspend fun findById(id: Long): TradingAccount? {
        val sql = "SELECT * FROM trading_accounts WHERE id = :id"
        return databaseClient
            .sql(sql)
            .bind("id", id)
            .map { row, _ -> toAccount(row) }
            .one()
            .awaitSingleOrNull()
    }

    suspend fun save(account: TradingAccount): TradingAccount =
        if (account.id == null) {
            insert(account)
        } else {
            update(account)
            account
        }

    private fun DatabaseClient.GenericExecuteSpec.bindAccount(account: TradingAccount): DatabaseClient.GenericExecuteSpec =
        this
            .bind("name", account.name)
            .bind("alorPortfolio", account.alorPortfolio)
            .bind("exchange", account.exchange)
            .bind("enabled", account.enabled)
            .bindOrNull("aumRub", account.aumRub)
            .bindOrNull("maxOpenPositions", account.maxOpenPositions)
            .bindOrNull("maxDailyLossRub", account.maxDailyLossRub)
            .bind("weight", account.weight)

    private suspend fun insert(account: TradingAccount): TradingAccount {
        val sql =
            """
            INSERT INTO trading_accounts (name, alor_portfolio, exchange, enabled, aum_rub,
                max_open_positions, max_daily_loss_rub, weight, created_at, updated_at)
            VALUES (:name, :alorPortfolio, :exchange, :enabled, :aumRub,
                :maxOpenPositions, :maxDailyLossRub, :weight, :createdAt, :updatedAt)
            RETURNING id
            """.trimIndent()
        val id =
            databaseClient
                .sql(sql)
                .bindAccount(account)
                .bind("createdAt", account.createdAt)
                .bind("updatedAt", account.updatedAt)
                .map { row, _ -> row.get("id", Long::class.javaObjectType)!! }
                .one()
                .awaitSingle()
        return account.copy(id = id)
    }

    private suspend fun update(account: TradingAccount) {
        val sql =
            """
            UPDATE trading_accounts SET
                name = :name, alor_portfolio = :alorPortfolio, exchange = :exchange,
                enabled = :enabled, aum_rub = :aumRub, max_open_positions = :maxOpenPositions,
                max_daily_loss_rub = :maxDailyLossRub, weight = :weight, updated_at = :updatedAt
            WHERE id = :id
            """.trimIndent()
        databaseClient
            .sql(sql)
            .bindAccount(account)
            .bind("updatedAt", account.updatedAt)
            .bind("id", account.id!!)
            .then()
            .awaitSingleOrNull()
    }

    suspend fun deleteById(id: Long) {
        databaseClient
            .sql("DELETE FROM trading_accounts WHERE id = :id")
            .bind("id", id)
            .then()
            .awaitSingleOrNull()
    }
}
