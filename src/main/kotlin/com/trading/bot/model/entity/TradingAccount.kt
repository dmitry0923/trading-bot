package com.trading.bot.model.entity

import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * Торговый аккаунт (портфель Alor) для multi-account режима (roadmap v2.2).
 *
 * Позволяет вести несколько Alor-портфелей через общий конвейер бота с
 * персональными лимитами:
 * - [aumRub] — переопределение депозита (при null берётся реальный баланс Alor);
 * - [maxOpenPositions] — лимит открытых позиций (при null — глобальный из RiskConfig);
 * - [maxDailyLossRub] — персональный дневной лимит убытка (при null — % AUM).
 *
 * Пустая таблица `trading_accounts` = legacy single-account режим: используется
 * [com.trading.bot.config.AlorConfig.portfolio], позиции без accountId.
 */
data class TradingAccount(
    val id: Long? = null,
    var name: String,
    var alorPortfolio: String,
    var exchange: String = "MOEX",
    var enabled: Boolean = true,
    var aumRub: BigDecimal? = null,
    var maxOpenPositions: Int? = null,
    var maxDailyLossRub: BigDecimal? = null,
    var weight: Int = 1,
    var createdAt: LocalDateTime = LocalDateTime.now(),
    var updatedAt: LocalDateTime = LocalDateTime.now(),
)
