package com.trading.bot.backtest

import com.trading.bot.application.StrategySelector
import com.trading.bot.application.strategy.BreakoutStrategy
import com.trading.bot.application.strategy.CnyRubStrategy
import com.trading.bot.application.strategy.GridStrategy
import com.trading.bot.application.strategy.MeanReversionStrategy
import com.trading.bot.application.strategy.OnlineMlDirectionStrategy
import com.trading.bot.application.strategy.ScalpingStrategy
import com.trading.bot.application.strategy.TrendFollowingStrategy
import com.trading.bot.config.BacktestConfig
import com.trading.bot.domain.risk.PerTickerRegime
import com.trading.bot.domain.risk.RegimeDetectionConfig
import com.trading.bot.domain.risk.RegimeDetector
import com.trading.bot.domain.strategy.Strategy
import com.trading.bot.domain.strategy.StrategyContext
import com.trading.bot.domain.technical.CandleResampler
import com.trading.bot.domain.technical.IndicatorCalculator
import com.trading.bot.model.StrategyAction
import com.trading.bot.model.dto.MarketSnapshot
import com.trading.bot.model.entity.Candle
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Research-фильтры входа (docs/17 этап 4c, pt.2, 2026-09-20). Аналог funding-veto/
 * ml-direction null-override: query-параметры перекрывают `bt.*`, null → конфиг.
 */
data class EntryFilterOverrides(
    val sessionFilterEnabled: Boolean? = null,
    val sessionFilterStartMinutes: Int? = null,
    val sessionFilterEndMinutes: Int? = null,
    val pullbackFilterEnabled: Boolean? = null,
    val pullbackEmaPeriod: Int? = null,
    val pullbackMaxDeviationPercent: Double? = null,
    val pullbackBlockOnUnknown: Boolean? = null,
    val orbEnabled: Boolean? = null,
    val orbWindowBars: Int? = null,
    val orbStrictBreakout: Boolean? = null,
    val orbBlockOnUnknown: Boolean? = null,
    val orbWindowStartMinutes: Int? = null,
    val orbWindowEndMinutes: Int? = null,
    val vwapMrEnabled: Boolean? = null,
    val vwapMrDeviationSigma: Double? = null,
    val vwapMrMaxAdx: Double? = null,
    val vwapMrTimeframe: String? = null,
    val vwapMrMinSessionBars: Int? = null,
    val vwapMrBlockOnUnknown: Boolean? = null,
    val timeDirectionEnabled: Boolean? = null,
    val timeDirectionLongBlockUntilHour: Int? = null,
    val timeDirectionShortBlockStartHour: Int? = null,
    val timeDirectionShortBlockEndHour: Int? = null,
    val squeezeEnabled: Boolean? = null,
    val squeezeBlockOnUnknown: Boolean? = null,
    val panicReversalEnabled: Boolean? = null,
    val panicMinSessionDropPercent: Double? = null,
    val panicRsiPeriod: Int? = null,
    val panicMaxRsi: Double? = null,
    val panicTimeframe: String? = null,
    val panicRequireBullishBar: Boolean? = null,
    val panicMinBars: Int? = null,
    val panicBlockOnUnknown: Boolean? = null,
    val macroTrendEnabled: Boolean? = null,
    val macroTrendTimeframe: String? = null,
    val macroTrendFastEma: Int? = null,
    val macroTrendSlowEma: Int? = null,
    val macroTrendMinHigherBars: Int? = null,
    val macroTrendPullbackEmaPeriod: Int? = null,
    val macroTrendMaxDeviationPercent: Double? = null,
    val macroTrendBlockOnUnknown: Boolean? = null,
) {
    val anyProvided: Boolean
        get() =
            sessionFilterEnabled != null ||
                sessionFilterStartMinutes != null ||
                sessionFilterEndMinutes != null ||
                pullbackFilterEnabled != null ||
                pullbackEmaPeriod != null ||
                pullbackMaxDeviationPercent != null ||
                pullbackBlockOnUnknown != null ||
                orbEnabled != null ||
                orbWindowBars != null ||
                orbStrictBreakout != null ||
                orbBlockOnUnknown != null ||
                orbWindowStartMinutes != null ||
                orbWindowEndMinutes != null ||
                vwapMrEnabled != null ||
                vwapMrDeviationSigma != null ||
                vwapMrMaxAdx != null ||
                vwapMrTimeframe != null ||
                vwapMrMinSessionBars != null ||
                vwapMrBlockOnUnknown != null ||
                timeDirectionEnabled != null ||
                timeDirectionLongBlockUntilHour != null ||
                timeDirectionShortBlockStartHour != null ||
                timeDirectionShortBlockEndHour != null ||
                squeezeEnabled != null ||
                squeezeBlockOnUnknown != null ||
                panicReversalEnabled != null ||
                panicMinSessionDropPercent != null ||
                panicRsiPeriod != null ||
                panicMaxRsi != null ||
                panicTimeframe != null ||
                panicRequireBullishBar != null ||
                panicMinBars != null ||
                panicBlockOnUnknown != null ||
                macroTrendEnabled != null ||
                macroTrendTimeframe != null ||
                macroTrendFastEma != null ||
                macroTrendSlowEma != null ||
                macroTrendMinHigherBars != null ||
                macroTrendPullbackEmaPeriod != null ||
                macroTrendMaxDeviationPercent != null ||
                macroTrendBlockOnUnknown != null
}

