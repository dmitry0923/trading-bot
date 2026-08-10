package com.trading.bot.domain.risk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Black-76: ценообразование опционов на фьючерсы и инверсия подразумеваемой
 * волатильности бисекцией.
 */
class Black76CalculatorTest {
    private val forward = 83_237.0
    private val strike = 83_000.0
    private val yearsToExpiry = 30.0 / 365.0

    @Test
    fun `implied volatility round trips to the input sigma`() {
        val sigma = 0.3
        val premium = Black76Calculator.price(forward, strike, yearsToExpiry, OptionKind.CALL, sigma)

        val iv = Black76Calculator.impliedVolatility(forward, strike, yearsToExpiry, OptionKind.CALL, premium)

        assertNotNull(iv)
        assertEquals(sigma, requireNotNull(iv), 1e-4)
    }

    @Test
    fun `expired option is worth intrinsic value`() {
        // call ITM (F=83237 > K=83000): intrinsic = F-K = 237
        assertEquals(forward - strike, Black76Calculator.price(forward, strike, 0.0, OptionKind.CALL, 0.3), 1e-6)
        // call OTM (F=83000 < K=83237): intrinsic = 0
        assertEquals(0.0, Black76Calculator.price(strike, forward, 0.0, OptionKind.CALL, 0.3), 1e-6)
        // put OTM (F=83237 > K=83000): intrinsic = 0
        assertEquals(0.0, Black76Calculator.price(forward, strike, 0.0, OptionKind.PUT, 0.3), 1e-6)
        // put ITM (K=83237 > F=83000): intrinsic = K-F = 237
        assertEquals(forward - strike, Black76Calculator.price(strike, forward, 0.0, OptionKind.PUT, 0.3), 1e-6)
    }

    @Test
    fun `call put parity holds`() {
        val call = Black76Calculator.price(forward, strike, yearsToExpiry, OptionKind.CALL, 0.25)
        val put = Black76Calculator.price(forward, strike, yearsToExpiry, OptionKind.PUT, 0.25)

        assertEquals(forward - strike, call - put, 1e-6)
    }

    @Test
    fun `higher sigma gives higher atm premium`() {
        val low = Black76Calculator.price(forward, strike, yearsToExpiry, OptionKind.CALL, 0.2)
        val high = Black76Calculator.price(forward, strike, yearsToExpiry, OptionKind.CALL, 0.5)

        assert(high > low)
    }

    @Test
    fun `iv null on invalid inputs`() {
        assertNull(Black76Calculator.impliedVolatility(forward, strike, yearsToExpiry, OptionKind.CALL, 0.0))
        assertNull(Black76Calculator.impliedVolatility(forward, strike, 0.0, OptionKind.CALL, 100.0))
        assertNull(Black76Calculator.impliedVolatility(-1.0, strike, yearsToExpiry, OptionKind.CALL, 100.0))
        assertNull(Black76Calculator.impliedVolatility(forward, -1.0, yearsToExpiry, OptionKind.CALL, 100.0))
    }

    @Test
    fun `iv null when premium below intrinsic`() {
        // call с deep ITM: премия не может быть ниже внутренней стоимости
        assertNull(Black76Calculator.impliedVolatility(forward, strike - 10_000.0, yearsToExpiry, OptionKind.CALL, 1.0))
    }

    @Test
    fun `at the money iv close to rule of thumb`() {
        // ATM call ≈ 0.4 * F * sigma * sqrt(T)  ->  sigma ≈ premium / (0.4 * F * sqrt(T))
        val premium = Black76Calculator.price(forward, forward, yearsToExpiry, OptionKind.CALL, 0.25)
        val ruleOfThumb = premium / (0.4 * forward * kotlin.math.sqrt(yearsToExpiry))

        assertEquals(0.25, ruleOfThumb, 0.05)
    }
}
