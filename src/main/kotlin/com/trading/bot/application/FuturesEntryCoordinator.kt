package com.trading.bot.application

import com.trading.bot.client.AlorClient
import com.trading.bot.config.LeverageConfig
import com.trading.bot.config.RiskConfig
import com.trading.bot.application.risk.FuturesPositionSizer
import com.trading.bot.application.risk.FuturesRiskEngine
import com.trading.bot.domain.risk.EntryRequest
import com.trading.bot.domain.risk.RiskVerdict
import com.trading.bot.domain.signal.Signal
import com.trading.bot.infrastructure.alor.AlorFuturesClient
import com.trading.bot.model.InstrumentType
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.StrategyAction
import com.trading.bot.model.entity.Position
import com.trading.bot.repository.PositionRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

/**
 * Оркестратор входа во фьючерсную позицию (Si).
 *
 * - Сериализует вход по тикеру (per-ticker mutex — защита от гонки двух сигналов
 *   на один тикер → двойного ордера).
 * - Этапы входа: [FuturesRiskEngine.canEnter] (Да/Нет) →
 *   [FuturesPositionSizer.calculateContracts] (размер) →
 *   [OrderBuilder.buildFuturesOrderParams] (SL/TP/маржа/ликвидация).
 * - Фактическое размещение ордера и обработку UNCERTAIN / PARTIAL / full fill
 *   делегирует [OrderExecutionEngine.placeEntryOrder].
 *
 * НЕ является Spring-бином: создаётся внутри FuturesTradingBotService из его
 * зависимостей (стейтлесс — все данные в БД).
 */
class FuturesEntryCoordinator(
    private val futuresRiskEngine: FuturesRiskEngine,
    private val futuresPositionSizer: FuturesPositionSizer,
    private val orderBuilder: OrderBuilder,
    private val tradingHoursGuard: TradingHoursGuard,
    private val alorClient: AlorClient,
    private val alorFuturesClient: AlorFuturesClient,
    private val marketDataGate: MarketDataGate,
    private val leverageConfig: LeverageConfig,
    private val riskConfig: RiskConfig,
    private val positionRepo: PositionRepository,
    private val engine: OrderExecutionEngine,
    private val meterRegistry: MeterRegistry,
) {
    private val logger = KotlinLogging.logger {}

    /** Per-ticker mutex входа: сериализует openPosition по тикеру (защита от
     *  гонки двух сигналов на один тикер → двойного ордера). */
    private val entryLocks = ConcurrentHashMap<String, Mutex>()

    suspend fun openPosition(signal: Signal) {
        val lock = entryLocks.computeIfAbsent(signal.ticker) { Mutex() }
        lock.withLock {
            doOpenPosition(signal)
        }
    }

    private suspend fun doOpenPosition(signal: Signal) {
        val ticker = signal.ticker
        if (!marketDataGate.isPriceDataFresh(ticker)) {
            logger.warn { "STALE market data — futures entry blocked $ticker (defense in depth)" }
            meterRegistry.counter("futures.entry.rejected", Tags.of("ticker", ticker, "reason", "STALE_DATA")).increment()
            return
        }
        if (!tradingHoursGuard.isTradingAllowed()) {
            logger.info { "Outside trading hours — entry skipped $ticker" }
            meterRegistry.counter("risk.entry.rejected", Tags.of("reason", "OUTSIDE_HOURS")).increment()
            return
        }

        val direction = if (signal.action == StrategyAction.BUY) PositionDirection.LONG else PositionDirection.SHORT
        val entryPrice = alorClient.getLastPrice(ticker) ?: signal.targetPrice
        val currentGo = alorFuturesClient.getFuturesGO(ticker)
        val portfolioMoney = alorFuturesClient.getPortfolioMoney()
        val openPositions = positionRepo.findByStatus(PositionStatus.OPEN)

        val verdict =
            futuresRiskEngine.canEnter(
                EntryRequest(
                    ticker = ticker,
                    action = signal.action,
                    entryPrice = entryPrice,
                    direction = direction,
                    portfolioMoney = portfolioMoney,
                    currentGo = currentGo,
                    openPositions = openPositions,
                ),
            )
        if (verdict is RiskVerdict.Rejected) {
            logger.warn { "Risk engine rejected $ticker: ${verdict.reason}" }
            return
        }

        val size =
            futuresPositionSizer.calculateContracts(
                ticker,
                portfolioMoney,
                riskConfig.defaultStopLossPoints,
                currentGo,
                entryPrice,
                direction,
            )
        if (size.quantity == 0) {
            logger.warn { "Position sizer rejected $ticker: ${size.reason}" }
            meterRegistry.counter("futures.entry.rejected", Tags.of("ticker", ticker, "reason", size.reason ?: "ZERO_SIZE")).increment()
            return
        }

        val params =
            orderBuilder.buildFuturesOrderParams(
                ticker = ticker,
                direction = direction,
                entryPrice = entryPrice,
                currentGo = currentGo,
                size = size,
                leverage = leverageConfig.effective(),
            )
        if (params.quantity <= 0) {
            logger.warn { "Order builder produced zero quantity for $ticker" }
            return
        }

        val opened =
            engine.placeEntryOrder(ticker, direction, params.quantity, entryPrice) { orderId, pending, fillPrice, qty ->
                Position(
                    ticker = ticker,
                    direction = direction,
                    quantity = qty,
                    entryPrice = fillPrice,
                    currentPrice = fillPrice,
                    stopLoss = params.stopLossPrice,
                    takeProfit = params.takeProfitPrice,
                    trailingStopPrice = params.trailingStopPrice,
                    instrumentType = InstrumentType.FUTURES,
                    leverage = params.leverage ?: leverageConfig.effective(),
                    goPerContract = params.goPerContract,
                    marginUsed = params.marginRequired,
                    liquidationPrice = params.liquidationPrice,
                    variationMargin = BigDecimal.ZERO,
                    stopLossPoints = params.stopLossPoints,
                    alorOrderId = orderId,
                    pendingEntry = pending,
                    cycleId = signal.cycleId,
                )
            }
        if (opened != null) {
            orderBuilder.recordStrategyExecution(signal, params)
            logger.info {
                "Opened futures $ticker $direction qty=${opened.quantity} @ ${opened.entryPrice} " +
                    "sl=${params.stopLossPrice} tp=${params.takeProfitPrice} " +
                    "margin=${params.marginRequired} liq=${params.liquidationPrice}"
            }
        }
    }
}
