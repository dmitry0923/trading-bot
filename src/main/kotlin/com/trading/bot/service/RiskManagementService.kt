package com.trading.bot.service

import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.config.RiskConfig
import com.trading.bot.infrastructure.alor.AlorFuturesClient
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.entity.Position
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Сервис классического риск-менеджмента.
 *
 * - Дневной лимит убытка, максимум открытых позиций, секторная концентрация
 * - Проверка волатильности (ATR%) перед входом
 * - Жёсткие портфельные лимиты Gross/Net Exposure
 *
 * Решение «входить/не входить» — [com.trading.bot.domain.risk.RiskEngine]
 * (FuturesRiskEngine/StockRiskEngine); размер позиции и SL/TP —
 * [com.trading.bot.domain.risk.PositionSizer] и OrderBuilder; правила выхода
 * (SL/TP/trailing) — [com.trading.bot.domain.risk.ExitRules]. Здесь — только
 * портфельные проверки и делегирование дневного P&L в единый источник.
 *
 * Дневной P&L и все Multi-Tier лимиты просадки (7д/30д, Shadow/Read-only) — единый
 * источник [DrawdownProtectionService]. Здесь — только делегирование без дублирования
 * состояния и без записи в daily_risk_snapshot.
 */
@Service
class RiskManagementService(
    private val riskConfig: RiskConfig,
    private val instrumentsConfig: InstrumentsConfig,
    private val drawdownProtection: DrawdownProtectionService,
    private val meterRegistry: MeterRegistry,
    private val aumProvider: AumProvider,
    private val alorFuturesClient: AlorFuturesClient,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Проверяет, достигнут ли дневной лимит убытка.
     *
     * @return true, если дневной P&L <= -maxDailyLossPercent% AUM (единый источник)
     */
    fun isDailyLossLimitReached(accountId: Long? = null): Boolean = drawdownProtection.isDailyLossLimitReached(accountId)

    /**
     * Проверка волатильности: ATR% от цены больше лимита → вход запрещён.
     * Вызывается перед открытием позиции (при наличии ATR).
     */
    fun isVolatilityTooHigh(
        atr: BigDecimal?,
        price: BigDecimal,
    ): Boolean {
        if (!riskConfig.enabled) return false
        if (atr == null || atr <= BigDecimal.ZERO || price <= BigDecimal.ZERO) {
            // ATR — обязательный risk input. Недоступность данных о волатильности
            // по умолчанию блокирует вход (fail-closed), см. risk.volatility-fail-closed.
            val blocked = riskConfig.volatilityFailClosed
            if (blocked) {
                logger.warn { "Volatility check: ATR/price unavailable (atr=$atr, price=$price) -> BLOCK (fail-closed)" }
            }
            return blocked
        }
        val atrPercent =
            atr
                .multiply(BigDecimal("100"))
                .divide(price, 4, RoundingMode.HALF_UP)
                .toDouble()
        val result = atrPercent > riskConfig.maxVolatilityPercent
        logger.info {
            "Volatility check: ATR%=$atrPercent vs limit=${riskConfig.maxVolatilityPercent}% -> ${if (result) "BLOCK" else "OK"}"
        }
        return result
    }

    /**
     * Жёсткие портфельные лимиты на Gross/Net Exposure.
     *
     * - Gross: сумма нотионалов ВСЕХ позиций (long + short) после добавления кандидата
     *   не должна превысить maxGrossExposurePercent от депозита (по умолчанию 150%);
     * - Net: чистый directional риск (long - short) после добавления кандидата
     *   не должен выйти за пределы ±maxNetExposurePercent от депозита (по умолчанию 100%).
     *
     * @param candidateNotionalRub нотионал кандидата в рублях (spec.notional(qty, price))
     * @param candidateDirection направление кандидата
     * @param openPositions текущие открытые позиции (должны принадлежать [accountId])
     * @param accountId аккаунт кандидата/позиций (AUM-база лимитов; null = legacy)
     * @return true, если портфель выйдет за лимиты exposure (или скоуп позиций нарушен)
     */
    fun exceedsPortfolioLimits(
        candidateNotionalRub: BigDecimal,
        candidateDirection: PositionDirection,
        openPositions: List<Position>,
        accountId: Long?,
    ): Boolean {
        if (candidateNotionalRub <= BigDecimal.ZERO) return false
        // P1-аудит: accountId передаётся ЯВНО, а не выводится из первой позиции —
        // иначе кандидат аккаунта B мерился бы AUM аккаунта A при scope-ошибке.
        // Нарушение scope (позиции другого аккаунта) — fail-closed: DENY + warn.
        if (openPositions.any { it.accountId != accountId }) {
            logger.warn {
                "Portfolio limit scope mismatch: accountId=$accountId vs positions " +
                    "${openPositions.map { it.accountId }} — DENY (P1)"
            }
            return true
        }
        val depositResult = aumProvider.latestAumResult(accountId)
        if (depositResult is AumProvider.AumResult.Unavailable) {
            // P1: AUM недоступен в LIVE — депозит не подменяется конфигурационным
            // (соотношение exposure к реальному балансу неизвестно) → DENY (fail-closed).
            logger.warn {
                "Portfolio limit check DENY: AUM unavailable for accountId=$accountId " +
                    "(candidate=$candidateNotionalRub)"
            }
            meterRegistry.counter("risk.portfolio.aum_unavailable.blocked").increment()
            return true
        }
        val deposit =
            (depositResult as AumProvider.AumResult.Available).value

        fun positionNotional(pos: Position): BigDecimal {
            val spec = instrumentsConfig.find(pos.ticker)
            // Портфельные лимиты Gross/Net Exposure считаются по РЕАЛЬНОЙ рыночной
            // экспозиции (market notional = цена × lotSize × qty) для ВСЕХ
            // инструментов, включая фьючерсы (P0-аудит). GO — это маржа (залог),
            // а не directional exposure: замена notional на ГО занижала реальный
            // ценовой риск позиции. Требуемая маржа (GO × qty) учитывается отдельным
            // маржинальным гейтом [maxMarginUsagePercent] (risk.portfolio.margin_usage),
            // см. [exceedsMarginUtilization].
            return spec?.notional(pos.quantity, pos.entryPrice)
                ?: pos.entryPrice.multiply(BigDecimal(pos.quantity))
        }

        val grossBefore = openPositions.sumOf { positionNotional(it) }
        val grossAfter = grossBefore.add(candidateNotionalRub)
        val grossLimit =
            deposit
                .multiply(BigDecimal(riskConfig.maxGrossExposurePercent))
                .divide(BigDecimal("100"), 2, RoundingMode.HALF_UP)
        if (grossAfter > grossLimit) {
            logger.warn {
                "Gross exposure limit: $grossAfter > $grossLimit (${riskConfig.maxGrossExposurePercent}% of deposit)"
            }
            meterRegistry.counter("risk.portfolio.gross_exposure.blocked").increment()
            return true
        }

        val longExposure =
            openPositions
                .filter { it.direction == PositionDirection.LONG }
                .sumOf { positionNotional(it) }
        val shortExposure =
            openPositions
                .filter { it.direction == PositionDirection.SHORT }
                .sumOf { positionNotional(it) }
        val netAfter =
            longExposure
                .subtract(shortExposure)
                .add(if (candidateDirection == PositionDirection.LONG) candidateNotionalRub else candidateNotionalRub.negate())
        val netLimit =
            deposit
                .multiply(BigDecimal(riskConfig.maxNetExposurePercent))
                .divide(BigDecimal("100"), 2, RoundingMode.HALF_UP)
        if (netAfter > netLimit || netAfter < netLimit.negate()) {
            logger.warn {
                "Net exposure limit: $netAfter outside ±$netLimit (${riskConfig.maxNetExposurePercent}% of deposit)"
            }
            meterRegistry.counter("risk.portfolio.net_exposure.blocked").increment()
            return true
        }
        return false
    }

    /**
     * Маржинальный гейт (P0-аудит): сумма требуемого гарантийного обеспечения (ГО)
     * ВСЕХ позиций фьючерсов + кандидата не должна превысить
     * [RiskConfig.maxMarginUsagePercent]% от депозита.
     *
     * В отличие от [exceedsPortfolioLimits] (который оперирует РЫНОЧНОЙ экспозицией
     * для Gross/Net), этот гейт контролирует ЗАЛОГОВУЮ загрузку: сколько депозита
     * реально связано маржой. Он независим от directional exposure и отдельно
     * ограничивает mandarin capacity (вероятность margin call / ликвидный резерв).
     *
     * @param deposit AUM-база лимита (депозит аккаунта кандидата)
     * @param existingMarginRub суммарное ГО уже открытых фьючерсных позиций аккаунта
     * @param candidateMarginRub требуемое ГО кандидата (actual GO × qty)
     * @return true, если суммарная маржинальная загрузка превысит лимит
     */
    fun exceedsMarginUtilization(
        deposit: BigDecimal,
        existingMarginRub: BigDecimal,
        candidateMarginRub: BigDecimal,
    ): Boolean {
        if (deposit <= BigDecimal.ZERO || candidateMarginRub <= BigDecimal.ZERO) return false
        val totalMargin = existingMarginRub.add(candidateMarginRub)
        val limit =
            deposit
                .multiply(BigDecimal(riskConfig.maxMarginUsagePercent))
                .divide(BigDecimal("100"), 2, RoundingMode.HALF_UP)
        if (totalMargin > limit) {
            logger.warn {
                "Margin utilization limit: $totalMargin (existing=$existingMarginRub + candidate=$candidateMarginRub) > " +
                    "$limit (${riskConfig.maxMarginUsagePercent}% of deposit)"
            }
            meterRegistry.counter("risk.portfolio.margin_utilization.blocked").increment()
            return true
        }
        return false
    }

    /**
     * Требуемое ГО одной открытой фьючерсной позиции (руб). Приоритет — персистенное
     * [Position.marginUsed] (фактическое при открытии, ГО × qty), fallback — статический
     * spec.go × qty.
     *
     * НЕ используется в LIVE-маржинальном гейте входа — там [freshMarginOfPositions]:
     * статический spec.go в LIVE — НЕ авторитет (GET_MOEX current GO отличается от
     * конфиг-оценки; для CNYRUBF 850 ₽ против ~1 000-2 700 ₽ на MOEX). Метод сохранён
     * для SIM/тест-фикстур и легаси-вызовов.
     */
    fun marginOfPosition(pos: Position): BigDecimal {
        val spec = instrumentsConfig.find(pos.ticker)
        if (spec != null && spec.type == "FUTURES") {
            return pos.marginUsed ?: spec.go.multiply(BigDecimal(pos.quantity))
        }
        return BigDecimal.ZERO
    }

    /**
     * Требуемое ГО ВСЕХ открытых фьючерсных позиций для маржинального гейта (P0/P1-аудит):
     *
     * - персистенное [Position.marginUsed] (зафиксировано при открытии из актуального ГО);
     * - при ОТСУТСТВИИ marginUsed — АКТУАЛЬНОЕ ГО из [AlorFuturesClient.getFuturesGO]
     *   (с TTL-кэшем 30с), НЕ статический spec.go: в LIVE конфиг-go устарел относительно
     *   биржи (CNYRUBF: 850 vs ~1 000-2 700 ₽ фактических на MOEX);
     * - если GO недоступно (API недоступен и кэш устарел) — return null (fail-closed,
     *   паритет P0-2/EXEC-005): маржинальная загрузка неизвестна → вход блокируется.
     *
     * В SIM-режиме [AlorFuturesClient] возвращает конфиг-GO — поведение идентично
     * прежнему fallback (spec.go), тесты/симуляция не меняются.
     *
     * @return суммарное ГО futures-позиций аккаунта или null при недоступности данных
     */
    suspend fun freshMarginOfPositions(openPositions: List<Position>): BigDecimal? {
        var total = BigDecimal.ZERO
        for (pos in openPositions) {
            val spec = instrumentsConfig.find(pos.ticker)
            if (spec == null || spec.type != "FUTURES") continue
            val margin =
                pos.marginUsed
                    ?: alorFuturesClient
                        .getFuturesGO(pos.ticker)
                        ?.multiply(BigDecimal(pos.quantity))
                    ?: return null
            total = total.add(margin)
        }
        return total
    }

    /**
     * Учёт P&L закрытой сделки в дневном итоге (делегирование в единый источник).
     *
     * @param pnl прибыль/убыток сделки
     */
    fun updateDailyPnL(
        pnl: BigDecimal,
        accountId: Long? = null,
    ) {
        drawdownProtection.updateDailyPnl(pnl, accountId)
    }

    /**
     * Текущий дневной P&L (единый источник [DrawdownProtectionService]).
     *
     * @return накопленный дневной P&L
     */
    fun getDailyPnL(accountId: Long? = null): BigDecimal = drawdownProtection.getDailyPnl(accountId)
}
