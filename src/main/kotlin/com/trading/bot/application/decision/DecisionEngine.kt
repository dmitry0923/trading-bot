package com.trading.bot.application.decision

import com.trading.bot.application.MarketDataGate
import com.trading.bot.application.OrderBuilder
import com.trading.bot.client.AlorClient
import com.trading.bot.domain.risk.PortfolioRiskEngine
import com.trading.bot.domain.risk.PortfolioRiskRequest
import com.trading.bot.domain.risk.RiskVerdict
import com.trading.bot.domain.signal.Signal
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.StrategyAction
import com.trading.bot.repository.PositionRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.ConcurrentHashMap

/**
 * Единый оркестратор входа в позицию для акций и фьючерсов.
 *
 * Объединяет прежние TradingBotService.doOpenPosition и FuturesEntryCoordinator:
 * различия инструментов инкапсулированы в [EntryProfile], здесь — общий пайплайн:
 *
 *   1. Per-ticker mutex (сериализация сигналов на один тикер → защита от двойного ордера).
 *   2. [MarketDataGate] (defense in depth: свежие рыночные данные).
 *   3. Цена входа: [AlorClient.getLastPrice] ?: signal.targetPrice.
 *   4. [EntryProfile.riskEngine].canEnter (Да/Нет).
 *   5. [EntryProfile.preSizingChecks] (корреляционные фильтры акций).
 *   6. [EntryProfile.sizePosition] (Kelly / маржа-риск).
 *   7. [EntryProfile.postSizingChecks] (лимиты Gross/Net Exposure).
 *   8. [EntryProfile.buildOrderParams] (SL/TP/маржа/ликвидация).
 *   9. [PortfolioRiskEngine] по [EntryProfile.portfolioMode]:
 *      ENFORCED — BLOCK/SCALE, READ_ONLY — только метрики/логи.
 *   10. [OrderBuilder.recordStrategyExecution] + [ExecutionGateway.placeEntryOrder].
 *   11. [EntryProfile.onOpened] (дневной P&L reset + agent log для акций).
 *
 * НЕ зависит от исполнения: размещение ордера приходит через [ExecutionGateway]
 * (обёртка над OrderExecutionEngine) — тесты используют фейки.
 */
