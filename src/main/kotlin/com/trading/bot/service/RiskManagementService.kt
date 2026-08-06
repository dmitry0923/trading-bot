package com.trading.bot.service

import com.trading.bot.config.RiskConfig
import com.trading.bot.model.Position
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.RiskCheckResult
import com.trading.bot.model.Strategy
import com.trading.bot.repository.DailyRiskSnapshotRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId

/**
 * Сервис классического риск-менеджмента.
 *
 * - Дневной лимит убытка, максимум открытых позиций, секторная концентрация
 * - Проверка волатильности (ATR%) перед входом
 * - Расчёт SL/TP по проценту от цены входа, трейлинг-стоп
 * - Контроль выхода по SL/TP/trailing для открытых позиций
 * - Учёт дневного P&L, персистится в daily_risk_snapshot (восстановление после рестарта)
 */
@Service
class RiskManagementService(
    private val riskConfig: RiskConfig,
    private val dailyRiskSnapshotRepo: DailyRiskSnapshotRepository,
    private val meterRegistry: MeterRegistry,
) {
    private val logger = KotlinLogging.logger {}
    private val moscowZone = ZoneId.of("Europe/Moscow")
    private var dailyPnL: BigDecimal = BigDecimal.ZERO
    private var maxDrawdownToday: BigDecimal = BigDecimal.ZERO
    private var dailyLossLimitReached: Boolean = false
    private var lastTradingDate: LocalDate = LocalDate.MIN

    /**
     * Проверяет, достигнут ли дневной лимит убытка.
     *
     * @return true, если дневной P&L <= -maxDailyLossRub
     */
    fun isDailyLossLimitReached(): Boolean {
        resetDailyStateIfNewDay()
        return dailyLossLimitReached
    }

    /**
     * Валидирует новую стратегию перед открытием позиции.
     *
     * @param strategy предлагаемая стратегия
     * @param openPositions текущие открытые позиции
     * @return результат проверки: разрешена ли сделка и с каким количеством
     */
    fun validateNewStrategy(
        strategy: Strategy,
        openPositions: List<Position>,
    ): RiskCheckResult {
        if (riskConfig.enabled && isDailyLossLimitReached()) {
            return RiskCheckResult(false, "Daily loss limit reached ($dailyPnL <= -${riskConfig.maxDailyLossRub})", 0)
        }
        if (riskConfig.enabled && openPositions.size >= riskConfig.maxOpenPositions) {
            return RiskCheckResult(false, "Max open positions reached (${riskConfig.maxOpenPositions})", 0)
        }
        if (riskConfig.enabled && exceedsSectorExposure(strategy.ticker, openPositions)) {
            val sector = sectorOf(strategy.ticker)
            val count = openPositions.count { sectorOf(it.ticker) == sector }
            return RiskCheckResult(
                false,
                "Sector concentration exceeded: $count open in sector $sector >= max ${riskConfig.maxSectorExposure}",
                0,
            )
        }
        return RiskCheckResult(true, "OK", strategy.quantity)
    }

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
     * Секторная концентрация: количество открытых позиций в одном секторе.
     * Справочник секторов — из risk.sectors (ticker -> sector), иначе "UNKNOWN".
     */
    fun exceedsSectorExposure(
        ticker: String,
        openPositions: List<Position>,
    ): Boolean {
        val sector = sectorOf(ticker)
        val count = openPositions.count { sectorOf(it.ticker) == sector }
        return count >= riskConfig.maxSectorExposure
    }

    /**
     * Сектор инструмента по тикеру.
     *
     * @param ticker тикер инструмента
     * @return сектор из справочника risk.sectors или "UNKNOWN"
     */
    fun sectorOf(ticker: String): String = riskConfig.sectors[ticker] ?: "UNKNOWN"

    /**
     * Жёсткие портфельные лимиты на Gross/Net Exposure.
     *
     * - Gross: сумма нотионалов ВСЕХ позиций (long + short) после добавления кандидата
     *   не должна превысить maxGrossExposurePercent от депозита (по умолчанию 150%);
     * - Net: чистый directional риск (long - short) после добавления кандидата
     *   не должен выйти за пределы ±maxNetExposurePercent от депозита (по умолчанию 100%).
     *
     * @param candidateNotionalRub нотионал кандидата в рублях (qty * entryPrice)
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
        val deposit = riskConfig.maxPositionRub

        val grossBefore = openPositions.sumOf { it.entryPrice.multiply(BigDecimal(it.quantity)) }
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
                .sumOf { it.entryPrice.multiply(BigDecimal(it.quantity)) }
        val shortExposure =
            openPositions
                .filter { it.direction == PositionDirection.SHORT }
                .sumOf { it.entryPrice.multiply(BigDecimal(it.quantity)) }
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
     * Проверяет, нужно ли закрыть позицию по стоп-лоссу при текущей цене.
     *
     * @param pos открытая позиция
     * @param price текущая цена
     * @return true, если цена пробила stopLoss в сторону убытка
     */
    fun shouldCloseBySL(
        pos: Position,
        price: BigDecimal,
    ): Boolean =
        when (pos.direction) {
            PositionDirection.LONG -> pos.stopLoss != null && price <= pos.stopLoss
            PositionDirection.SHORT -> pos.stopLoss != null && price >= pos.stopLoss
        }

    /**
     * Проверяет, нужно ли закрыть позицию по тейк-профиту при текущей цене.
     *
     * @param pos открытая позиция
     * @param price текущая цена
     * @return true, если цена достигла takeProfit
     */
    fun shouldCloseByTP(
        pos: Position,
        price: BigDecimal,
    ): Boolean =
        when (pos.direction) {
            PositionDirection.LONG -> pos.takeProfit != null && price >= pos.takeProfit
            PositionDirection.SHORT -> pos.takeProfit != null && price <= pos.takeProfit
        }

    /**
     * Проверяет, нужно ли закрыть позицию по трейлинг-стопу при текущей цене.
     *
     * @param pos открытая позиция
     * @param price текущая цена
     * @return true, если цена пробила trailingStopPrice
     */
    fun shouldCloseByTrailing(
        pos: Position,
        price: BigDecimal,
    ): Boolean {
        if (!riskConfig.trailingStopEnabled || pos.trailingStopPrice == null) return false
        return when (pos.direction) {
            PositionDirection.LONG -> price <= pos.trailingStopPrice
            PositionDirection.SHORT -> price >= pos.trailingStopPrice
        }
    }

    /**
     * Обновляет трейлинг-стоп позиции по текущей цене (если трейлинг включён).
     *
     * @param pos открытая позиция (мутируется)
     * @param price текущая цена
     */
    fun updateTrailingStop(
        pos: Position,
        price: BigDecimal,
    ) {
        if (!riskConfig.trailingStopEnabled) return
        val percent = BigDecimal(riskConfig.trailingStopPercent.toString()).divide(BigDecimal("100"))
        val newStop =
            when (pos.direction) {
                PositionDirection.LONG -> price.multiply(BigDecimal.ONE.subtract(percent))
                PositionDirection.SHORT -> price.multiply(BigDecimal.ONE.add(percent))
            }
        pos.trailingStopPrice = newStop.setScale(2, RoundingMode.HALF_UP)
    }

    /**
     * Рассчитывает цену стоп-лосса по проценту от цены входа.
     *
     * @param entryPrice цена входа
     * @param direction направление позиции
     * @return цена стоп-лосса (с 2 знаками после запятой)
     */
    fun calcSL(
        entryPrice: BigDecimal,
        direction: PositionDirection,
    ): BigDecimal {
        val percent = BigDecimal(riskConfig.defaultStopLossPercent.toString()).divide(BigDecimal("100"))
        return when (direction) {
            PositionDirection.LONG -> entryPrice.multiply(BigDecimal.ONE.subtract(percent)).setScale(2, RoundingMode.HALF_UP)
            PositionDirection.SHORT -> entryPrice.multiply(BigDecimal.ONE.add(percent)).setScale(2, RoundingMode.HALF_UP)
        }
    }

    /**
     * Рассчитывает цену тейк-профита по проценту от цены входа.
     *
     * @param entryPrice цена входа
     * @param direction направление позиции
     * @return цена тейк-профита (с 2 знаками после запятой)
     */
    fun calcTP(
        entryPrice: BigDecimal,
        direction: PositionDirection,
    ): BigDecimal {
        val percent = BigDecimal(riskConfig.defaultTakeProfitPercent.toString()).divide(BigDecimal("100"))
        return when (direction) {
            PositionDirection.LONG -> entryPrice.multiply(BigDecimal.ONE.add(percent)).setScale(2, RoundingMode.HALF_UP)
            PositionDirection.SHORT -> entryPrice.multiply(BigDecimal.ONE.subtract(percent)).setScale(2, RoundingMode.HALF_UP)
        }
    }

    /**
     * Добавляет P&L закрытой сделки к дневному итогу и персистит состояние.
     *
     * @param pnl прибыль/убыток сделки
     */
    fun updateDailyPnL(pnl: BigDecimal) {
        resetDailyStateIfNewDay()
        dailyPnL = dailyPnL.add(pnl)
        if (dailyPnL < maxDrawdownToday) maxDrawdownToday = dailyPnL
        if (dailyPnL <= riskConfig.maxDailyLossRub.negate()) {
            dailyLossLimitReached = true
            logger.error { "DAILY LOSS LIMIT reached: dailyPnL=$dailyPnL <= -${riskConfig.maxDailyLossRub}" }
        }
        persistDailyState()
    }

    /**
     * Текущий дневной P&L (восстановленный из снапшота при смене дня/рестарте).
     *
     * @return накопленный дневной P&L
     */
    fun getDailyPnL(): BigDecimal {
        resetDailyStateIfNewDay()
        return dailyPnL
    }

    /**
     * Смена календарного дня (МСК) → сброс дневного состояния.
     * При рестарте в течение дня восстанавливает значения из БД.
     */
    private fun resetDailyStateIfNewDay() {
        val today = LocalDate.now(moscowZone)
        if (lastTradingDate == today) return
        lastTradingDate = today
        loadDailyState(today)
    }

    private fun loadDailyState(today: LocalDate) {
        val snapshot =
            try {
                dailyRiskSnapshotRepo.findByDate(today)
            } catch (e: Exception) {
                logger.warn(e) { "Daily risk snapshot load failed" }
                null
            }
        dailyPnL = snapshot?.dailyPnl ?: BigDecimal.ZERO
        dailyLossLimitReached = snapshot?.limitReached ?: false
        maxDrawdownToday = snapshot?.maxDrawdownToday ?: BigDecimal.ZERO
        logger.info { "Daily risk state for $today: dailyPnL=$dailyPnL limitReached=$dailyLossLimitReached" }
    }

    private fun persistDailyState() {
        try {
            dailyRiskSnapshotRepo.upsert(lastTradingDate, dailyPnL, dailyLossLimitReached, maxDrawdownToday)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to persist daily risk snapshot" }
        }
    }
}
