package com.trading.bot.model.entity

import com.trading.bot.infrastructure.UuidV7
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

/**
 * Инвестор робота: пассивный участник, вносящий капитал.
 * Статистика бота (закрытые сделки) используется для расчёта доли и прогноза доходности.
 *
 * Все идентификаторы — UUIDv7 ([com.trading.bot.infrastructure.UuidV7]).
 */
data class Investor(
    val id: UUID = UuidV7.uuid(),
    val name: String,
    val email: String? = null,
    val status: String = "ACTIVE",
    val createdAt: LocalDateTime = LocalDateTime.now(),
)

enum class InvestorTransactionType {
    DEPOSIT,
    WITHDRAWAL,
    CLEARING,
    FEE,
}

data class InvestorAccount(
    val id: UUID = UuidV7.uuid(),
    val investorId: UUID,
    val currency: String = "RUB",
    val balance: BigDecimal = BigDecimal.ZERO,
    val totalDeposited: BigDecimal = BigDecimal.ZERO,
    val totalWithdrawn: BigDecimal = BigDecimal.ZERO,
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val updatedAt: LocalDateTime = LocalDateTime.now(),
)

data class InvestorTransaction(
    val id: UUID = UuidV7.uuid(),
    val investorId: UUID,
    val accountId: UUID,
    val type: String,
    val amount: BigDecimal,
    val currency: String = "RUB",
    val sharesAtTime: BigDecimal? = null,
    val equityAtTime: BigDecimal? = null,
    val description: String? = null,
    val createdAt: LocalDateTime = LocalDateTime.now(),
)

data class InvestorAllocation(
    val id: UUID = UuidV7.uuid(),
    val investorId: UUID,
    val accountId: UUID,
    val amount: BigDecimal,
    val allocatedAt: LocalDateTime = LocalDateTime.now(),
)
