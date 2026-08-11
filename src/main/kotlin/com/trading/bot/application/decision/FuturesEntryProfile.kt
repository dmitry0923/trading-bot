package com.trading.bot.application.decision

import com.trading.bot.application.OrderBuilder
import com.trading.bot.application.risk.FuturesPositionSizer
import com.trading.bot.application.risk.FuturesRiskEngine
import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.config.LeverageConfig
import com.trading.bot.config.RiskConfig
import com.trading.bot.domain.order.OrderParams
import com.trading.bot.domain.risk.EntryRequest
import com.trading.bot.domain.risk.PositionSizeResult
import com.trading.bot.domain.risk.RiskEngine
import com.trading.bot.domain.signal.Signal
import com.trading.bot.infrastructure.alor.AlorFuturesClient
import com.trading.bot.model.InstrumentType
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.entity.Position
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
 * - параметры заявки: SL/TP в пунктах, маржа, ликвидация, плечо
 *   ([OrderBuilder.buildFuturesOrderParams]);
 * - портфельный риск — READ_ONLY (Si-хедж не блокируется, метрики фиксируются;
 *   жёсткий BLOCK/SCALE для Si включается отдельно в фазе 2);
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
    ): EntryRequest {
        val currentGo = alorFuturesClient.getFuturesGO(signal.ticker)
        val portfolioMoney = alorFuturesClient.getPortfolioMoney(tradingAccountService.portfolioOf(accountId))
        return EntryRequest(
            ticker = signal.ticker,
            action = signal.action,
            entryPrice = entryPrice,
            direction = signal.direction(),
            portfolioMoney = portfolioMoney,
            currentGo = currentGo,
            openPositions = openPositions,
            accountId = accountId,
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
        val size =
            futuresPositionSizer.calculateContracts(
                signal.ticker,
                request.portfolioMoney,
                riskConfig.defaultStopLossPoints,
                request.currentGo,
                entryPrice,
                request.direction,
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
    ): OrderParams =
        orderBuilder.buildFuturesOrderParams(
            ticker = ticker,
            direction = direction,
            entryPrice = entryPrice,
            currentGo = request.currentGo,
            size = size,
            leverage = leverageConfig.effective(),
        )

    override fun portfolioMode(): PortfolioMode = PortfolioMode.READ_ONLY

    override fun buildPosition(
        signal: Signal,
        params: OrderParams,
        orderId: String?,
        pending: Boolean,
        fillPrice: BigDecimal,
        qty: Int,
    ): Position =
        Position(
            ticker = signal.ticker,
            direction = params.direction,
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
