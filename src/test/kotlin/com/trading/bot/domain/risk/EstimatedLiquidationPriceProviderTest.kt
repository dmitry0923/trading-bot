package com.trading.bot.domain.risk

import com.trading.bot.model.PositionDirection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Проверка оценочной ликвидационной цены (conservative pre-trade guard).
 *
 * Для Si: priceStep 0.01, priceStepCost 10 ₽ → pointValue = 1000 ₽ на 1.0 цены;
 * bufferPrice = GO / pointValue = 15000 / 1000 = 15 ₽.
 */
class EstimatedLiquidationPriceProviderTest {
    private val provider = EstimatedLiquidationPriceProvider()

    private fun price(
        priceStep: BigDecimal,
        priceStepCost: BigDecimal,
    ): BigDecimal? =
        provider.liquidationPrice(
            entryPrice = BigDecimal("100"),
            direction = PositionDirection.LONG,
            marginPerContract = BigDecimal("15000"),
            priceStep = priceStep,
            priceStepCost = priceStepCost,
        )

    @Test
    fun `zero price step returns null instead of throwing`() {
        assertNull(price(BigDecimal.ZERO, BigDecimal("10")))
    }

    @Test
    fun `negative price step returns null`() {
        assertNull(price(BigDecimal("-0.01"), BigDecimal("10")))
    }

    @Test
    fun `zero or negative price step cost returns null`() {
        assertNull(price(BigDecimal("0.01"), BigDecimal.ZERO))
        assertNull(price(BigDecimal("0.01"), BigDecimal("-10")))
    }

    @Test
    fun `long liquidation price is entry minus margin buffer`() {
        val result =
            provider.liquidationPrice(
                entryPrice = BigDecimal("100"),
                direction = PositionDirection.LONG,
                marginPerContract = BigDecimal("15000"),
                priceStep = BigDecimal("0.01"),
                priceStepCost = BigDecimal("10"),
            )

        assertEquals(0, BigDecimal("85").compareTo(result))
    }

    @Test
    fun `short liquidation price is entry plus margin buffer`() {
        val result =
            provider.liquidationPrice(
                entryPrice = BigDecimal("100"),
                direction = PositionDirection.SHORT,
                marginPerContract = BigDecimal("15000"),
                priceStep = BigDecimal("0.01"),
                priceStepCost = BigDecimal("10"),
            )

        assertEquals(0, BigDecimal("115").compareTo(result))
    }

    @Test
    fun `null entry or direction returns null`() {
        assertNull(
            provider.liquidationPrice(
                entryPrice = null,
                direction = PositionDirection.LONG,
                marginPerContract = BigDecimal("15000"),
                priceStep = BigDecimal("0.01"),
                priceStepCost = BigDecimal("10"),
            ),
        )
        assertNull(
            provider.liquidationPrice(
                entryPrice = BigDecimal("100"),
                direction = null,
                marginPerContract = BigDecimal("15000"),
                priceStep = BigDecimal("0.01"),
                priceStepCost = BigDecimal("10"),
            ),
        )
    }
}