/**
 * Входные фильтры (research, pt.2): session-фильтр (вход только в определённые
 * фазы торговой сессии) и pullback-фильтр (вход только в полосе отката к EMA —
 * не гнаться за ценой). Fail-closed по нехватке данных — на выбор оператора.
 */
class EntryFilters(
    val sessionEnabled: Boolean,
    val sessionStartMinutes: Int,
    val sessionEndMinutes: Int,
    val pullbackEnabled: Boolean,
    val pullbackEmaPeriod: Int,
    val pullbackMaxDeviationPercent: Double,
    val pullbackBlockOnUnknown: Boolean,
    val orbEnabled: Boolean,
    val orbWindowBars: Int,
    val orbStrictBreakout: Boolean,
    val orbBlockOnUnknown: Boolean,
    val orbWindowStartMinutes: Int,
    val orbWindowEndMinutes: Int,
    val vwapMrEnabled: Boolean,
    val vwapMrDeviationSigma: Double,
    val vwapMrMaxAdx: Double,
    val vwapMrTimeframe: String,
    val vwapMrMinSessionBars: Int,
    val vwapMrBlockOnUnknown: Boolean,
    val timeDirectionEnabled: Boolean,
    val timeDirectionLongBlockUntilHour: Int,
    val timeDirectionShortBlockStartHour: Int,
    val timeDirectionShortBlockEndHour: Int,
    val squeezeEnabled: Boolean,
    val squeezeBlockOnUnknown: Boolean,
    val panicReversalEnabled: Boolean,
    val panicMinSessionDropPercent: Double,
    val panicRsiPeriod: Int,
    val panicMaxRsi: Double,
    val panicTimeframe: String,
    val panicRequireBullishBar: Boolean,
    val panicMinBars: Int,
    val panicBlockOnUnknown: Boolean,
    val macroTrendEnabled: Boolean,
    val macroTrendTimeframe: String,
    val macroTrendFastEma: Int,
    val macroTrendSlowEma: Int,
    val macroTrendMinHigherBars: Int,
    val macroTrendPullbackEmaPeriod: Int,
    val macroTrendMaxDeviationPercent: Double,
    val macroTrendBlockOnUnknown: Boolean,
) {
    companion object {
        /** Минут в сутках — [EntryFilters.orbWindowEndMinutes] ≥ этого значения
         *  означает «окно не сужено» (вход разрешён сразу после закрытия диапазона). */
        private const val MINUTES_PER_DAY = 1440

        fun from(
            config: BacktestConfig,
            overrides: EntryFilterOverrides? = null,
        ): EntryFilters =
            EntryFilters(
                sessionEnabled = overrides?.sessionFilterEnabled ?: config.sessionFilterEnabled,
                sessionStartMinutes =
                    overrides?.sessionFilterStartMinutes ?: config.sessionFilterStartMinutes,
                sessionEndMinutes =
                    overrides?.sessionFilterEndMinutes ?: config.sessionFilterEndMinutes,
                pullbackEnabled = overrides?.pullbackFilterEnabled ?: config.pullbackFilterEnabled,
                pullbackEmaPeriod = overrides?.pullbackEmaPeriod ?: config.pullbackEmaPeriod,
                pullbackMaxDeviationPercent =
                    overrides?.pullbackMaxDeviationPercent ?: config.pullbackMaxDeviationPercent,
                pullbackBlockOnUnknown =
                    overrides?.pullbackBlockOnUnknown ?: config.pullbackBlockOnUnknown,
                orbEnabled = overrides?.orbEnabled ?: config.orbEnabled,
                orbWindowBars = overrides?.orbWindowBars ?: config.orbWindowBars,
                orbStrictBreakout = overrides?.orbStrictBreakout ?: config.orbStrictBreakout,
                orbBlockOnUnknown =
                    overrides?.orbBlockOnUnknown ?: config.orbBlockOnUnknown,
                orbWindowStartMinutes =
                    overrides?.orbWindowStartMinutes ?: config.orbWindowStartMinutes,
                orbWindowEndMinutes =
                    overrides?.orbWindowEndMinutes ?: config.orbWindowEndMinutes,
                vwapMrEnabled = overrides?.vwapMrEnabled ?: config.vwapMrEnabled,
                vwapMrDeviationSigma =
                    overrides?.vwapMrDeviationSigma ?: config.vwapMrDeviationSigma,
                vwapMrMaxAdx = overrides?.vwapMrMaxAdx ?: config.vwapMrMaxAdx,
                vwapMrTimeframe = overrides?.vwapMrTimeframe ?: config.vwapMrTimeframe,
                vwapMrMinSessionBars =
                    overrides?.vwapMrMinSessionBars ?: config.vwapMrMinSessionBars,
                vwapMrBlockOnUnknown =
                    overrides?.vwapMrBlockOnUnknown ?: config.vwapMrBlockOnUnknown,
                timeDirectionEnabled =
                    overrides?.timeDirectionEnabled ?: config.timeDirectionEnabled,
                timeDirectionLongBlockUntilHour =
                    overrides?.timeDirectionLongBlockUntilHour ?: config.timeDirectionLongBlockUntilHour,
                timeDirectionShortBlockStartHour =
                    overrides?.timeDirectionShortBlockStartHour ?: config.timeDirectionShortBlockStartHour,
                timeDirectionShortBlockEndHour =
                    overrides?.timeDirectionShortBlockEndHour ?: config.timeDirectionShortBlockEndHour,
                squeezeEnabled = overrides?.squeezeEnabled ?: config.squeezeEnabled,
                squeezeBlockOnUnknown = overrides?.squeezeBlockOnUnknown ?: config.squeezeBlockOnUnknown,
                panicReversalEnabled = overrides?.panicReversalEnabled ?: config.panicReversalEnabled,
                panicMinSessionDropPercent =
                    overrides?.panicMinSessionDropPercent ?: config.panicMinSessionDropPercent,
                panicRsiPeriod = overrides?.panicRsiPeriod ?: config.panicRsiPeriod,
                panicMaxRsi = overrides?.panicMaxRsi ?: config.panicMaxRsi,
                panicTimeframe = overrides?.panicTimeframe ?: config.panicTimeframe,
                panicRequireBullishBar =
                    overrides?.panicRequireBullishBar ?: config.panicRequireBullishBar,
                panicMinBars = overrides?.panicMinBars ?: config.panicMinBars,
                panicBlockOnUnknown = overrides?.panicBlockOnUnknown ?: config.panicBlockOnUnknown,
                macroTrendEnabled = overrides?.macroTrendEnabled ?: config.macroTrendEnabled,
                macroTrendTimeframe = overrides?.macroTrendTimeframe ?: config.macroTrendTimeframe,
                macroTrendFastEma = overrides?.macroTrendFastEma ?: config.macroTrendFastEma,
                macroTrendSlowEma = overrides?.macroTrendSlowEma ?: config.macroTrendSlowEma,
                macroTrendMinHigherBars =
                    overrides?.macroTrendMinHigherBars ?: config.macroTrendMinHigherBars,
                macroTrendPullbackEmaPeriod =
                    overrides?.macroTrendPullbackEmaPeriod ?: config.macroTrendPullbackEmaPeriod,
                macroTrendMaxDeviationPercent =
                    overrides?.macroTrendMaxDeviationPercent ?: config.macroTrendMaxDeviationPercent,
                macroTrendBlockOnUnknown =
                    overrides?.macroTrendBlockOnUnknown ?: config.macroTrendBlockOnUnknown,
            )

        /** Фильтры выключены — не влияют на входы. */
        val PASS_THROUGH =
            EntryFilters(
                sessionEnabled = false,
                sessionStartMinutes = 600,
                sessionEndMinutes = 1080,
                pullbackEnabled = false,
                pullbackEmaPeriod = 20,
                pullbackMaxDeviationPercent = 1.0,
                pullbackBlockOnUnknown = false,
                orbEnabled = false,
                orbWindowBars = 6,
                orbStrictBreakout = true,
                orbBlockOnUnknown = false,
                orbWindowStartMinutes = 0,
                orbWindowEndMinutes = 1440,
                vwapMrEnabled = false,
                vwapMrDeviationSigma = 1.5,
                vwapMrMaxAdx = 25.0,
                vwapMrTimeframe = "HOUR_1",
                vwapMrMinSessionBars = 6,
                vwapMrBlockOnUnknown = true,
                timeDirectionEnabled = false,
                timeDirectionLongBlockUntilHour = 11,
                timeDirectionShortBlockStartHour = 13,
                timeDirectionShortBlockEndHour = 16,
                squeezeEnabled = false,
                squeezeBlockOnUnknown = true,
                panicReversalEnabled = false,
                panicMinSessionDropPercent = 3.0,
                panicRsiPeriod = 14,
                panicMaxRsi = 25.0,
                panicTimeframe = "HOUR_1",
                panicRequireBullishBar = true,
                panicMinBars = 20,
                panicBlockOnUnknown = true,
                macroTrendEnabled = false,
                macroTrendTimeframe = "HOUR_1",
                macroTrendFastEma = 20,
                macroTrendSlowEma = 50,
                macroTrendMinHigherBars = 60,
                macroTrendPullbackEmaPeriod = 20,
                macroTrendMaxDeviationPercent = 0.5,
                macroTrendBlockOnUnknown = true,
            )
    }

    /** true → вход в бар [barTime] блокирован (вне окна сессии / вне полосы отката). */
    fun blocksEntry(
        barTime: LocalTime,
        close: BigDecimal,
        closes: List<BigDecimal>,
    ): Boolean {
        if (sessionEnabled) {
            val minutes = barTime.hour * 60 + barTime.minute
            if (minutes < sessionStartMinutes || minutes > sessionEndMinutes) return true
        }
        if (pullbackEnabled) {
            if (closes.size < pullbackEmaPeriod) return pullbackBlockOnUnknown
            val ema = IndicatorCalculator.ema(closes, pullbackEmaPeriod).last()
            if (!ema.isFinite() || ema <= 0) return true
            val deviation = Math.abs(close.toDouble() - ema) / ema * 100.0
            if (deviation > pullbackMaxDeviationPercent) return true
        }
        return false
    }

    /**
     * Opening Range Breakout фильтр направления (research, pt.3).
     *
     * Диапазон = High/Low [orbWindowBars] баров текущего торгового дня, начиная
     * с первого бара с временем ≥ [orbWindowStartMinutes] (минуты от полуночи).
     * Пробой проверяется на барах, где выполнены оба условия:
     *  - диапазон закрыт (последний бар диапазона уже есть в истории);
     *  - время бара ≥ [orbWindowEndMinutes] (вход разрешён только после закрытия
     *    окна). При неп суженном окне (0..1440, дефолт) второе условие всегда
     *    выполнено — это исходное поведение ORB: диапазон = первые бары дня,
     *    пробой проверяется весь день.
     *
     * Возврат:
     *  - [StrategyAction.BUY] — close пробил верх диапазона (разрешён LONG);
     *  - [StrategyAction.SELL] — close пробил низ диапазона (разрешён SHORT);
     *  - [StrategyAction.HOLD] — вход заблокирован: close внутри диапазона при
     *    [orbStrictBreakout], либо диапазон undefined при [orbBlockOnUnknown];
     *  - null — фильтр не блокирует: до начала окна, внутри окна (диапазон ещё
     *    не закрыт) при [orbBlockOnUnknown]=false, либо close внутри диапазона
     *    при [orbStrictBreakout]=false.
     *
     * undefined (данных не хватает: день в истории начинается позже окна, либо
     * окно не содержит [orbWindowBars] баров) → HOLD при [orbBlockOnUnknown]
     * (fail-closed), иначе null.
     *
     * Стратегия «ORB на золоте» (research 2026-09-26): окно 15:30–16:00 МСК
     * ([orbWindowStartMinutes]=930, [orbWindowEndMinutes]=960, [orbWindowBars]=3
     * для MINUTE_10), вход на пробое после 16:00.
     */
    fun orbDirection(
        candles: List<Candle>,
        index: Int,
    ): StrategyAction? {
        if (!orbEnabled || index <= 0) return null
        val bar = candles[index]
        val barMinutes = bar.time.toLocalTime().let { it.hour * 60 + it.minute }
        // До начала окна ORB не применяется (утренние бары не фильтруются).
        if (barMinutes < orbWindowStartMinutes) return null
        // Собираем бары текущего торгового дня назад от index, пока дата совпадает
        // с датой бара. Начало диапазона — первый бар дня в окне.
        var dayStart = index
        val day = bar.time.toLocalDate()
        while (dayStart > 0 && candles[dayStart - 1].time.toLocalDate() == day) {
            dayStart--
        }
        val rangeStart =
            (dayStart..index).firstOrNull {
                val m = candles[it].time.toLocalTime()
                m.hour * 60 + m.minute >= orbWindowStartMinutes
            }
        if (rangeStart == null || rangeStart + orbWindowBars - 1 >= index) {
            // Диапазон ещё формируется (включая его последний бар — пробой
            // проверяется только на следующих барах) или день начинается не с
            // начала данных — undefined.
            return if (orbBlockOnUnknown) StrategyAction.HOLD else null
        }
        if (orbWindowEndMinutes < MINUTES_PER_DAY && barMinutes < orbWindowEndMinutes) {
            // Суженное окно: диапазон ещё не закрыт — направление неизвестно.
            return if (orbBlockOnUnknown) StrategyAction.HOLD else null
        }
        val rangeBars = candles.subList(rangeStart, rangeStart + orbWindowBars)
        val rangeHigh =
            rangeBars.maxOfOrNull { it.highPrice } ?: return if (orbBlockOnUnknown) StrategyAction.HOLD else null
        val rangeLow =
            rangeBars.minOfOrNull { it.lowPrice } ?: return if (orbBlockOnUnknown) StrategyAction.HOLD else null
        return when {
            bar.closePrice > rangeHigh -> StrategyAction.BUY
            bar.closePrice < rangeLow -> StrategyAction.SELL
            orbStrictBreakout -> StrategyAction.HOLD
            else -> null
        }
    }

    /**
     * VWAP mean reversion фильтр направления (research, стратегия №1
     * «Контртрендовый отскок от VWAP», 2026-09-26).
     *
     * Логика: контрттрендовый вход разрешён ТОЛЬКО при отклонении цены от
     * сессионного VWAP ≥ [vwapMrDeviationSigma]σ (σ — стандартное отклонение
     * typical price в сессии) и при низком тренде ADX([vwapMrTimeframe]) ≤
     * [vwapMrMaxAdx].
     *  - цена ниже VWAP на ≥ Nσ → ожидаем возврат вверх → [StrategyAction.BUY];
     *  - цена выше VWAP на ≥ Nσ → ожидаем возврат вниз → [StrategyAction.SELL];
     *  - отклонение < Nσ → входа нет → [StrategyAction.HOLD];
     *  - ADX выше порога (сильный тренд) → MR не работает → HOLD.
     *
     * @param window бары текущей сессии (текущий день) до и включая текущий бар
     * @param higherTimeframeCandles завершённые бары старшего ТФ для ADX
     * @return HOLD — вход заблокирован, BUY/SELL — разрешённая сторона,
     *   null — фильтр off либо undefined при [vwapMrBlockOnUnknown]=false
     */
    fun vwapMrDirection(
        window: List<Candle>,
        higherTimeframeCandles: List<Candle>,
    ): StrategyAction? {
        if (!vwapMrEnabled || window.isEmpty()) return null
        val vwap = IndicatorCalculator.vwap(window)
        val sigmaPercent = IndicatorCalculator.vwapStdDevPercent(window)
        if (vwap == null ||
            sigmaPercent == null ||
            sigmaPercent < IndicatorCalculator.MIN_MEANINGFUL_VWAP_SIGMA_PERCENT
        ) {
            // Сессия безвольная (σ ≈ 0) — отклонение не определено.
            return if (vwapMrBlockOnUnknown) StrategyAction.HOLD else null
        }
        if (window.size < vwapMrMinSessionBars) {
            // Сессия ещё не набрала минимум баров для оценки σ.
            return if (vwapMrBlockOnUnknown) StrategyAction.HOLD else null
        }
        val adx = IndicatorCalculator.adx(higherTimeframeCandles)
        if (adx > vwapMrMaxAdx) return StrategyAction.HOLD
        val close = window.last().closePrice.toDouble()
        val sigmaValue = vwap * sigmaPercent / 100.0
        val deviation = (close - vwap) / sigmaValue
        return when {
            deviation <= -vwapMrDeviationSigma -> StrategyAction.BUY
            deviation >= vwapMrDeviationSigma -> StrategyAction.SELL
            else -> StrategyAction.HOLD
        }
    }

    /**
     * Bollinger Squeeze Breakout (research, стратегия №8, 2026-09-26).
     *
     * Сжатие = обе полосы Боллинджера внутри канала Кельтнера
     * ([IndicatorCalculator.isSqueeze]). Вход разрешён только когда сжатие
     * закончилось И текущий закрыл вышел за полосу Боллинджера:
     *  - close > BB(upper) → [StrategyAction.BUY];
     *  - close < BB(lower) → [StrategyAction.SELL];
     *  - сжатие продолжается / цена внутри полос → [StrategyAction.HOLD].
     *
     * @param window бары до и включая текущий (базовый ТФ)
     * @return HOLD — вход заблокирован; BUY/SELL — разрешённая сторона;
     *   null — фильтр off либо undefined при [squeezeBlockOnUnknown]=false
     */
    fun squeezeDirection(window: List<Candle>): StrategyAction? {
        if (!squeezeEnabled || window.isEmpty()) return null
        val squeeze = IndicatorCalculator.isSqueeze(window)
        if (squeeze == null) {
            return if (squeezeBlockOnUnknown) StrategyAction.HOLD else null
        }
        // Внутри сжатия входа нет — ждём выхода из него.
        if (squeeze) return StrategyAction.HOLD
        val closes = window.map { it.closePrice }
        val (_, bbUpper, bbLower) =
            IndicatorCalculator.bollinger(
                closes,
                IndicatorCalculator.BOLLINGER_SQUEEZE_PERIOD,
                IndicatorCalculator.BOLLINGER_SQUEEZE_MULT,
            )
        val close = window.last().closePrice
        return when {
            close > bbUpper -> StrategyAction.BUY
            close < bbLower -> StrategyAction.SELL
            else -> StrategyAction.HOLD
        }
    }

    /**
     * Panic & Reversal (research, стратегия №7, 2026-09-26).
     *
     * Вход в LONG после паники: текущая сессия (день) просела на
     * ≥ [panicMinSessionDropPercent]% относительно открытия дня, RSI([panicRsiPeriod])
     * на [panicTimeframe] < [panicMaxRsi] (перепродажа) и текущий бар закрылся
     * выше открытия — подтверждение начала отскока.
     *
     * @param sessionBars бары текущего торгового дня до и включая текущий
     * @param higherCloses закрытия завершённых баров [panicTimeframe]
     * @return [StrategyAction.BUY] — вход разрешён; [StrategyAction.HOLD] —
     *   заблокирован; null — фильтр off либо undefined при
     *   [panicBlockOnUnknown]=false
     */
    fun panicReversalDirection(
        sessionBars: List<Candle>,
        higherCloses: List<BigDecimal>,
    ): StrategyAction? {
        if (!panicReversalEnabled || sessionBars.isEmpty()) return null
        val unknown = if (panicBlockOnUnknown) StrategyAction.HOLD else null
        val first = sessionBars.first()
        if (first.openPrice <= BigDecimal.ZERO) return unknown
        if (sessionBars.size < panicMinBars) return unknown
        if (higherCloses.size < panicRsiPeriod + 1) return unknown
        val last = sessionBars.last()
        if (panicRequireBullishBar && last.closePrice <= last.openPrice) return StrategyAction.HOLD
        val dropPercent = (last.closePrice.toDouble() - first.openPrice.toDouble()) / first.openPrice.toDouble() * 100.0
        if (dropPercent > -panicMinSessionDropPercent) return StrategyAction.HOLD
        val rsi = IndicatorCalculator.rsi(higherCloses, panicRsiPeriod)
        if (!rsi.isFinite() || rsi >= panicMaxRsi) return StrategyAction.HOLD
        return StrategyAction.BUY
    }

    /**
     * Macro-Trend Pullback (research, стратегия №1, 2026-09-26).
     *
     * Контекст: восходящий тренд по старшему ТФ —
     * EMA([macroTrendFastEma]) > EMA([macroTrendSlowEma]) на завершённых барах
     * [macroTrendTimeframe]. Точка входа: откат к EMA базового ТФ — расстояние
     * от EMA([macroTrendPullbackEmaPeriod]) ≤ [macroTrendMaxDeviationPercent]%.
     * Разрешена только сторона BUY (пробой вниз в растущем тренде — не вход).
     *
     * @return [StrategyAction.BUY] — вход разрешён; [StrategyAction.HOLD] —
     *   заблокирован; null — фильтр off либо undefined при
     *   [macroTrendBlockOnUnknown]=false
     */
    fun macroTrendDirection(
        higherCloses: List<BigDecimal>,
        baseCloses: List<BigDecimal>,
    ): StrategyAction? {
        if (!macroTrendEnabled || baseCloses.isEmpty()) return null
        val unknown = if (macroTrendBlockOnUnknown) StrategyAction.HOLD else null
        if (higherCloses.size < macroTrendMinHigherBars) return unknown
        if (baseCloses.size < macroTrendPullbackEmaPeriod) return unknown
        val fast = IndicatorCalculator.ema(higherCloses, macroTrendFastEma).last()
        val slow = IndicatorCalculator.ema(higherCloses, macroTrendSlowEma).last()
        if (!fast.isFinite() || !slow.isFinite()) return unknown
        // Тренд вверх: EMA20 > EMA50. Иначе (вниз/боковик) — вход заблокирован.
        if (fast <= slow) return StrategyAction.HOLD
        val ema = IndicatorCalculator.ema(baseCloses, macroTrendPullbackEmaPeriod).last()
        if (!ema.isFinite() || ema <= 0.0) return unknown
        val close = baseCloses.last().toDouble()
        val deviation = Math.abs(close - ema) / ema * 100.0
        if (deviation > macroTrendMaxDeviationPercent) return StrategyAction.HOLD
        return StrategyAction.BUY
    }

    /**
     * Time×direction фильтр (research, pt.4): блокирует LONG в утренние часы
     * (≤ [timeDirectionLongBlockUntilHour]) и SHORT в дневное окно
     * ([timeDirectionShortBlockStartHour]..[timeDirectionShortBlockEndHour]).
     * Гипотеза из декомпозиции сделок CNYRUBF (2026-09-23, 730д IS): утренние
     * LONG (avg −21.3, TP 1/15) и дневные SHORT (avg −35.8, TP 0/10) генерируют
     * основную часть убытка. true → данный вход [action] в бар [barTime] блокирован.
     */
    fun blocksDirection(
        barTime: LocalTime,
        action: StrategyAction,
    ): Boolean {
        if (!timeDirectionEnabled) return false
        val hour = barTime.hour
        return when (action) {
            StrategyAction.BUY -> hour <= timeDirectionLongBlockUntilHour
            StrategyAction.SELL -> hour in timeDirectionShortBlockStartHour..timeDirectionShortBlockEndHour
            else -> false
        }
    }
}

