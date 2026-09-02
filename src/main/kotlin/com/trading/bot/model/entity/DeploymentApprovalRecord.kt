package com.trading.bot.model.entity

import java.time.Instant

/**
 * Персистентное одобрение тикера к LIVE-торговле (execution interlock, P1 аудит).
 *
 * Единственный источник истины, который читает исполнительный слой перед выставлением
 * реального ордера: если тикера нет в этой таблице (или статус != LIVE_ALLOWED), вход
 * блокируется (fail-closed). Заполняется ТОЛЬКО из [com.trading.bot.backtest.DeploymentGate]
 * при [com.trading.bot.backtest.DeploymentStatus.LIVE_ALLOWED]; переживает рестарт.
 *
 * R2DBC-репозиторий — [com.trading.bot.repository.DeploymentApprovalRepository];
 * кэш для горячего пути — [com.trading.bot.service.DeploymentApprovalService].
 */
data class DeploymentApprovalRecord(
    val ticker: String,
    val status: String,
    val frozenConfidenceThreshold: Double? = null,
    val paramsHash: String? = null,
    val approvedAt: Instant = Instant.now(),
)
