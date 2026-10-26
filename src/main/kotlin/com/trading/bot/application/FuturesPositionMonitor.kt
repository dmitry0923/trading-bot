package com.trading.bot.application

import com.trading.bot.application.risk.FuturesRiskEngine
import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.config.RiskConfig
import com.trading.bot.domain.risk.ExitRules
import com.trading.bot.infrastructure.tracing.TraceContext
import com.trading.bot.model.CloseReason
import com.trading.bot.model.InstrumentType
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.entity.Position
import com.trading.bot.repository.PositionRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import kotlinx.coroutines.withContext
import java.math.BigDecimal

/**
 * Мониторинг открытых фьючерсных позиций на каждом тике.
 *
 * - pendingEntry/pendingClose — не трогаем SL/TP/закрытие, ждём State Reconciliation
 *   ([OrderExecutionEngine.resolveEntryViaOutbox] / [OrderExecutionEngine.reconcilePosition]).
 * - Guardrail ликвидации — самый приоритетный: [FuturesRiskEngine.checkLiquidationDistance]
 *   = CRITICAL → немедленный market close.
 * - Затем SL / TP / trailing ([ExitRules]) и подтягивание trailing-стопа фьючерса
 *   с учётом вариационной маржи ([ExitRules.updateFuturesTrailingStop]).
 *
 * НЕ является Spring-бином: создаётся внутри FuturesTradingBotService из его
 * зависимостей (стейтлесс — все данные в БД).
 */
class FuturesPositionMonitor(
    private val futuresRiskEngine: FuturesRiskEngine,
    private val riskConfig: RiskConfig,
    private val instrumentsConfig: InstrumentsConfig,
    private val positionRepo: PositionRepository,
    private val engine: OrderExecutionEngine,
    private val meterRegistry: MeterRegistry,
) {
    private val logger = KotlinLogging.logger {}

    suspend fun monitor(
        ticker: String,
        price: BigDecimal,
    ) {
        val open = positionRepo.findByStatus(PositionStatus.OPEN).filter { it.ticker == ticker }
        for (pos in open) {
            if (pos.instrumentType != InstrumentType.FUTURES) continue
            // Контекст корутины, а не MDC.put (иначе утечка на поток пула) — скан-8 P2.
            withContext(
                TraceContext.mdcContextNullable(
                    mapOf(
                        TraceContext.TRACE_ID to pos.cycleId,
                        TraceContext.CYCLE_ID to pos.cycleId,
                    ),
                ),
            ) {
                monitorPosition(pos, price)
            }
        }
    }

    private suspend fun monitorPosition(
        pos: Position,
        price: BigDecimal,
    ) {
        if (pos.pendingEntry) {
            engine.resolveEntryViaOutbox(pos)
            return
        }

        // Позиция уже в работе — ждём сверку (защита от double execution).
        if (pos.pendingClose) {
            engine.reconcilePosition(pos)
            return
        }

        pos.currentPrice = price

        // 1. Guardrail ликвидации — самый приоритетный.
        when (futuresRiskEngine.checkLiquidationDistance(pos, price)) {
            FuturesRiskEngine.LiquidationStatus.CRITICAL -> {
                logger.error { "LIQUIDATION_CRITICAL ${pos.ticker} @ $price - immediate market close" }
                engine.closePosition(pos, price, CloseReason.LIQUIDATION_CRITICAL)
                return
            }

            FuturesRiskEngine.LiquidationStatus.WARNING -> {
                logger.warn {
                    "LIQUIDATION_WARNING ${pos.ticker} @ $price - " +
                        "distance < ${riskConfig.minLiquidationDistancePercent}%"
                }
                meterRegistry
                    .counter(
                        "futures.liquidation.warning",
                        Tags.of("ticker", pos.ticker),
                    ).increment()
            }

            FuturesRiskEngine.LiquidationStatus.SAFE -> {}
        }

        // 2. SL / TP / trailing
        // Уровни биржевых заявок проверяем первыми — локально считаем только то,
        // что инструмент гарантированно пробьёт (иначе двойное закрытие).
        if (!ExitRules.exchangeSlCovers(pos) && ExitRules.shouldCloseBySL(pos, price)) {
            engine.closePosition(pos, price, CloseReason.STOP_LOSS)
            return
        }
        if (!ExitRules.exchangeTpCovers(pos) && ExitRules.shouldCloseByTP(pos, price)) {
            engine.closePosition(pos, price, CloseReason.TAKE_PROFIT)
            return
        }
        if (!ExitRules.exchangeSlCovers(pos) && ExitRules.shouldCloseByTrailing(pos, price)) {
            engine.closePosition(pos, price, CloseReason.TRAILING_STOP)
            return
        }

        // 3. Подтягиваем trailing (только в плюс, в самом триггере условия).
        if (riskConfig.trailingStopEnabled) {
            ExitRules.updateFuturesTrailingStop(
                pos,
                price,
                riskConfig.trailingStopPercent,
                instrumentsConfig.pointValue(pos.ticker),
                instrumentsConfig.find(pos.ticker)?.priceStep ?: BigDecimal("0.01"),
            )
        }
        positionRepo.save(pos)
        // Сдвиг SL-порога (trailing) → биржевая заявка перевыставляется
        // (отмена старой + новая по текущему уровню), если он синхронизирован.
        engine.onProtectionLevelsChanged(pos)
    }
}
