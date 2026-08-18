package com.trading.bot.service

import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.config.RiskConfig
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
        if (!riskConfig.enabled || atr == null || atr <= BigDecimal.ZERO || price <= BigDecimal.ZERO) return false
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
     * @param openPositions текущие открытые позиции
     * @return true, если портфель выйдет за лимиты exposure
     */
    fun exceedsPortfolioLimits(
        candidateNotionalRub: BigDecimal,
        candidateDirection: PositionDirection,
        openPositions: List<Position>,
    ): Boolean {
        if (candidateNotionalRub <= BigDecimal.ZERO) return false
        val deposit = aumProvider.latestAum()

        fun positionNotional(pos: Position): BigDecimal {
            val spec = instrumentsConfig.find(pos.ticker)
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
