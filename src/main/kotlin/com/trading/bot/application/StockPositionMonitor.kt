package com.trading.bot.application

import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.config.RiskConfig
import com.trading.bot.domain.risk.ExitRules
import com.trading.bot.infrastructure.tracing.TraceContext
import com.trading.bot.model.CloseReason
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.StrategyAction
import com.trading.bot.model.entity.Position
import com.trading.bot.model.entity.Strategy
import com.trading.bot.repository.PositionRepository
import com.trading.bot.service.ReactiveRedisCacheService
import com.trading.bot.service.TradeEventService
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Мониторинг открытых позиций акций/валют на каждом тике.
 *
 * - SL/TP/trailing/strategy-close — локальный мониторинг (если биржевая защитная
 *   заявка не покрывает уровень).
 * - Подтягивание trailing-стопа по текущей цене.
 * - Обновление SL/TP стратегией из Redis-кэша.
 *
 * Вынесен из [com.trading.bot.service.TradingBotService] для устранения God Object.
 * НЕ является Spring-бином: создаётся внутри TradingBotService из его зависимостей
 * (стейтлесс — все данные в БД).
 */
class StockPositionMonitor(
    private val positionRepo: PositionRepository,
    private val engine: OrderExecutionEngine,
    private val redis: ReactiveRedisCacheService,
    private val riskConfig: RiskConfig,
    private val instrumentsConfig: InstrumentsConfig,
    private val tradeEventService: TradeEventService,
    private val meterRegistry: MeterRegistry,
) {
    private val logger = KotlinLogging.logger {}
    private val positionPnlGauges = ConcurrentHashMap<String, AtomicReference<Double>>()

    suspend fun monitor(event: com.trading.bot.event.PriceChangedEvent) {
        val handlerStart = System.nanoTime()
        try {
            val open =
                positionRepo
                    .findByStatusAndTicker(PositionStatus.OPEN, event.ticker)
            val strategy = redis.getStrategy(event.ticker)
            open.forEach { pos ->
                if (pos.instrumentType != com.trading.bot.model.InstrumentType.FUTURES) {
                    monitorPosition(pos, event.price, strategy)
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Stock monitor error ${event.ticker}" }
            meterRegistry.counter("bot.monitor.error", Tags.of("ticker", event.ticker)).increment()
        } finally {
            meterRegistry
                .timer("bot.latency", Tags.of("ticker", event.ticker))
                .record(System.nanoTime() - handlerStart, java.util.concurrent.TimeUnit.NANOSECONDS)
        }
    }

    private suspend fun monitorPosition(
        pos: Position,
        price: BigDecimal,
        strategy: Strategy?,
    ) {
        TraceContext.put(TraceContext.TRACE_ID, pos.cycleId)
        TraceContext.put(TraceContext.CYCLE_ID, pos.cycleId)
        if (pos.pendingEntry || pos.pendingClose) return
        pos.currentPrice = price
        val pnl = calculatePnl(pos, price)
        pos.pnl = pnl
        updatePositionPnlGauge(pos.ticker, pnl.toDouble())

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

        val prevTrailing = pos.trailingStopPrice
        if (riskConfig.trailingStopEnabled) {
            val priceStep = instrumentsConfig.find(pos.ticker)?.priceStep ?: BigDecimal("0.01")
            ExitRules.updateTrailingStop(pos, price, riskConfig.trailingStopPercent, priceStep)
        }
        val trailingChanged = pos.trailingStopPrice != prevTrailing

        var slUpdated = false
        var tpUpdated = false
        strategy?.let { strat ->
            if (strat.action == StrategyAction.CLOSE) {
                engine.closePosition(pos, price, CloseReason.STRATEGY_CLOSE)
                return
            }
            strat.stopLoss?.let { newSL ->
                val shouldUpd =
                    when (pos.direction) {
                        PositionDirection.LONG -> pos.stopLoss?.let { newSL > it } ?: true
                        PositionDirection.SHORT -> pos.stopLoss?.let { newSL < it } ?: true
                    }
                if (shouldUpd) {
                    pos.stopLoss = newSL
                    logger.info { "SL updated ${pos.ticker} -> $newSL" }
                    slUpdated = true
                }
            }
            strat.takeProfit?.let { newTP ->
                val shouldUpd =
                    when (pos.direction) {
                        PositionDirection.LONG -> pos.takeProfit?.let { newTP > it } ?: true
                        PositionDirection.SHORT -> pos.takeProfit?.let { newTP < it } ?: true
                    }
                if (shouldUpd) {
                    pos.takeProfit = newTP
                    logger.info { "TP updated ${pos.ticker} -> $newTP" }
                    tpUpdated = true
                }
            }
        }
        if (slUpdated || tpUpdated || trailingChanged) {
            positionRepo.save(pos)
            tradeEventService.recordPositionUpdated(pos)
            engine.onProtectionLevelsChanged(pos)
        }
    }

    private fun calculatePnl(
        pos: Position,
        price: BigDecimal,
    ): BigDecimal {
        val lotSize = instrumentsConfig.find(pos.ticker)?.lotSize ?: 1
        val multiplier = BigDecimal(pos.quantity * lotSize)
        return when (pos.direction) {
            PositionDirection.LONG -> price.subtract(pos.entryPrice).multiply(multiplier)
            PositionDirection.SHORT -> pos.entryPrice.subtract(price).multiply(multiplier)
        }
    }

    private fun updatePositionPnlGauge(
        ticker: String,
        pnl: Double,
    ) {
        positionPnlGauges
            .computeIfAbsent(ticker) { t ->
                val ref = AtomicReference(0.0)
                meterRegistry.gauge("position.pnl", Tags.of("ticker", t), ref) { it.get() }
                ref
            }.set(pnl)
    }
}
