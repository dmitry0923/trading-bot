package com.trading.bot.domain.risk

import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * Единый риск-снапшот фьючерсного входа (P1-аудит): свободные средства портфеля, ГО
 * кандидата (side-specific) и ГО уже открытых фьючерсных позиций снимаются в ОДИН
 * координатный момент. Все риск-расчёты входа (сайзинг, маржинальный гейт) опираются
 * на один снапшот, а не на разрозненные/устаревшие запросы по времени (T0 ≠ T0+Δ).
 *
 * @param takenAt момент снятия снапшота (локальное время МСК)
 * @param accountId аккаунт кандидата
 * @param portfolioMoney свободные средства портфеля аккаунта
 * @param candidateGo ГО 1 контракта кандидата (сторона = направлению сигнала)
 * @param openFuturesGoPerTicker ГО за 1 контракт открытых futures-позиций (ключ —
 *   тикер; сторона — направлению позиции). Позиция вне карты = данные неполны,
 *   маржинальный гейт трактует это как fail-closed (см.
 *   [com.trading.bot.service.RiskManagementService.freshMarginOfPositions]).
 */
data class FuturesRiskSnapshot(
    val takenAt: LocalDateTime,
    val accountId: Long?,
    val portfolioMoney: BigDecimal,
    val candidateGo: BigDecimal,
    val openFuturesGoPerTicker: Map<String, BigDecimal>,
)
