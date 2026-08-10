package com.trading.bot.domain.risk

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Направление опциона (call/put) в терминах Black-76.
 */
enum class OptionKind { CALL, PUT }

/**
 * Калькулятор Black-76 для опционов на фьючерсы (форвардная цена F).
 *
 * Цена:
 *   d1 = (ln(F/K) + σ²T/2) / (σ√T),  d2 = d1 - σ√T
 *   call = F·N(d1) - K·N(d2)
 *   put  = K·N(-d2) - F·N(-d1)
 *
 * [impliedVolatility] инвертирует цену бисекцией по σ на [0.0001, 5.0]
 * (до 500% годовой волатильности) с точностью ~1e-8.
 */
object Black76Calculator {
    private const val MIN_SIGMA = 1e-4
    private const val MAX_SIGMA = 5.0
    private const val MAX_ITERATIONS = 120
    private const val TOLERANCE = 1e-8

    /**
     * Теоретическая цена опциона Black-76.
     *
     * @param forward форвардная цена базового фьючерса (F)
     * @param strike страйк (K)
     * @param yearsToExpiry время до экспирации в годах (T)
     * @param kind направление опциона
     * @param sigma годовая волатильность в долях (0.25 = 25%)
     * @return цена опциона в единицах цены
     */
    fun price(
        forward: Double,
        strike: Double,
        yearsToExpiry: Double,
        kind: OptionKind,
        sigma: Double,
    ): Double {
        if (yearsToExpiry <= 0.0 || sigma <= 0.0) {
            return intrinsicValue(forward, strike, kind)
        }
        val volRootT = sigma * sqrt(yearsToExpiry)
        val d1 = (ln(forward / strike) + 0.5 * sigma * sigma * yearsToExpiry) / volRootT
        val d2 = d1 - volRootT
        return when (kind) {
            OptionKind.CALL -> forward * normCdf(d1) - strike * normCdf(d2)
            OptionKind.PUT -> strike * normCdf(-d2) - forward * normCdf(-d1)
        }
    }

    /**
     * Годовая подразумеваемая волатильность (в долях, 0.25 = 25%) по рыночной премии.
     *
     * @return волатильность или null при невалидных входных данных (неположительная
     *   премия/цена/время) или если премия ниже внутренней стоимости
     */
    fun impliedVolatility(
        forward: Double,
        strike: Double,
        yearsToExpiry: Double,
        kind: OptionKind,
        premium: Double,
    ): Double? {
        if (forward <= 0.0 || strike <= 0.0 || yearsToExpiry <= 0.0 || premium <= 0.0) return null
        if (premium < intrinsicValue(forward, strike, kind)) return null

        var low = MIN_SIGMA
        var high = MAX_SIGMA
        if (price(forward, strike, yearsToExpiry, kind, high) < premium) return high

        repeat(MAX_ITERATIONS) {
            val mid = 0.5 * (low + high)
            if (price(forward, strike, yearsToExpiry, kind, mid) < premium) {
                low = mid
            } else {
                high = mid
            }
            if (high - low < TOLERANCE) return 0.5 * (low + high)
        }
        return 0.5 * (low + high)
    }

    private fun intrinsicValue(
        forward: Double,
        strike: Double,
        kind: OptionKind,
    ): Double =
        when (kind) {
            OptionKind.CALL -> maxOf(forward - strike, 0.0)
            OptionKind.PUT -> maxOf(strike - forward, 0.0)
        }

    /**
     * Функция ошибок erf (аппроксимация Abramowitz & Stegun 7.1.26, погрешность < 1.5e-7).
     * На JVM нет kotlin.math.erf — используется полиномиальная аппроксимация.
     */
    private fun erf(x: Double): Double {
        val sign = if (x < 0.0) -1.0 else 1.0
        val absX = kotlin.math.abs(x)
        val t = 1.0 / (1.0 + 0.3275911 * absX)
        // Horner: a1 + t*(a2 + t*(a3 + t*(a4 + t*a5)))
        val poly =
            t * (
                0.254829592 +
                    t * (
                        -0.284496736 +
                            t * (1.421413741 + t * (-1.453152027 + t * 1.061405429))
                    )
            )
        return sign * (1.0 - poly * exp(-absX * absX))
    }

    private fun normCdf(x: Double): Double = 0.5 * (1.0 + erf(x / sqrt(2.0)))
}
