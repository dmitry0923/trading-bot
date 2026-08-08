package com.trading.bot.model.entity

import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * Одно решение в ledger'е Decision-level A/B эксперимента (таблица experiment_decisions).
 *
 * Для каждого цикла пишутся ДВЕ записи — рука CONTROL (текущий пайплайн) и рука
 * VARIANT (экспериментальная). CONTROL исполняется реально (кроме полного shadow),
 * VARIANT всегда paper (is_paper=true, не исполняется). Сравнение исходов происходит
 * при закрытии контрольной позиции ([resultPnl] обеих рук).
 *
 * @param id идентификатор записи
 * @param cycleId идентификатор цикла (= trace_id)
 * @param experimentId идентификатор эксперимента
 * @param arm рука: CONTROL | VARIANT
 * @param ticker тикер
 * @param timeframe таймфрейм
 * @param action решение (BUY/SELL/HOLD)
 * @param targetPrice целевая цена
 * @param quantity объём
 * @param stopLoss стоп-лосс
 * @param takeProfit тейк-профит
 * @param confidence уверенность
 * @param reasoning обоснование
 * @param isPaper true — решение только для наблюдения, не исполняется
 * @param version версия промпта вариантной руки ("shadow-copy" для копии контроля)
 * @param rawOutput сырой JSON решения
 * @param executed фактически исполнено на бирже (CONTROL, не shadow)
 * @param resultPnl фактический (CONTROL) или гипотетический (VARIANT) P&L после закрытия
 * @param closed закрыто ли и подведён ли результат
 * @param decidedAt время принятия решения
 */
data class ExperimentDecision(
    val id: Long? = null,
    val cycleId: String,
    val experimentId: String,
    val arm: String,
    val ticker: String,
    val timeframe: String? = null,
    val action: String,
    val targetPrice: BigDecimal? = null,
    val quantity: Int = 0,
    val stopLoss: BigDecimal? = null,
    val takeProfit: BigDecimal? = null,
    val confidence: Double? = null,
    val reasoning: String? = null,
    val isPaper: Boolean,
    val version: String? = null,
    val rawOutput: String? = null,
    val executed: Boolean = false,
    val resultPnl: BigDecimal? = null,
    val closed: Boolean = false,
    val decidedAt: LocalDateTime = LocalDateTime.now(),
)