@Component
class DecisionEngine(
    private val marketDataGate: MarketDataGate,
    private val alorClient: AlorClient,
    private val orderBuilder: OrderBuilder,
    private val portfolioRiskEngine: PortfolioRiskEngine,
    private val positionRepo: PositionRepository,
    private val meterRegistry: MeterRegistry,
    private val profiles: List<EntryProfile>,
) {
    private val logger = KotlinLogging.logger {}

    /** Per-ticker mutex входа: сериализует openPosition по тикеру (защита от
     *  гонки двух сигналов на один тикер → двойного ордера). */
    private val entryLocks = ConcurrentHashMap<String, Mutex>()

    /**
     * Вход по стратегическому сигналу (BUY/SELL). Тикер маршрутизируется на
     * профиль по [EntryProfile.matches]; размещение ордера делегируется [gateway].
     */
    suspend fun openPosition(
        signal: Signal,
        gateway: ExecutionGateway,
    ) {
        if (signal.action != StrategyAction.BUY && signal.action != StrategyAction.SELL) return
        val profile =
            profiles.firstOrNull { it.matches(signal.ticker) }
                ?: run {
                    logger.warn { "No entry profile for ticker ${signal.ticker}; entry skipped" }
                    return
                }
        val lock = entryLocks.computeIfAbsent(signal.ticker) { Mutex() }
        lock.withLock {
            doOpenPosition(signal, profile, gateway)
        }
    }

    private suspend fun doOpenPosition(
        signal: Signal,
        profile: EntryProfile,
        gateway: ExecutionGateway,
    ) {
        val ticker = signal.ticker
        if (!marketDataGate.isPriceDataFresh(ticker)) {
            logger.warn { "STALE market data — entry blocked $ticker (defense in depth)" }
            meterRegistry
                .counter("${profile.metricPrefix}.entry.rejected", Tags.of("ticker", ticker, "reason", "STALE_DATA"))
                .increment()
            return
        }

        val direction = if (signal.action == StrategyAction.BUY) PositionDirection.LONG else PositionDirection.SHORT
        val entryPrice = alorClient.getLastPrice(ticker) ?: signal.targetPrice
        val openPositions = positionRepo.findByStatus(PositionStatus.OPEN)

        // Риск-этап: Да/Нет (дневной лимит, drawdown, волатильность, дубли,
        // лимиты позиций/секторов, ATR%, STRESS, валидность входных данных).
        val request = profile.buildEntryRequest(signal, entryPrice, openPositions)
        when (val verdict = profile.riskEngine.canEnter(request)) {
            is RiskVerdict.Rejected -> {
                logger.warn { "Risk engine rejected $ticker: ${verdict.reason}" }
                meterRegistry
                    .counter("${profile.metricPrefix}.risk.reject", Tags.of("ticker", ticker, "reason", verdict.reason))
                    .increment()
                return
            }

            RiskVerdict.Allowed -> {}
        }

        profile.preSizingChecks(ticker, openPositions)?.let { reason ->
            logger.warn { "Pre-sizing filter reject $ticker: $reason" }
            meterRegistry
                .counter("${profile.metricPrefix}.risk.reject", Tags.of("ticker", ticker, "reason", reason))
                .increment()
            return
        }

        // Сайзинг (Kelly для акций, маржа/риск для фьючерсов).
        val size = profile.sizePosition(signal, entryPrice, request, openPositions)

        profile.postSizingChecks(ticker, direction, entryPrice, size, openPositions)?.let { reason ->
            logger.warn { "Post-sizing filter reject $ticker: $reason" }
            meterRegistry
                .counter("${profile.metricPrefix}.risk.reject", Tags.of("ticker", ticker, "reason", reason))
                .increment()
            return
        }

        var params = profile.buildOrderParams(ticker, direction, entryPrice, size, request)
        if (params.quantity <= 0) {
            logger.warn { "Order builder produced zero quantity for $ticker" }
            return
        }

        // Портфельный риск (агрегат): VaR95 / эффективное число ставок / направленная
        // концентрация. ENFORCED — блок/уменьшение размера; READ_ONLY — только метрики.
        val portfolioNotional = entryPrice.multiply(BigDecimal(params.quantity))
        val portfolioReport =
            portfolioRiskEngine.evaluate(
                PortfolioRiskRequest(
                    candidateTicker = ticker,
                    candidateDirection = direction,
                    candidateNotionalRub = portfolioNotional,
                    openPositions = openPositions,
                    aum = request.portfolioMoney,
                ),
            )
        when (profile.portfolioMode()) {
            PortfolioMode.ENFORCED -> {
                if (!portfolioReport.allowed) {
                    logger.warn { "Portfolio risk reject $ticker: ${portfolioReport.reasons.joinToString("|")}" }
                    meterRegistry
                        .counter(
                            "${profile.metricPrefix}.risk.reject",
                            Tags.of("ticker", ticker, "reason", portfolioReport.reasons.joinToString("|")),
                        ).increment()
                    return
                }
                if (portfolioReport.scaleDownFactor < BigDecimal.ONE) {
                    val scaledQty =
                        BigDecimal(params.quantity)
                            .multiply(portfolioReport.scaleDownFactor)
                            .setScale(0, RoundingMode.DOWN)
                            .toInt()
                            .coerceAtLeast(1)
                    if (scaledQty != params.quantity) {
                        val scaledSize = size.copy(quantity = scaledQty)
                        params = profile.buildOrderParams(ticker, direction, entryPrice, scaledSize, request)
                        meterRegistry.counter("${profile.metricPrefix}.portfolio.scaled", Tags.of("ticker", ticker)).increment()
                        logger.info {
                            "Portfolio risk scale $ticker: qty ${params.quantity} (factor=${portfolioReport.scaleDownFactor})"
                        }
                    }
                }
            }

            PortfolioMode.READ_ONLY -> {
                if (!portfolioReport.allowed) {
                    meterRegistry
                        .counter(
                            "futures.portfolio.readonly",
                            Tags.of("reasons", portfolioReport.reasons.joinToString("|")),
                        ).increment()
                    logger.warn {
                        "Portfolio risk (read-only) $ticker: ${portfolioReport.reasons.joinToString("|")} " +
                            "eff=${portfolioReport.effectivePositions} var95=${portfolioReport.var95Rub}"
                    }
                } else if (portfolioReport.scaleDownFactor < BigDecimal.ONE) {
                    logger.info {
                        "Portfolio risk (read-only) $ticker: scale ${portfolioReport.scaleDownFactor} " +
                            "eff=${portfolioReport.effectivePositions}"
                    }
                }
            }
        }

        val opened =
            gateway.placeEntryOrder(ticker, direction, params.quantity, entryPrice) { orderId, pending, fillPrice, qty ->
                profile.buildPosition(signal, params, orderId, pending, fillPrice, qty)
            }
        if (opened != null) {
            orderBuilder.recordStrategyExecution(signal, params)
            profile.onOpened(signal, opened, params, size)
            logger.info {
                "Opened $ticker ${direction.name} qty=${opened.quantity} @ ${opened.entryPrice} " +
                    "sl=${params.stopLossPrice} tp=${params.takeProfitPrice}"
            }
        }
    }
}
