package com.trading.bot.application.decision

import com.trading.bot.domain.order.OrderParams
import com.trading.bot.domain.risk.EntryRequest
import com.trading.bot.domain.risk.PositionSizeResult
import com.trading.bot.domain.risk.RiskEngine
import com.trading.bot.domain.signal.Signal
import com.trading.bot.model.InstrumentType
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.StrategyAction
import com.trading.bot.model.entity.Position
import java.math.BigDecimal

/**
 * Режим применения портфельного риск-движка ([com.trading.bot.domain.risk.PortfolioRiskEngine])
 * внутри [DecisionEngine].
 *
 * - ENFORCED — жёсткий: блокировка входа (BLOCK) и уменьшение размера (SCALE).
 * - READ_ONLY — только фиксация метрик/логов, вход не блокируется и не масштабируется.
 */
enum class PortfolioMode {
    ENFORCED,
    READ_ONLY,
}

internal fun Signal.direction(): PositionDirection = if (action == StrategyAction.BUY) PositionDirection.LONG else PositionDirection.SHORT

/**
 * Профиль входа для класса инструментов (акции/фьючерсы).
 *
 * [DecisionEngine] выполняет общий пайплайн входа, различия инструментов
 * инкапсулированы здесь:
 * - построение [EntryRequest] (портфельные данные: депозит/GO);
 * - pre-sizing фильтры (корреляция) и post-sizing фильтры (exposure);
 * - расчёт размера позиции (Kelly для акций, маржа/риск для фьючерсов);
 * - сборка [OrderParams] (SL/TP по проценту или в пунктах, ликвидация);
 * - режим портфельного риска [PortfolioMode];
 * - построение сущности [Position] (инструмент-специфичные поля);
 * - побочные эффекты после открытия ([onOpened]).
 */
interface EntryProfile {
    /** Тип инструментов профиля (для [Position.instrumentType]). */
    val instrumentType: InstrumentType

    /** Префикс метрик входа (bot.* / futures.*). */
    val metricPrefix: String

    /** Риск-этап «Да/Нет» для этого класса инструментов. */
    val riskEngine: RiskEngine

    /** Маршрутизация сигнала на профиль (например, by instruments-config type). */
    fun matches(ticker: String): Boolean

    /** Входные данные для [riskEngine] (портфельные данные инструмента). */
    suspend fun buildEntryRequest(
        signal: Signal,
        entryPrice: BigDecimal,
        openPositions: List<Position>,
    ): EntryRequest

    /**
     * Фильтры ДО сайзинга (корреляция). Возвращает причину отказа или null.
     * Метрику/лог отклонения фиксирует [DecisionEngine].
     */
    suspend fun preSizingChecks(
        ticker: String,
        openPositions: List<Position>,
    ): String?

    /** Расчёт размера позиции. quantity = 0 возможен (акции при Kelly = 0) — финальный
     *  отказ определяет [buildOrderParams] (params.quantity <= 0). */
    suspend fun sizePosition(
        signal: Signal,
        entryPrice: BigDecimal,
        request: EntryRequest,
    ): PositionSizeResult

    /**
     * Фильтры ПОСЛЕ сайзинга (портфельный exposure). Возвращает причину отказа или null.
     * Метрику/лог отклонения фиксирует [DecisionEngine].
     */
    suspend fun postSizingChecks(
        direction: PositionDirection,
        entryPrice: BigDecimal,
        size: PositionSizeResult,
        openPositions: List<Position>,
    ): String?

    /** Сборка параметров заявки из размера и данных входа. */
    fun buildOrderParams(
        ticker: String,
        direction: PositionDirection,
        entryPrice: BigDecimal,
        size: PositionSizeResult,
        request: EntryRequest,
    ): OrderParams

    /** Режим применения портфельного риск-движка. */
    fun portfolioMode(): PortfolioMode = PortfolioMode.ENFORCED

    /** Построение сущности позиции для [DecisionEngine] (аргументы те же, что в
     *  [com.trading.bot.application.OrderExecutionEngine.placeEntryOrder]). */
    fun buildPosition(
        signal: Signal,
        params: OrderParams,
        orderId: String?,
        pending: Boolean,
        fillPrice: BigDecimal,
        qty: Int,
    ): Position

    /** Побочные эффекты после успешного открытия (agent log, дневной P&L reset). */
    suspend fun onOpened(
        signal: Signal,
        opened: Position,
        params: OrderParams,
        size: PositionSizeResult,
    ) = Unit
}
