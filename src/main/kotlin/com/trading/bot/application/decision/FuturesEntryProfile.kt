package com.trading.bot.application.decision

import com.trading.bot.application.OrderBuilder
import com.trading.bot.application.risk.FuturesPositionSizer
import com.trading.bot.application.risk.FuturesRiskEngine
import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.config.LeverageConfig
import com.trading.bot.config.RiskConfig
import com.trading.bot.config.toFuturesAtrStopPolicy
import com.trading.bot.domain.order.OrderParams
import com.trading.bot.domain.risk.EntryRequest
import com.trading.bot.domain.risk.FuturesStopResolver
import com.trading.bot.domain.risk.PortfolioRiskEngine
import com.trading.bot.domain.risk.PositionSizeResult
import com.trading.bot.domain.risk.RiskEngine
import com.trading.bot.domain.risk.TradeRiskDecision
import com.trading.bot.domain.signal.Signal
import com.trading.bot.infrastructure.alor.AlorFuturesClient
import com.trading.bot.model.InstrumentType
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.entity.Position
import com.trading.bot.service.CandleCacheService
import com.trading.bot.service.LiveFrozenStrategyResolver
import com.trading.bot.service.TradingAccountService
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * Профиль входа для фьючерсов (Si).
 *
 * Воспроизводит прежний пайплайн FuturesEntryCoordinator:
 * - риск-этап: [FuturesRiskEngine] (Да/Нет, с STRESS-проверкой);
 * - сайзинг: [FuturesPositionSizer] (риск на сделку / маржинальный бюджет / лимит контрактов);
 * - стоп: ATR-адаптивная дистанция в пунктах через [FuturesStopResolver]
 *   (fallback — [RiskConfig.defaultStopLossPoints]); передаётся и в сайзер
 *   (риск на сделку учитывает ATR-стоп), и в параметры заявки;
 * - параметры заявки: SL/TP в ценах от стопа, маржа, ликвидация, плечо
 *   ([OrderBuilder.buildFuturesOrderParams]);
 * - портфельный риск — ENFORCED ([PortfolioRiskEngine]): превышение VaR95 / слабая
 *   диверсификация / направленная концентрация BLOCK-ают вход, умеренное превышение
 *   warn-порогов SCALE-ит размер (как для акций). Одиночный Si-вход на свободных
 *   средствах не блокируется (концентрация 100% = порог, VaR95 1-контрактной позиции
 *   далёк от лимита 5% AUM); жёсткий лимит срабатывает при удвоении в тот же тикер
 *   (effectivePositions → 1) и при перевесе портфеля;
 * - после открытия: без побочных эффектов (PositionOpened публикует ядро исполнения).
 */
