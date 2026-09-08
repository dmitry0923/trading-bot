package com.trading.bot.application.decision

import com.trading.bot.application.OrderBuilder
import com.trading.bot.application.funding.FundingSnapshotService
import com.trading.bot.application.risk.FuturesPositionSizer
import com.trading.bot.application.risk.FuturesRiskEngine
import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.config.LeverageConfig
import com.trading.bot.config.RiskConfig
import com.trading.bot.config.toFuturesAtrStopPolicy
import com.trading.bot.domain.order.OrderParams
import com.trading.bot.domain.risk.EntryRequest
import com.trading.bot.domain.risk.FuturesRiskSnapshot
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
import com.trading.bot.service.AdaptiveRiskService
import com.trading.bot.service.CandleCacheService
import com.trading.bot.service.LiveFrozenStrategyResolver
import com.trading.bot.service.RiskManagementService
import com.trading.bot.service.TradingAccountService
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDateTime

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
    private val adaptiveRisk: AdaptiveRiskService,
    private val risk: RiskManagementService,
    private val fundingSnapshotService: FundingSnapshotService,
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
        // P1-аудит (снапшот): свободные средства, ГО кандидата (side-specific long/short,
        // P0) и ГО уже открытых фьючерсных позиций снимаются в ОДИН координатный момент
        // (FuturesRiskSnapshot) — сайзинг и маржинальный гейт не мешают разрозненные
        // запросы T0 ≠ T0+Δ.
        val portfolioMoney =
            alorFuturesClient.getPortfolioMoney(
                tradingAccountService.portfolioOf(accountId),
            )
        val direction = signal.direction()
        val currentGo = alorFuturesClient.getFuturesGO(signal.ticker, direction)
        if (portfolioMoney == null || currentGo == null) {
            logger.warn {
                "Portfolio data unavailable for accountId=$accountId (money=$portfolioMoney, go=$currentGo), " +
                    "blocking entry for ${signal.ticker} (EXEC-005/P1)"
            }
            return null
        }
        val openFuturesGoPerTicker = mutableMapOf<String, BigDecimal>()
        for (pos in openPositions) {
            if (!instrumentsConfig.isFutures(pos.ticker) || openFuturesGoPerTicker.containsKey(pos.ticker)) continue
            val go = alorFuturesClient.getFuturesGO(pos.ticker, pos.direction)
            if (go == null) {
                logger.warn {
                    "Open position GO unavailable for ${pos.ticker} (accountId=$accountId) — " +
                        "blocking entry (fresh snapshot, fail-closed)"
                }
                return null
            }
            openFuturesGoPerTicker[pos.ticker] = go
        }
        val snapshot =
            FuturesRiskSnapshot(
                takenAt = LocalDateTime.now(),
                accountId = accountId,
                portfolioMoney = portfolioMoney,
                candidateGo = currentGo,
                openFuturesGoPerTicker = openFuturesGoPerTicker,
            )
        // До входа обновляем funding-снапшот тикера для P&L: MOEX при наличии,
        // иначе provisional CONFIG fallback (метрика funding.live.provider_unavailable).
        fundingSnapshotService.refresh(signal.ticker)
        return EntryRequest(
            ticker = signal.ticker,
            action = signal.action,
            entryPrice = entryPrice,
            direction = direction,
            portfolioMoney = snapshot.portfolioMoney,
            currentGo = snapshot.candidateGo,
            openPositions = openPositions,
            accountId = accountId,
            frozenStrategy = liveFrozenStrategyResolver.resolveActive(signal.ticker),
            futuresRiskSnapshot = snapshot,
        )
    }

    override suspend fun preSizingChecks(
        ticker: String,
        openPositions: List<Position>,
    ): String? =
        when {
            adaptiveRisk.exceedsCorrelationLimit(ticker, openPositions) -> "CORRELATION"
            adaptiveRisk.exceedsSectorCorrelationLimit(ticker, openPositions) -> "SECTOR_CORRELATION"
            else -> null
        }

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
        request: EntryRequest,
        direction: PositionDirection,
        size: PositionSizeResult,
        openPositions: List<Position>,
    ): String? {
        val ticker = request.ticker
        if (size.quantity < 1) return "ZERO_RISK_SIZE"
        val spec = instrumentsConfig.find(ticker) ?: return "INSTRUMENT_SPEC_MISSING"
        // P0-аудит (разделение exposure и margin): портфельные лимиты Gross/Net
        // считаются по РЕАЛЬНОЙ рыночной экспозиции (market notional = цена ×
        // lotSize × qty), а НЕ по ГО. GO — залог, а не directional exposure;
        // заменять notional на GO нельзя (занижает ценовой риск). Требуемая маржа
        // контролируется отдельным гейтом [RiskManagementService.exceedsMarginUtilization]
        // по фактическому ГО (size.marginRequired = actual currentGo × qty из сайзера).
        //
        // Отсутствие InstrumentSpec для фьючерса — FAIL-CLOSED (INSTRUMENT_SPEC_MISSING):
        // fallback price × qty математически НЕВЕРЕН для фьючерса (забывает lotSize,
        // для CNYRUBF дал бы 12.8 ₽ вместо 12 800 ₽ на контракт) и занизил бы Gross/Net
        // риск-гейт до ложного прохода. Не найдено — вход блокируется, а не продолжается.
        val marketNotional = spec.notional(size.quantity, request.entryPrice)

        // Маржинальная загрузка: существующие фьючерсные позиции по ГО + кандидат
        // (actual currentGo × qty) против maxMarginUsagePercent% от депозита аккаунта.
        // Источник ГО существующих позиций — [RiskManagementService.freshMarginOfPositions]
        // из ЕДИНОГО риск-снапшота входа (request.futuresRiskSnapshot, P1): ГО открытых
        // позиций и кандидата сняты в один момент. Живой GO с TTL-кэшем, персистенное
        // marginUsed и статический spec.go в LIVE НЕ используются (CNYRUBF: marginUsed
        // застывает на моменте открытия, spec.go 850 ₽ против ~1 000-2 700 ₽ на MOEX).
        // Недоступность ГО существующих позиций (позиция вне снапшота / API down +
        // устаревший кэш) → fail-closed MARGIN_DATA_UNAVAILABLE: загрузка неизвестна,
        // вход блокируется (паритет P0-2/EXEC-005).
        // P1-аудит (stressed margin): ГО кандидата оценивается С ЗАПАСОМ 1.5x —
        // запас на рост/пересчёт ГО после открытия. Вход на пределе лимита запрещён
        // (малейшее повышение ГО → превышение лимита и риск margin call).
        val snapshotMap = request.futuresRiskSnapshot?.openFuturesGoPerTicker
        val existingMargin = risk.freshMarginOfPositions(openPositions, snapshotMap) ?: return "PORTFOLIO_MARGIN_DATA_UNAVAILABLE"
        val stressedCandidateMargin =
            size.marginRequired.multiply(BigDecimal(riskConfig.stressedMarginMultiplier))
        if (risk.exceedsMarginUtilization(request.portfolioMoney, existingMargin, stressedCandidateMargin)) {
            return "PORTFOLIO_MARGIN_LIMIT"
        }

        return if (risk.exceedsPortfolioLimits(marketNotional, direction, openPositions, request.accountId)) "PORTFOLIO_LIMIT" else null
    }

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
