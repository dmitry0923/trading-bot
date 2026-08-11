package com.trading.bot.application

import com.trading.bot.application.risk.FuturesRiskEngine
import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.config.RiskConfig
import com.trading.bot.domain.risk.ExitRules
import com.trading.bot.infrastructure.tracing.TraceContext
import com.trading.bot.model.InstrumentType
import com.trading.bot.model.PositionStatus
import com.trading.bot.repository.PositionRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
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
            TraceContext.put(TraceContext.TRACE_ID, pos.cycleId)
            TraceContext.put(TraceContext.CYCLE_ID, pos.cycleId)

            // Позиция ожидает подтверждения входа — SL/TP/закрытие не трогаем,
            // ждём State Reconciliation.
            if (pos.pendingEntry) {
                engine.resolveEntryViaOutbox(pos)
                continue
            }

            // Закрытие уже в полёте — новый ордер НЕ создаём (защита от double execution).
            if (pos.pendingClose) {
                engine.reconcilePosition(pos)
                continue
            }

            pos.currentPrice = price

            // 1. Guardrail ликвидации — самый приоритетный
            when (futuresRiskEngine.checkLiquidationDistance(pos, price)) {
                FuturesRiskEngine.LiquidationStatus.CRITICAL -> {
                    logger.error { "LIQUIDATION_CRITICAL ${pos.ticker} @ $price — immediate market close" }
                    engine.closePosition(pos, price, "LIQUIDATION_CRITICAL")
                    continue
                }

                FuturesRiskEngine.LiquidationStatus.WARNING -> {
                    logger.warn {
                        "LIQUIDATION_WARNING ${pos.ticker} @ $price — " +
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
            // Живая биржевая заявка покрывает уровень — закрывает сама биржа,
            // иначе локальный мониторинг продублирует закрытие (двойной ордер).
            if (!ExitRules.exchangeSlCovers(pos) && ExitRules.shouldCloseBySL(pos, price)) {
                engine.closePosition(pos, price, "STOP_LOSS")
                continue
            }
            if (!ExitRules.exchangeTpCovers(pos) && ExitRules.shouldCloseByTP(pos, price)) {
                engine.closePosition(pos, price, "TAKE_PROFIT")
                continue
            }
            if (!ExitRules.exchangeSlCovers(pos) && ExitRules.shouldCloseByTrailing(pos, price)) {
                engine.closePosition(pos, price, "TRAILING_STOP")
                continue
            }

            // 3. Подтягивание trailing (только в прибыль, с учётом вариационной маржи)
            ExitRules.updateFuturesTrailingStop(
                pos,
                price,
                riskConfig.trailingStopPercent,
                instrumentsConfig.pointValue(pos.ticker),
            )
            positionRepo.save(pos)
            // Сдвиг SL-уровня (trailing) → биржевая защитная заявка перевыставляется
            // (отмена старой + новая на текущем уровне), пока не синхронизированы.
            engine.onProtectionLevelsChanged(pos)
        }
    }
}