/**
 * Backtest signal generator that mirrors the LIVE strategy pipeline.
 *
 * Uses the same deterministic strategies as LIVE (StrategyRunner):
 * TrendFollowing, Breakout, Scalping, MeanReversion, Grid, CnyRubStrategy.
 * The winner is selected by maximum weighted signal strength, same as LIVE
 * (ties broken by registration order — deterministic).
 *
 * Regime parity (P0#1): when [regimeConfig] is non-null, the generator
 * mirrors [com.trading.bot.application.StrategyRunner.runAll] behaviour:
 *   1. Detect per-ticker regime via [RegimeDetector];
 *   2. If regime blocks entry (incl. UNKNOWN due to insufficient data — fail-closed)
 *      → HOLD;
 *   3. Filter strategies by [StrategySelector.eligibleStrategyIds];
 *   4. Weight signalStrength by [StrategySelector.fitScore].
 * When [regimeConfig] is null, regime is not applied (legacy pass-through).
 *
 * Adaptive confidence gate (P0#2): mirrors [com.trading.bot.service.StrategyService]
 * adaptive threshold. Signals with strength below [adaptiveConfidenceThreshold]
 * (or non-finite, e.g. NaN) are gated to HOLD.
 *
 * This ensures signal parity: backtest tests the same strategy decisions
 * that would fire in LIVE trading, not a simplified heuristic.
 *
 * Не Spring-бин: инстанцируется напрямую ([BacktestSignalGeneratorConfig] при
 * `bt.agent.live-strategies=true`). Все стратегии создаются локально на каждый
 * вызов сигнала не нужны — они stateless, поэтому список создаётся один раз.
 * Детерминирован по `candles[0..index]`: никаких LLM, часов или внешних данных.
 *
 * [mlDirection] — исследовательский онлайн-фильтр НАПРАВЛЕНИЯ (bt.ml-direction-enabled).
 * НЕ конкурирует за сигнал: обучается на каждом баре (онлайн-LR, без lookahead,
 * сброс модели по cycleId на каждый simulate — изоляция фолдов/MC) и применяется
 * к победителю среди детерминированных стратегий следующим образом:
 *   - если ML-направление уверенное и совпадает с направлением победителя — вход;
 *   - если ML-направление уверенное и ПРОТИВОПОЛОЖНО победителю — вход заблокирован
 *     (причина `ML_DIRECTION_VETO`);
 *   - если ML HOLD (нехватка данных/warmup/нет уверенности) — зависит от
 *     [mlDirectionBlockOnUnknown] (true: fail-closed блок, false: пропуск).
 */
