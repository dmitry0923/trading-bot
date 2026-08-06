package com.trading.bot.model

import java.math.BigDecimal
import java.time.Instant

/**
 * Multi-Tier Drawdown Protection — снимок защиты от просадок на момент проверки.
 *
 * Три уровня (все в % от AUM, чтобы лимиты масштабировались при росте/падении капитала):
 * 1. Дневной лимит убытка (закрытые сделки сегодня);
 * 2. Скользящий лимит за 7 дней;
 * 3. Скользящий лимит за 30 дней («смерть от тысячи порезов» — медленная просадка,
 *    при которой дневной лимит не пробивается).
 *
 * Дополнительно: лимит серии убыточных сделок подряд ([consecutiveLosses]),
 * при достижении которого LLM-агент переводится в Shadow/Read-only ([shadowModeActive]).
 */
data class DrawdownStatus(
    /** Текущий капитал (AUM) в рублях: стартовый депозит + реализованный P&L закрытых сделок. */
    val aum: BigDecimal,
    val dailyPnlRub: BigDecimal,
    val dailyLimitRub: BigDecimal,
    val dailyLimitBreached: Boolean,
    val rolling7dPnlRub: BigDecimal,
    val rolling7dLimitRub: BigDecimal,
    val rolling7dBreached: Boolean,
    val rolling30dPnlRub: BigDecimal,
    val rolling30dLimitRub: BigDecimal,
    val rolling30dBreached: Boolean,
    val consecutiveLosses: Int,
    val maxConsecutiveLosses: Int,
    val shadowModeActive: Boolean,
    val shadowModeUntil: Instant?,
    val reasons: List<String>,
    val timestamp: Instant,
) {
    /**
     * Блокирует ли текущее состояние новые входы.
     */
    fun blocking(): Boolean = dailyLimitBreached || rolling7dBreached || rolling30dBreached || shadowModeActive
}
