package com.trading.bot.repository

import com.trading.bot.infrastructure.db.bindOrNull
import com.trading.bot.infrastructure.db.require
import com.trading.bot.model.entity.Investor
import com.trading.bot.model.entity.InvestorAccount
import com.trading.bot.model.entity.InvestorAllocation
import com.trading.bot.model.entity.InvestorTransaction
import io.r2dbc.spi.Row
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

/**
 * R2DBC-репозиторий инвесторов, счетов, транзакций и аллокаций.
 */
@Repository
class InvestorRepository(
    private val databaseClient: DatabaseClient,
) {
    private fun toInvestor(row: Row): Investor =
        Investor(
            id = row.require("id", UUID::class.java),
            name = row.require("name", String::class.java),
            email = row.get("email", String::class.java),
            status = row.require("status", String::class.java),
            createdAt = row.require("created_at", LocalDateTime::class.java),
        )

    private fun toAccount(row: Row): InvestorAccount =
        InvestorAccount(
            id = row.require("id", UUID::class.java),
            investorId = row.require("investor_id", UUID::class.java),
            currency = row.require("currency", String::class.java),
            balance = row.require("balance", BigDecimal::class.java),
            totalDeposited = row.require("total_deposited", BigDecimal::class.java),
            totalWithdrawn = row.require("total_withdrawn", BigDecimal::class.java),
            createdAt = row.require("created_at", LocalDateTime::class.java),
            updatedAt = row.require("updated_at", LocalDateTime::class.java),
        )

    private fun toTransaction(row: Row): InvestorTransaction =
        InvestorTransaction(
            id = row.require("id", UUID::class.java),
            investorId = row.require("investor_id", UUID::class.java),
            accountId = row.require("account_id", UUID::class.java),
            type = row.require("type", String::class.java),
            amount = row.require("amount", BigDecimal::class.java),
            currency = row.require("currency", String::class.java),
            sharesAtTime = row.get("shares_at_time", BigDecimal::class.java),
            equityAtTime = row.get("equity_at_time", BigDecimal::class.java),
            description = row.get("description", String::class.java),
            createdAt = row.require("created_at", LocalDateTime::class.java),
        )

    private fun toAllocation(row: Row): InvestorAllocation =
        InvestorAllocation(
            id = row.require("id", UUID::class.java),
            investorId = row.require("investor_id", UUID::class.java),
            accountId = row.require("account_id", UUID::class.java),
            amount = row.require("amount", BigDecimal::class.java),
            allocatedAt = row.require("allocated_at", LocalDateTime::class.java),
        )

    suspend fun saveInvestor(investor: Investor) {
        databaseClient
            .sql(
                """
                INSERT INTO investors (id, name, email, status, created_at)
                VALUES (:id, :name, :email, :status, :createdAt)
                ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, email = EXCLUDED.email, status = EXCLUDED.status
                """.trimIndent(),
            ).bind("id", investor.id)
            .bind("name", investor.name)
            .bindOrNull("email", investor.email)
            .bind("status", investor.status)
            .bind("createdAt", investor.createdAt)
            .then()
            .awaitSingleOrNull()
    }

    suspend fun saveAccount(account: InvestorAccount) {
        databaseClient
            .sql(
                """
                INSERT INTO investor_accounts
                    (id, investor_id, currency, balance, total_deposited, total_withdrawn, created_at, updated_at)
                VALUES (:id, :investorId, :currency, :balance, :totalDeposited, :totalWithdrawn, :createdAt, :updatedAt)
                ON CONFLICT (id) DO UPDATE SET
                    balance = EXCLUDED.balance,
                    total_deposited = EXCLUDED.total_deposited,
                    total_withdrawn = EXCLUDED.total_withdrawn,
                    updated_at = EXCLUDED.updated_at
                """.trimIndent(),
            ).bind("id", account.id)
            .bind("investorId", account.investorId)
            .bind("currency", account.currency)
            .bind("balance", account.balance)
            .bind("totalDeposited", account.totalDeposited)
            .bind("totalWithdrawn", account.totalWithdrawn)
            .bind("createdAt", account.createdAt)
            .bind("updatedAt", account.updatedAt)
            .then()
            .awaitSingleOrNull()
    }

    suspend fun saveTransaction(transaction: InvestorTransaction) {
        databaseClient
            .sql(
                """
                INSERT INTO investor_transactions
                    (id, investor_id, account_id, type, amount, currency, shares_at_time, equity_at_time, description, created_at)
                VALUES (:id, :investorId, :accountId, :type, :amount, :currency, :sharesAtTime, :equityAtTime, :description, :createdAt)
                """.trimIndent(),
            ).bind("id", transaction.id)
            .bind("investorId", transaction.investorId)
            .bind("accountId", transaction.accountId)
            .bind("type", transaction.type)
            .bind("amount", transaction.amount)
            .bind("currency", transaction.currency)
            .bindOrNull("sharesAtTime", transaction.sharesAtTime)
            .bindOrNull("equityAtTime", transaction.equityAtTime)
            .bindOrNull("description", transaction.description)
            .bind("createdAt", transaction.createdAt)
            .then()
            .awaitSingleOrNull()
    }

    suspend fun saveAllocation(allocation: InvestorAllocation) {
        databaseClient
            .sql(
                """
                INSERT INTO investor_allocations (id, investor_id, account_id, amount, allocated_at)
                VALUES (:id, :investorId, :accountId, :amount, :allocatedAt)
                """.trimIndent(),
            ).bind("id", allocation.id)
            .bind("investorId", allocation.investorId)
            .bind("accountId", allocation.accountId)
            .bind("amount", allocation.amount)
            .bind("allocatedAt", allocation.allocatedAt)
            .then()
            .awaitSingleOrNull()
    }

    suspend fun findInvestorById(id: UUID): Investor? {
        val sql = "SELECT * FROM investors WHERE id = :id"
        return databaseClient
            .sql(sql)
            .bind("id", id)
            .map { row, _ -> toInvestor(row) }
            .one()
            .awaitSingleOrNull()
    }

    suspend fun findAccountByInvestor(investorId: UUID): InvestorAccount? {
        val sql = "SELECT * FROM investor_accounts WHERE investor_id = :investorId LIMIT 1"
        return databaseClient
            .sql(sql)
            .bind("investorId", investorId)
            .map { row, _ -> toAccount(row) }
            .one()
            .awaitSingleOrNull()
    }

    suspend fun findAllInvestors(): List<Investor> =
        databaseClient
            .sql("SELECT * FROM investors ORDER BY created_at DESC")
            .map { row, _ -> toInvestor(row) }
            .all()
            .collectList()
            .awaitSingleOrNull()
            ?: emptyList()

    suspend fun findAllAccounts(): List<InvestorAccount> =
        databaseClient
            .sql("SELECT * FROM investor_accounts ORDER BY created_at DESC")
            .map { row, _ -> toAccount(row) }
            .all()
            .collectList()
            .awaitSingleOrNull()
            ?: emptyList()

    suspend fun findTransactionsByInvestor(investorId: UUID): List<InvestorTransaction> {
        val sql = "SELECT * FROM investor_transactions WHERE investor_id = :investorId ORDER BY created_at DESC"
        return databaseClient
            .sql(sql)
            .bind("investorId", investorId)
            .map { row, _ -> toTransaction(row) }
            .all()
            .collectList()
            .awaitSingleOrNull()
            ?: emptyList()
    }

    suspend fun findAllAllocations(): List<InvestorAllocation> =
        databaseClient
            .sql("SELECT * FROM investor_allocations ORDER BY allocated_at DESC")
            .map { row, _ -> toAllocation(row) }
            .all()
            .collectList()
            .awaitSingleOrNull()
            ?: emptyList()

    /**
     * Полная очистка инвесторских таблиц (используется в тестах).
     */
    suspend fun deleteAllData() {
        databaseClient.sql("DELETE FROM investor_allocations").then().awaitSingleOrNull()
        databaseClient.sql("DELETE FROM investor_transactions").then().awaitSingleOrNull()
        databaseClient.sql("DELETE FROM investor_accounts").then().awaitSingleOrNull()
        databaseClient.sql("DELETE FROM investors").then().awaitSingleOrNull()
    }
}