class LiveStrategyBacktestSignalGenerator(
    private val regimeConfig: RegimeDetectionConfig? = null,
    private val adaptiveConfidenceThreshold: Double = 0.60,
    private val mlDirection: OnlineMlDirectionStrategy? = null,
    private val mlDirectionBlockOnUnknown: Boolean = false,
    private val entryFilters: EntryFilters? = null,
) : BacktestSignalGenerator {
    private val strategies: List<Strategy> =
        listOf(
            TrendFollowingStrategy(),
            BreakoutStrategy(),
            ScalpingStrategy(),
            MeanReversionStrategy(),
            GridStrategy(),
            // Микроструктура (bid/ask/OBI) в бэктесте отсутствует — стратегия
            // детерминированно падает в fallback-режим чистого mean-reversion.
            CnyRubStrategy(),
        )

    private val strategySelector = StrategySelector()

    override suspend fun signal(
        ticker: String,
        candles: List<Candle>,
        index: Int,
        minBars: Int,
        cycleId: String,
    ): StrategyAction {
        if (index < minBars) return StrategyAction.HOLD

        val window = candles.subList(0, index + 1)
        val indicators = IndicatorCalculator.calculate(window)
        val bar = candles[index]

        val snapshot =
            MarketSnapshot(
                ticker = ticker,
                currentPrice = bar.closePrice,
                volume = bar.volume,
                timestamp = bar.time.atZone(ZoneId.systemDefault()).toInstant(),
            )

        val regime: PerTickerRegime? =
            regimeConfig?.let { RegimeDetector.detect(window, it) }

        if (regime != null && regime.blocksEntry) return StrategyAction.HOLD

        val eligibleIds = regime?.let { strategySelector.eligibleStrategyIds(it) }

        val context =
            StrategyContext(
                ticker = ticker,
                snapshot = snapshot,
                candles = window,
                indicators = indicators,
                cycleId = cycleId,
                regime = regime,
            )

        // ML-фильтр обучается на каждом баре (онлайн-LR без lookahead), даже если
        // в этом баре ни одна стратегия не дала сигнала.
        val mlDecision =
            mlDirection?.let {
                try {
                    it.evaluate(context)
                } catch (_: Exception) {
                    null
                }
            }

        var bestAction = StrategyAction.HOLD
        var bestStrength = 0.0

        for (strategy in strategies) {
            if (eligibleIds != null && strategy.id !in eligibleIds) continue
            val decision =
                try {
                    strategy.evaluate(context)
                } catch (_: Exception) {
                    continue
                }
            if (decision.action != StrategyAction.HOLD) {
                val strength =
                    if (regime != null) {
                        val fit = strategySelector.fitScore(strategy.id, regime)
                        (decision.signalStrength * fit).coerceIn(0.0, 1.0)
                    } else {
                        decision.signalStrength
                    }
                if (strength > bestStrength) {
                    bestAction = decision.action
                    bestStrength = strength
                }
            }
        }

        if (bestAction == StrategyAction.HOLD) return StrategyAction.HOLD

        if (!bestStrength.isFinite() || bestStrength < adaptiveConfidenceThreshold) {
            return StrategyAction.HOLD
        }

        // Research-фильтры входа (docs/17 этап 4c): session (вход только в фазы
        // сессии) и pullback к EMA (не гнаться за ценой). Fail-closed по выбору
        // оператора; по умолчанию off.
        val entryBlocked =
            entryFilters?.let {
                try {
                    it.blocksEntry(
                        bar.time.toLocalTime(),
                        bar.closePrice,
                        window.map { c -> c.closePrice },
                    )
                } catch (_: Exception) {
                    false
                }
            } ?: false
        if (entryBlocked) return StrategyAction.HOLD

        // Bollinger Squeeze Breakout (research, стратегия №8): вход только на
        // пробое наружу из сжатия волатильности.
        entryFilters?.let { filters ->
            val sq =
                try {
                    filters.squeezeDirection(window)
                } catch (_: Exception) {
                    null
                }
            if (sq == StrategyAction.HOLD) return StrategyAction.HOLD
            if (sq != null && sq != bestAction) return StrategyAction.HOLD
        }

        // Panic & Reversal (research, стратегия №7): LONG после падения сессии
        // при перепродаже по RSI старшего ТФ и подтверждающем баре-отскоке.
        entryFilters?.let { filters ->
            if (filters.panicReversalEnabled) {
                val pr =
                    try {
                        val session = sessionBars(candles, index)
                        val higher =
                            CandleResampler.resample(
                                higherTimeframeLookback(
                                    candles,
                                    index,
                                    filters.panicTimeframe,
                                    higherTfBarsNeeded(filters.panicRsiPeriod + 1),
                                ),
                                filters.panicTimeframe,
                                completedBefore = bar.time,
                            )
                        filters.panicReversalDirection(session, higher.map { it.closePrice })
                    } catch (_: Exception) {
                        null
                    }
                if (pr == StrategyAction.HOLD) return StrategyAction.HOLD
                if (pr != null && pr != bestAction) return StrategyAction.HOLD
            }
        }

        // Macro-Trend Pullback (research, стратегия №1): LONG на откате в
        // восходящем тренде старшего ТФ.
        entryFilters?.let { filters ->
            if (filters.macroTrendEnabled) {
                val mt =
                    try {
                        val higher =
                            CandleResampler.resample(
                                higherTimeframeLookback(
                                    candles,
                                    index,
                                    filters.macroTrendTimeframe,
                                    higherTfBarsNeeded(filters.macroTrendMinHigherBars),
                                ),
                                filters.macroTrendTimeframe,
                                completedBefore = bar.time,
                            )
                        filters.macroTrendDirection(
                            higher.map { it.closePrice },
                            window.map { it.closePrice },
                        )
                    } catch (_: Exception) {
                        null
                    }
                if (mt == StrategyAction.HOLD) return StrategyAction.HOLD
                if (mt != null && mt != bestAction) return StrategyAction.HOLD
            }
        }

        // Opening Range Breakout (research, pt.3): вход разрешён только в направлении
        // пробоя дневного opening range. HOLD — вход заблокирован, BUY/SELL — только
        // эта сторона разрешена, null — фильтр off/пропуск.
        entryFilters?.let { filters ->
            val orb =
                try {
                    filters.orbDirection(candles, index)
                } catch (_: Exception) {
                    null
                }
            if (orb == StrategyAction.HOLD) return StrategyAction.HOLD
            if (orb != null && orb != bestAction) return StrategyAction.HOLD
        }

        // VWAP mean reversion (research, стратегия №1): контрттрендовый вход
        // только при отклонении ≥ Nσ от сессионного VWAP и низком ADX старшего
        // ТФ. HOLD — вход заблокирован, BUY/SELL — разрешённая сторона.
        entryFilters?.let { filters ->
            if (filters.vwapMrEnabled) {
                val mr =
                    try {
                        val session = sessionBars(candles, index)
                        val higher =
                            CandleResampler.resample(
                                higherTimeframeLookback(candles, index, filters.vwapMrTimeframe),
                                filters.vwapMrTimeframe,
                                completedBefore = bar.time,
                            )
                        filters.vwapMrDirection(session, higher)
                    } catch (_: Exception) {
                        null
                    }
                if (mr == StrategyAction.HOLD) return StrategyAction.HOLD
                if (mr != null && mr != bestAction) return StrategyAction.HOLD
            }
        }

        // Time×direction фильтр (research, pt.4): блокирует LONG в утренние часы
        // и SHORT в дневное окно (см. декомпозицию сделок CNYRUBF 2026-09-23).
        entryFilters?.let { filters ->
            val tdBlocked =
                try {
                    filters.blocksDirection(bar.time.toLocalTime(), bestAction)
                } catch (_: Exception) {
                    false
                }
            if (tdBlocked) return StrategyAction.HOLD
        }

        // ML-фильтр направления: veto против направления победителя.
        val mlAction = mlDecision?.action ?: StrategyAction.HOLD
        return when {
            mlDecision == null -> {
                bestAction
            }

            mlAction == StrategyAction.HOLD -> {
                if (mlDirectionBlockOnUnknown) {
                    StrategyAction.HOLD
                } else {
                    bestAction
                }
            }

            mlAction != bestAction -> {
                StrategyAction.HOLD
            }

            else -> {
                bestAction
            }
        }
    }

    private companion object {
        /** Базовый таймфрейм ресемплинга (MINUTE_10) — бар на 10 минут. */
        private const val BASE_BAR_MINUTES = 10L

        /** Жёсткий потолок lookback для ADX старшего ТФ: ресемплинг всего
         *  префикса истории даёт O(n²) на длинных прогонах (46k свечей), а ADX
         *  нуждается лишь в десятках завершённых баров старшего ТФ. */
        private const val MAX_ADX_LOOKBACK_BARS = 600

        /** Минимум баров старшего ТФ для расчёта ADX: 2·period + запас. */
        private const val ADX_HIGHER_TF_BARS = 32

        /**
         * Срез свечей для расчёта индикатора старшего ТФ: столько базовых баров,
         * сколько нужно для [bars] завершённых баров старшего ТФ, но не больше
         * [MAX_ADX_LOOKBACK_BARS]. Неизвестный таймфрейм → пустой список
         * (индикатор не определён, вызывающий код решает fail-closed/пропуск).
         */
        fun higherTimeframeLookback(
            candles: List<Candle>,
            index: Int,
            timeframe: String,
            bars: Int = ADX_HIGHER_TF_BARS,
        ): List<Candle> {
            val duration =
                runCatching { CandleResampler.durationMinutes(timeframe) }.getOrNull()
                    ?: return emptyList()
            val barsPerBucket = (duration / BASE_BAR_MINUTES).coerceAtLeast(1L)
            val needed = (bars * barsPerBucket).coerceAtMost(MAX_ADX_LOOKBACK_BARS.toLong())
            val from = (index + 1 - needed).coerceAtLeast(0L)
            return candles.subList(from.toInt(), index + 1)
        }

        /** Запас 1.5× к требуемому числу баров старшего ТФ (warmup индикатора). */
        fun higherTfBarsNeeded(bars: Int): Int = (bars * 3 / 2).coerceAtLeast(bars)

        /**
         * Бары текущей торговой сессии (день) до и включая [index] — окно для
         * сессионного VWAP в фильтре VWAP-MR.
         */
        fun sessionBars(
            candles: List<Candle>,
            index: Int,
        ): List<Candle> {
            val day = candles[index].time.toLocalDate()
            var start = index
            while (start > 0 && candles[start - 1].time.toLocalDate() == day) {
                start--
            }
            return candles.subList(start, index + 1)
        }
    }
}