@Component
class FuturesEntryProfile(
    futuresRiskEngine: FuturesRiskEngine,
    private val futuresPositionSizer: FuturesPositionSizer,
    private val orderBuilder: OrderBuilder,
    private val alorFuturesClient: AlorFuturesClient,
    private val riskConfig: RiskConfig,
    private val leverageConfig: LeverageConfig,
    private val instrumentsConfig: InstrumentsConfig,
    private val meterRegistry: MeterRegistry,
    private val tradingAccountService: TradingAccountService,
    private val candleCache: CandleCacheService,
    private val futuresStopResolver: FuturesStopResolver,
    private val liveFrozenStrategyResolver: LiveFrozenStrategyResolver,
) : EntryProfile {
    private val logger = KotlinLogging.logger {}

    override val instrumentType: InstrumentType = InstrumentType.FUTURES
    override val metricPrefix: String = "futures"
    override val riskEngine: RiskEngine = futuresRiskEngine

    override fun matches(ticker: String): Boolean = instrumentsConfig.isFutures(ticker)

    override suspend fun buildEntryRequest(
        signal: Signal,
        entryPrice: BigDecimal,
        openPositions: List<Position>,
        accountId: Long?,
    ): EntryRequest? {
        val currentGo = alorFuturesClient.getFuturesGO(signal.ticker)
        val portfolioMoney = alorFuturesClient.getPortfolioMoney(tradingAccountService.portfolioOf(accountId))
        if (portfolioMoney == null || currentGo == null) {
            logger.warn {
                "Portfolio data unavailable for accountId=$accountId (money=$portfolioMoney, go=$currentGo), " +
                    "blocking entry for ${signal.ticker} (EXEC-005/P1)"
            }
            return null
        }
        return EntryRequest(
            ticker = signal.ticker,
            action = signal.action,
            entryPrice = entryPrice,
            direction = signal.direction(),
            portfolioMoney = portfolioMoney,
            currentGo = currentGo,
            openPositions = openPositions,
            accountId = accountId,
            frozenStrategy = liveFrozenStrategyResolver.resolveActive(signal.ticker),
        )
    }

    override suspend fun preSizingChecks(
        ticker: String,
        openPositions: List<Position>,
    ): String? = null

    override suspend fun sizePosition(
        signal: Signal,
        entryPrice: BigDecimal,
        request: EntryRequest,
    ): PositionSizeResult {
        val frozen = request.frozenStrategy
        // P1-аудит: при LIVE-исполнении замороженной стратегии стоп в пунктах —
        // ПЛОСКИЙ из frozen (ровно как перевалидировано), НЕ ATR-адаптивный.
        val stopLossPoints =
            frozen
                ?.slPoints
                ?.takeIf { it > 0 }
                ?: resolveStopLossPoints(signal.ticker)
        val size =
            futuresPositionSizer.calculateContracts(
                signal.ticker,
                request.portfolioMoney,
                stopLossPoints,
                request.currentGo,
                entryPrice,
                request.direction,
                riskPerTradePercent = frozen?.riskPerTradePercent,
                maxContractsPerPosition = frozen?.futuresMaxContractsPerPosition,
            )
        if (size.quantity == 0) {
            logger.warn { "Position sizer rejected ${signal.ticker}: ${size.reason}" }
            meterRegistry
                .counter("futures.entry.rejected", Tags.of("ticker", signal.ticker, "reason", size.reason ?: "ZERO_SIZE"))
                .increment()
        }
        return size
    }

    override suspend fun postSizingChecks(
        ticker: String,
        direction: PositionDirection,
        entryPrice: BigDecimal,
        size: PositionSizeResult,
        openPositions: List<Position>,
    ): String? = null

    override fun buildOrderParams(
        ticker: String,
        direction: PositionDirection,
        entryPrice: BigDecimal,
        size: PositionSizeResult,
        request: EntryRequest,
    ): OrderParams {
        val frozen = request.frozenStrategy
        return orderBuilder.buildFuturesOrderParams(
            ticker = ticker,
            direction = direction,
            entryPrice = entryPrice,
            currentGo = request.currentGo,
            size = size,
            // P1-аудит: плечо и SL/TP-пункты при LIVE-исполнении берутся ИЗ frozen.
            leverage =
                frozen
                    ?.leverage
                    ?.takeIf { it > 0.0 }
                    ?.let { BigDecimal.valueOf(it) }
                    ?: leverageConfig.effective(),
            stopLossPoints = frozen?.slPoints?.takeIf { it > 0 } ?: resolveStopLossPoints(ticker),
            takeProfitPointsOverride = frozen?.tpPoints?.takeIf { it > 0 },
        )
    }

    /**
     * Дистанция стоп-лосса фьючерса в пунктах: ATR по свечам MINUTE_10 из кэша,
     * политика (флаг, клампы, fallback) — единый [FuturesStopResolver], тот же,
     * что использует backtest. Если данных недостаточно — фиксированный дефолт.
     */
    private fun resolveStopLossPoints(ticker: String): Int {
        val instrument = instrumentsConfig.find(ticker) ?: return riskConfig.defaultStopLossPoints
        val atr =
            candleCache.calculateAtr(
                ticker,
                "MINUTE_10",
                riskConfig.futuresAtrStopPeriod,
            )
        return futuresStopResolver.resolve(atr, instrument.priceStep, riskConfig.toFuturesAtrStopPolicy())
    }

    override fun portfolioMode(): PortfolioMode = PortfolioMode.ENFORCED

    override fun buildPosition(
        decision: TradeRiskDecision,
        orderId: String?,
        pending: Boolean,
        fillPrice: BigDecimal,
        qty: Int,
    ): Position =
        Position(
            ticker = decision.ticker,
            direction = decision.direction,
            quantity = qty,
            entryPrice = fillPrice,
            currentPrice = fillPrice,
            stopLoss = decision.stopLoss,
            takeProfit = decision.takeProfit,
            trailingStopPrice = if (decision.trailingStop) decision.stopLoss else null,
            instrumentType = InstrumentType.FUTURES,
            leverage = decision.leverage ?: leverageConfig.effective(),
            goPerContract = decision.goPerContract,
            marginUsed = decision.marginRequired,
            liquidationPrice = decision.liquidationPrice,
            variationMargin = BigDecimal.ZERO,
            stopLossPoints = decision.stopLossPoints,
            alorOrderId = orderId,
            pendingEntry = pending,
            cycleId = decision.cycleId,
        )
}
