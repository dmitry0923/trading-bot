package com.trading.bot.application

import com.trading.bot.client.AlorClient
import com.trading.bot.config.LeverageConfig
import com.trading.bot.config.RiskConfig
import com.trading.bot.domain.risk.FuturesRiskEngine
import com.trading.bot.infrastructure.alor.AlorFuturesClient
import com.trading.bot.model.InstrumentType
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.StrategyAction
import com.trading.bot.model.entity.Position
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
 * - Проверки risk-first: stale market data (defense in depth), дневной лимит убытка,
 *   торговые часы, [FuturesRiskEngine.validateEntry].
 * - Фактическое размещение ордера и обработку UNCERTAIN / PARTIAL / full fill
 *   делегирует [OrderExecutionEngine.placeEntryOrder].
 *
 * НЕ является Spring-бином: создаётся внутри FuturesTradingBotService из его
 * зависимостей (стейтлесс — все данные в БД).
 */
class FuturesEntryCoordinator(
    private val futuresRiskEngine: FuturesRiskEngine,
    private val tradingHoursGuard: TradingHoursGuard,
    private val alorClient: AlorClient,
    private val alorFuturesClient: AlorFuturesClient,
    private val marketDataGate: MarketDataGate,
    private val leverageConfig: LeverageConfig,
    private val riskConfig: RiskConfig,
    private val engine: OrderExecutionEngine,
    private val meterRegistry: MeterRegistry,
) {
    private val logger = KotlinLogging.logger {}

    /** Per-ticker mutex входа: сериализует openPosition по тикеру (защита от
     *  гонки двух сигналов на один тикер → двойного ордера). */
    private val entryLocks = ConcurrentHashMap<String, Mutex>()

    suspend fun openPosition(
        ticker: String,
        targetPrice: BigDecimal,
        action: StrategyAction,
        cycleId: String?,
    ) {
        val lock = entryLocks.computeIfAbsent(ticker) { Mutex() }
        lock.withLock {
            doOpenPosition(ticker, targetPrice, action, cycleId)
        }
    }

    private suspend fun doOpenPosition(
        ticker: String,
        targetPrice: BigDecimal,
        action: StrategyAction,
        cycleId: String?,
    ) {
        if (!marketDataGate.isPriceDataFresh(ticker)) {
            logger.warn { "STALE market data — futures entry blocked $ticker (defense in depth)" }
            meterRegistry.counter("futures.entry.rejected", Tags.of("ticker", ticker, "reason", "STALE_DATA")).increment()
            return
        }
        if (futuresRiskEngine.isDailyLossLimitReached()) {
            logger.warn { "Daily loss limit reached — entry blocked $ticker" }
            meterRegistry.counter("risk.entry.rejected", Tags.of("reason", "DAILY_LIMIT")).increment()
            return
        }
        if (!tradingHoursGuard.isTradingAllowed()) {
            logger.info { "Outside trading hours — entry skipped $ticker" }
            meterRegistry.counter("risk.entry.rejected", Tags.of("reason", "OUTSIDE_HOURS")).increment()
            return
        }

        val direction = if (action == StrategyAction.BUY) PositionDirection.LONG else PositionDirection.SHORT
        val entryPrice = alorClient.getLastPrice(ticker) ?: targetPrice
        val currentGo = alorFuturesClient.getFuturesGO(ticker)
        val portfolioMoney = alorFuturesClient.getPortfolioMoney()

        val validation = futuresRiskEngine.validateEntry(ticker, entryPrice, direction, portfolioMoney, currentGo)
        if (!validation.allowed) {
            logger.warn { "Risk engine rejected $ticker: ${validation.reason}" }
            return
        }

        val opened =
            engine.placeEntryOrder(ticker, direction, validation.quantity, entryPrice) { orderId, pending, fillPrice, qty ->
                Position(
                    ticker = ticker,
                    direction = direction,
                    quantity = qty,
                    entryPrice = fillPrice,
                    currentPrice = fillPrice,
                    stopLoss = validation.stopLossPrice,
                    takeProfit = validation.takeProfitPrice,
                    trailingStopPrice = validation.stopLossPrice,
                    instrumentType = InstrumentType.FUTURES,
                    leverage = leverageConfig.effective(),
                    goPerContract = currentGo,
                    marginUsed = validation.marginRequired,
                    liquidationPrice = validation.liquidationPrice,
                    variationMargin = BigDecimal.ZERO,
                    stopLossPoints = riskConfig.defaultStopLossPoints,
                    alorOrderId = orderId,
                    pendingEntry = pending,
                    cycleId = cycleId,
                )
            }
        if (opened != null) {
            logger.info {
                "Opened futures $ticker $direction qty=${opened.quantity} @ ${opened.entryPrice} " +
                    "sl=${validation.stopLossPrice} tp=${validation.takeProfitPrice} " +
                    "margin=${validation.marginRequired} liq=${validation.liquidationPrice}"
            }
        }
    }
}
