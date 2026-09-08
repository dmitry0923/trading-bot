package com.trading.bot.application.decision

import com.trading.bot.application.MarketDataGate
import com.trading.bot.application.OrderBuilder
import com.trading.bot.client.AlorClient
import com.trading.bot.config.DistributedLockConfig
import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.domain.risk.PortfolioRiskEngine
import com.trading.bot.domain.risk.PortfolioRiskRequest
import com.trading.bot.domain.risk.RiskVerdict
import com.trading.bot.domain.risk.TradeRiskDecision
import com.trading.bot.domain.signal.Signal
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.StrategyAction
import com.trading.bot.model.dto.MarketSnapshot
import com.trading.bot.repository.PositionRepository
import com.trading.bot.service.AdaptiveRiskService
import com.trading.bot.service.DegenerateCaseGuard
import com.trading.bot.service.DistributedLockService
import com.trading.bot.service.DistributedLockService.LockExecutionResult
import com.trading.bot.service.EntryLeaseRecoveryGate
import com.trading.bot.service.HigherTfTrendFilter
import com.trading.bot.service.MlEntryFilter
import com.trading.bot.service.TradingAccountService
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
 *   2. Распределённый лок на тикер (Redis, [DistributedLockService]) — при мульти-реплике
 *      вход выполняет только одна инстанция; при недоступности Redis вход блокируется
 *      (fail-closed, чтобы не открыть позицию без лока).
 *   3. [MarketDataGate] (defense in depth: свежие рыночные данные).
 *   4. Детектор вырожденных случаев ([DegenerateCaseGuard], roadmap 13.3.5):
 *      широкий спред, гэп, депозитарная пауза — отказ входа (fail-closed без данных).
 *   5. Цена входа: [AlorClient.getLastPrice] ?: signal.targetPrice.
 *   6. [EntryProfile.riskEngine].canEnter (Да/Нет).
 *   7. ML-фильтр входа ([MlEntryFilter], roadmap 13.11.5): прогноз модели < порога —
 *      отказ (pass-through при выключенном фильтре).
 *   8. [EntryProfile.preSizingChecks] (корреляционные фильтры акций).
 *   9. [EntryProfile.sizePosition] (Kelly / маржа-риск).
 *   10. [EntryProfile.postSizingChecks] (лимиты Gross/Net Exposure).
 *   11. [EntryProfile.buildOrderParams] (SL/TP/маржа/ликвидация).
 *   12. [PortfolioRiskEngine] по [EntryProfile.portfolioMode]:
 *       ENFORCED — BLOCK/SCALE, READ_ONLY — только метрики/логи.
 *   13. [OrderBuilder.recordStrategyExecution] + [ExecutionGateway.placeEntryOrder].
 *   14. [EntryProfile.onOpened] (дневной P&L reset + agent log для акций).
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
    private val distributedLockService: DistributedLockService,
    private val distributedLockConfig: DistributedLockConfig,
    private val tradingAccountService: TradingAccountService,
    private val mlEntryFilter: MlEntryFilter,
    private val higherTfTrendFilter: HigherTfTrendFilter,
    private val degenerateCaseGuard: DegenerateCaseGuard,
    private val instrumentsConfig: InstrumentsConfig,
    private val netEvGate: NetEvGate,
    private val adaptiveRisk: AdaptiveRiskService,
    private val entryLeaseRecoveryGate: EntryLeaseRecoveryGate,
) {
    private val logger = KotlinLogging.logger {}

    /** Per-ticker mutex входа: сериализует openPosition по тикеру (защита от
     *  гонки двух сигналов на один тикер → двойного ордера). */
    private val entryLocks = ConcurrentHashMap<String, Mutex>()

    /**
     * Per-account mutex входа (P1-аудит): сериализует ПОРТФЕЛЬНЫЙ пайплайн
     * (снапшот позиций → risk checks → placing ордера) для одного аккаунта —
     * закрывает race двух сигналов по РАЗНЫМ тикерам, которые оба проходят
     * MAX_POSITIONS/сектор/корреляцию/exposure/VaR по одному устаревшему снапшоту.
     * Per-ticker лок (и Redis) дубль по тикеру уже закрывает; этот закрывает
     * кросс-тикерные портфельные инварианты внутри аккаунта.
     */
    private val accountLocks = ConcurrentHashMap<String, Mutex>()

    /**
     * Сериализация выбора аккаунта ([TradingAccountService.selectAccount]): round-robin
     * счётчик и чтение ёмкости неатомарны — два параллельных сигнала могли выбрать
     * один аккаунт с неактуальной ёмкостью. Критическая секция короткая (только выбор).
     */
    private val entryAdmissionMutex = Mutex()

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
            if (entryLeaseRecoveryGate.isDegraded("ticker:${signal.ticker}")) {
                logger.warn { "Entry refused ${signal.ticker}: lease lost earlier — recovery/reconciliation pending" }
                meterRegistry
                    .counter("${profile.metricPrefix}.entry.rejected", Tags.of("ticker", signal.ticker, "reason", "LEASE_RECOVERY_PENDING"))
                    .increment()
                return
            }
            val result =
                distributedLockService.runExclusiveFenced(
                    name = "position:${signal.ticker}",
                    ttlSeconds = distributedLockConfig.positionOpenTtlSeconds,
                    failOpenOnError = false,
                ) { _ ->
                    doOpenPosition(signal, profile, gateway)
                }
            if (result == LockExecutionResult.LEASE_LOST) {
                entryLeaseRecoveryGate.mark("ticker:${signal.ticker}", "entry-ticker-lease-lost")
                logger.warn {
                    "Entry lease lost ${signal.ticker}: critical section may have " +
                        "partially executed — reconciliation required, new entries on ticker blocked until then"
                }
            } else if (result != LockExecutionResult.COMPLETED) {
                logger.info {
                    "Entry skipped ${signal.ticker}: distributed lock not acquired " +
                        "(another instance is opening / Redis unavailable)"
                }
            }
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

        // Вырожденные случаи (13.3.5): широкий спред, гэп, депозитарная пауза → отказ
        // входа. Fail-closed при недостатке данных (UNKNOWN ≠ SAFE).
        when (val result = degenerateCaseGuard.check(ticker, signal.timeframe)) {
            is DegenerateCaseGuard.GuardResult.Allowed -> {
                Unit
            }

            is DegenerateCaseGuard.GuardResult.Blocked -> {
                logger.warn { "Degenerate case rejected $ticker: ${result.reason}" }
                meterRegistry
                    .counter("${profile.metricPrefix}.risk.reject", Tags.of("ticker", ticker, "reason", result.reason))
                    .increment()
                return
            }
        }

        val direction = if (signal.action == StrategyAction.BUY) PositionDirection.LONG else PositionDirection.SHORT
        val spec = instrumentsConfig.find(ticker)
        if (spec == null) {
            logger.error { "No instrument spec for $ticker — entry blocked (unknown instrument)" }
            meterRegistry
                .counter("${profile.metricPrefix}.entry.rejected", Tags.of("ticker", ticker, "reason", "UNKNOWN_INSTRUMENT"))
                .increment()
            return
        }
        val snapshot = alorClient.getMarketSnapshot(ticker)
        val rawEntryPrice = snapshot?.microprice ?: snapshot?.currentPrice ?: signal.targetPrice
        val entryPrice = alignPriceToGrid(rawEntryPrice, spec.priceStep)

        // Multi-account: выбор портфеля для входа (весовой round-robin с ёмкостью),
        // сериализован — round-robin счётчик и чтение ёмкости неатомарны.
        // null = legacy single-account (таблица пуста) или все аккаунты переполнены.
        val accountId = entryAdmissionMutex.withLock { tradingAccountService.selectAccount() }

        // Если аккаунты сконфигурированы, но все переполнены — отклоняем вход,
        // а не утекаем в дефолтный (legacy) портфель.
        if (accountId == null && tradingAccountService.hasEnabledAccounts()) {
            logger.warn { "Entry rejected $ticker: all accounts at capacity (ACCOUNTS_FULL)" }
            meterRegistry
                .counter("${profile.metricPrefix}.risk.reject", Tags.of("ticker", ticker, "reason", "ACCOUNTS_FULL"))
                .increment()
            return
        }

        // Per-account admission (P1-аудит): снапшот позиций → риск-проверки → placing
        // ордера сериализуются по аккаунту (in-JVM mutex + Redis-лок). Второй сигнал
        // в тот же аккаунт ждёт и читает СВЕЖИЙ снапшот, поэтому MAX_POSITIONS/сектор/
        // корреляция/Gross-Net/VaR не могут «пройти вдвоём» по устаревшему состоянию.
        val accountKey = accountId?.toString() ?: "legacy"
        val result =
            accountLocks.computeIfAbsent(accountKey) { Mutex() }.withLock {
                if (entryLeaseRecoveryGate.isDegraded("account:$accountKey")) {
                    logger.warn { "Entry refused $ticker: lease lost earlier on account $accountKey — recovery/reconciliation pending" }
                    meterRegistry
                        .counter(
                            "${profile.metricPrefix}.entry.rejected",
                            Tags.of("ticker", ticker, "reason", "LEASE_RECOVERY_PENDING"),
                        ).increment()
                    return
                }
                distributedLockService.runExclusiveFenced(
                    name = "position:account:$accountKey",
                    ttlSeconds = distributedLockConfig.positionOpenTtlSeconds,
                    failOpenOnError = false,
                ) { fence ->
                    doOpenPositionForAccount(
                        signal = signal,
                        profile = profile,
                        gateway = gateway,
                        ticker = ticker,
                        direction = direction,
                        entryPrice = entryPrice,
                        spec = spec,
                        snapshot = snapshot,
                        accountId = accountId,
                        fence = fence,
                    )
                }
            }
        if (result == LockExecutionResult.LEASE_LOST) {
            entryLeaseRecoveryGate.mark("account:$accountKey", "entry-account-lease-lost")
            logger.warn {
                "Entry lease lost $ticker on account $accountKey: critical section may " +
                    "have partially executed — reconciliation required, new entries on account blocked until then"
            }
        } else if (result != LockExecutionResult.COMPLETED) {
            logger.info {
                "Entry skipped $ticker: account distributed lock not acquired " +
                    "(another instance opening on account $accountKey / Redis unavailable)"
            }
        }
    }

    /** Тело риск-пайплайна ПОСЛЕ выбора аккаунта, сериализованное на аккаунт
     *  (per-account mutex + Redis [position:account:*], см. [doOpenPosition]).
     *  Цена входа и снапшот приходят из вызывающего контекста — повторный сетевой
     *  вызов внутри account-лока не делается. */
    private suspend fun doOpenPositionForAccount(
        signal: Signal,
        profile: EntryProfile,
        gateway: ExecutionGateway,
        ticker: String,
        direction: PositionDirection,
        entryPrice: BigDecimal,
        spec: InstrumentsConfig.InstrumentSpec,
        snapshot: MarketSnapshot?,
        accountId: Long?,
        fence: DistributedLockService.LeaseFence?,
    ) {
        // F-11 (roadmap 13.25): открытые позиции берутся ТОЛЬКО по выбранному аккаунту —
        // иначе MAX_POSITIONS, корреляционные и портфельные лимиты считались по ПУЛУ всех
        // аккаунтов. accountId = null (legacy) — позиции с account_id = NULL.
        val openPositions = positionRepo.findByStatus(PositionStatus.OPEN).filter { it.accountId == accountId }

        // Риск-этап: Да/Нет (дневной лимит, drawdown, волатильность, дубли,
        // лимиты позиций/секторов, ATR%, STRESS, валидность входных данных).
        val request = profile.buildEntryRequest(signal, entryPrice, openPositions, accountId)
        if (request == null) {
            logger.warn { "Entry rejected $ticker: portfolio data unavailable (LIVE API)" }
            meterRegistry
                .counter("${profile.metricPrefix}.risk.reject", Tags.of("ticker", ticker, "reason", "PORTFOLIO_DATA_UNAVAILABLE"))
                .increment()
            return
        }
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

        // ML-фильтр входа (13.11.5): прогноз модели < порога → отказ. Выключен —
        // pass-through. Fail-closed при включённом фильтре и недоступной модели.
        mlEntryFilter.shouldBlock(signal)?.let { reason ->
            logger.warn { "ML filter rejected $ticker: $reason" }
            meterRegistry
                .counter("${profile.metricPrefix}.risk.reject", Tags.of("ticker", ticker, "reason", "ML_FILTER"))
                .increment()
            return
        }

        // Multi-timeframe фильтр тренда (13.9.1): вход против тренда старшего ТФ →
        // отказ. Выключен — pass-through. Fail-closed при включённом фильтре и
        // недостатке свечей для тренда.
        higherTfTrendFilter.shouldBlock(signal)?.let { reason ->
            logger.warn { "Higher-TF filter rejected $ticker: $reason" }
            meterRegistry
                .counter("${profile.metricPrefix}.risk.reject", Tags.of("ticker", ticker, "reason", "MTF_FILTER"))
                .increment()
            return
        }

        profile.preSizingChecks(ticker, openPositions)?.let { reason ->
            logger.warn { "Pre-sizing filter reject $ticker: $reason" }
            meterRegistry
                .counter("${profile.metricPrefix}.risk.reject", Tags.of("ticker", ticker, "reason", reason))
                .increment()
            return
        }

        // Сайзинг (Kelly для акций, маржа/риск для фьючерсов).
        val size = profile.sizePosition(signal, entryPrice, request)

        profile.postSizingChecks(request, direction, size, openPositions)?.let { reason ->
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

        // NET EV Gate (P0 audit): блокируем сделки с отрицательным или слишком низким
        // математическим ожиданием после учёта комиссии, спреда и adverse selection.
        // expectedNetPerLot уже с вычетом комиссии (PnlCalculator).
        val expectedNetPerLot = adaptiveRisk.expectedNetProfitPerLot(ticker, accountId)
        when (val evResult = netEvGate.check(ticker, expectedNetPerLot, snapshot)) {
            is NetEvGate.GateResult.Blocked -> {
                logger.warn { "NET EV rejected $ticker: netEV=${evResult.netEV ?: "UNKNOWN (insufficient data)"}" }
                meterRegistry
                    .counter("${profile.metricPrefix}.risk.reject", Tags.of("ticker", ticker, "reason", "NET_EV_TOO_LOW"))
                    .increment()
                return
            }

            is NetEvGate.GateResult.Pass -> {}
        }

        // Портфельный риск (агрегат): VaR95 / эффективное число ставок / направленная
        // концентрация. ENFORCED — блок/уменьшение размера; READ_ONLY — только метрики.
        val initialDecision = TradeRiskDecision.from(signal, request, size, params)
        val candidatePrice = initialDecision.entryPrice ?: entryPrice
        val portfolioNotional = spec.notional(initialDecision.quantity, candidatePrice)
        val portfolioReport =
            portfolioRiskEngine.evaluate(
                PortfolioRiskRequest(
                    candidateTicker = initialDecision.ticker,
                    candidateDirection = initialDecision.direction,
                    candidateNotionalRub = portfolioNotional,
                    openPositions = openPositions,
                    aum = request.portfolioMoney,
                ),
            )
        var finalSize = size
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
                    if (scaledQty < 1) {
                        logger.warn {
                            "Portfolio risk block $ticker: scale ${portfolioReport.scaleDownFactor} reduces qty ${params.quantity} below 1 lot"
                        }
                        meterRegistry.counter("${profile.metricPrefix}.risk.scaleBlock", Tags.of("ticker", ticker)).increment()
                        return
                    }
                    if (scaledQty != params.quantity) {
                        finalSize = size.copy(quantity = scaledQty)
                        params = profile.buildOrderParams(ticker, direction, entryPrice, finalSize, request)
                        val scaledNotional = spec.notional(scaledQty, entryPrice)
                        val recheck =
                            portfolioRiskEngine.evaluate(
                                PortfolioRiskRequest(
                                    candidateTicker = ticker,
                                    candidateDirection = direction,
                                    candidateNotionalRub = scaledNotional,
                                    openPositions = openPositions,
                                    aum = request.portfolioMoney,
                                ),
                            )
                        if (!recheck.allowed) {
                            logger.warn { "Portfolio risk re-check rejected $ticker after SCALE: ${recheck.reasons.joinToString("|")}" }
                            meterRegistry.counter("${profile.metricPrefix}.risk.scaleReject", Tags.of("ticker", ticker)).increment()
                            return
                        }
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

        // Финальное риск-решение сделки: сигнал + риск-вход + размер + параметры
        // заявки схлопываются в [TradeRiskDecision] и прогоняются через исполнение
        // (position/логи/история стратегии) без дублирования полей.
        val decision = TradeRiskDecision.from(signal, request, finalSize, params)
        val opened =
            gateway.placeEntryOrder(
                decision.ticker,
                decision.direction,
                decision.quantity,
                decision.entryPrice ?: entryPrice,
                accountId,
                buildPosition = { orderId, pending, fillPrice, qty ->
                    profile.buildPosition(decision, orderId, pending, fillPrice, qty)
                },
                fence = fence,
            )
        if (opened != null) {
            orderBuilder.recordStrategyExecution(decision)
            profile.onOpened(decision, opened)
            logger.info {
                "Opened ${decision.ticker} ${decision.direction.name} qty=${opened.quantity} @ ${opened.entryPrice} " +
                    "sl=${decision.stopLoss} tp=${decision.takeProfit}"
            }
        }
    }

    /**
     * Округление цены до сетки цен инструмента (priceStep).
     * Гарантирует, что лимитный ордер будет выставлен на валидном уровне MOEX.
     */
    private fun alignPriceToGrid(
        price: BigDecimal,
        priceStep: BigDecimal,
    ): BigDecimal {
        if (priceStep <= BigDecimal.ZERO) return price
        val scale = priceStep.scale()
        return price.divide(priceStep, 0, RoundingMode.HALF_UP).multiply(priceStep).setScale(scale, RoundingMode.HALF_UP)
    }
}
