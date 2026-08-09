package com.trading.bot.domain.risk

/**
 * Вердикт риск-этапа пайплайна.
 *
 * RiskEngine отвечает ТОЛЬКО на вопрос «можно ли входить» — Да/Нет.
 * Размер позиции и SL/TP вычисляет PositionSizer, параметры заявки собирает
 * OrderBuilder.
 */
sealed interface RiskVerdict {
    data object Allowed : RiskVerdict

    data class Rejected(val reason: String) : RiskVerdict
}
