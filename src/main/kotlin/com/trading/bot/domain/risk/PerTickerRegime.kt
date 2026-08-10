package com.trading.bot.domain.risk

/**
 * Многоосевая модель рыночного режима для отдельного инструмента.
 *
 * В отличие от [MarketRegime] (рыночный overlay по волатильности RVI), этот
 * режим описывает состояние конкретного тикера по трём независимым осям:
 *   - направление ([RegimeDirection]) — тренд вверх/вниз или боковик;
 *   - волатильность ([RegimeVolatility]) — перцентильный ранг ATR% тикера;
 *   - ликвидность ([RegimeLiquidity]) — перцентильный ранг объёма.
 * плюс событие ([MarketEvent]) — резкое направленное движение (Crash/Pump).
 *
 * Классификатор — [RegimeDetector]. Режим используется Strategy Selector'ом
 * (жёсткий фильтр + взвешивание уверенности) и риск-слоем (урезка размера,
 * блок входов при экстремальных состояниях).
 */
enum class RegimeDirection {
    TREND_UP,
    TREND_DOWN,
    RANGE,
}

enum class RegimeVolatility {
    LOW,
    NORMAL,
    HIGH,
    EXTREME,
}

enum class RegimeLiquidity {
    NORMAL,
    THIN,
}

enum class MarketEvent {
    NONE,
    CRASH,
    PUMP,
}

/**
 * Текущее состояние инструмента по осям [RegimeDirection] × [RegimeVolatility]
 * × [RegimeLiquidity] + [MarketEvent].
 */
data class PerTickerRegime(
    val direction: RegimeDirection,
    val volatility: RegimeVolatility,
    val liquidity: RegimeLiquidity,
    val event: MarketEvent,
) {
    /**
     * Блокирует ли режим новые входы. Crash/Pump (резкое направленное движение),
     * низкая ликвидность (THIN) и экстремальная волатильность (EXTREME) — входы
     * запрещены (стратегии не выбираются, сигнал не публикуется).
     */
    val blocksEntry: Boolean
        get() =
            event != MarketEvent.NONE ||
                liquidity == RegimeLiquidity.THIN ||
                volatility == RegimeVolatility.EXTREME

    /**
     * Множитель размера позиции: EXTREME/THIN/Crash/Pump — 0 (страховка), HIGH —
     * консервативная доля, иначе 1.
     */
    fun sizeMultiplier(): Double =
        when {
            blocksEntry -> 0.0
            volatility == RegimeVolatility.HIGH -> 0.5
            else -> 1.0
        }

    /** Человекочитаемое описание режима (для reasoning и логов). */
    fun describe(): String = "dir=$direction vol=$volatility liq=$liquidity event=$event"

    /** Причина блокировки входов (для метрик/логов), null если режим не блокирует. */
    fun blockReason(): String? =
        when {
            event == MarketEvent.CRASH -> "CRASH"
            event == MarketEvent.PUMP -> "PUMP"
            liquidity == RegimeLiquidity.THIN -> "LOW_LIQUIDITY"
            volatility == RegimeVolatility.EXTREME -> "HIGH_VOLATILITY"
            else -> null
        }

    /**
     * Кодированное числовое представление режима для gauge-метрики:
     * event×1000 + volatility×100 + direction×10 + liquidity.
     */
    fun encodedLevel(): Double = (event.ordinal * 1000 + volatility.ordinal * 100 + direction.ordinal * 10 + liquidity.ordinal).toDouble()

    companion object {
        /** Fail-safe режим при недостатке данных: нейтральный, не блокирует. */
        val UNKNOWN: PerTickerRegime =
            PerTickerRegime(
                direction = RegimeDirection.RANGE,
                volatility = RegimeVolatility.NORMAL,
                liquidity = RegimeLiquidity.NORMAL,
                event = MarketEvent.NONE,
            )
    }
}
