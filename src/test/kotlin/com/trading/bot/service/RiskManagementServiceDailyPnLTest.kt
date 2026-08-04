package com.trading.bot.service

import com.trading.bot.config.RiskConfig
import com.trading.bot.model.DailyRiskSnapshot
import com.trading.bot.model.Position
import com.trading.bot.model.PositionDirection
import com.trading.bot.repository.DailyRiskSnapshotRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

/**
 * Персистентный дневной P&L: накопление по сделкам, срабатывание лимита
 * и восстановление снапшота после рестарта.
 */
class RiskManagementServiceDailyPnLTest {
    private val repo = Mockito.mock(DailyRiskSnapshotRepository::class.java)
    private val moscowToday = LocalDate.now(ZoneId.of("Europe/Moscow"))

    private fun service(maxDailyLoss: BigDecimal): RiskManagementService {
        val config = RiskConfig().apply { maxDailyLossRub = maxDailyLoss }
        return RiskManagementService(config, DailyRiskService(config, repo))
    }

    @Test
    fun `daily pnl accumulates closed trades`() {
        val s = service(BigDecimal("5000"))

        s.updateDailyPnL(BigDecimal("1000"))
        s.updateDailyPnL(BigDecimal("-400"))

        assertEquals(0, BigDecimal("600").compareTo(s.getDailyPnL()))
        Mockito.verify(repo).upsert(moscowToday, BigDecimal("1000"), false, BigDecimal.ZERO)
        Mockito.verify(repo).upsert(moscowToday, BigDecimal("600"), false, BigDecimal.ZERO)
    }

    @Test
    fun `loss limit reached blocks further trading`() {
        val s = service(BigDecimal("5000"))
        assertFalse(s.isDailyLossLimitReached())

        s.updateDailyPnL(BigDecimal("-5001"))

        assertTrue(s.isDailyLossLimitReached())
    }

    @Test
    fun `small losses do not trigger the limit`() {
        val s = service(BigDecimal("5000"))

        s.updateDailyPnL(BigDecimal("-3000"))

        assertFalse(s.isDailyLossLimitReached())
    }

    @Test
    fun `trailing stop never loosens when price reverses`() {
        val s = service(BigDecimal("5000"))
        val long =
            Position(
                ticker = "SBER",
                direction = PositionDirection.LONG,
                quantity = 1,
                entryPrice = BigDecimal("100"),
                trailingStopPrice = BigDecimal("108.90"),
            )
        val short =
            Position(
                ticker = "SBER",
                direction = PositionDirection.SHORT,
                quantity = 1,
                entryPrice = BigDecimal("100"),
                trailingStopPrice = BigDecimal("91.90"),
            )

        s.updateTrailingStop(long, BigDecimal("105"))
        s.updateTrailingStop(short, BigDecimal("95"))

        assertEquals(BigDecimal("108.90"), long.trailingStopPrice)
        assertEquals(BigDecimal("91.90"), short.trailingStopPrice)
    }

    @Test
    fun `restores daily state from snapshot after restart`() {
        Mockito.`when`(repo.findByDate(moscowToday)).thenReturn(
            DailyRiskSnapshot(
                id = 1,
                tradeDate = moscowToday,
                dailyPnl = BigDecimal("-3000"),
                limitReached = false,
                maxDrawdownToday = BigDecimal("-3000"),
            ),
        )
        val s = service(BigDecimal("5000"))

        assertEquals(0, BigDecimal("-3000").compareTo(s.getDailyPnL()))
    }

    @Test
    fun `restores limit reached flag from snapshot`() {
        Mockito.`when`(repo.findByDate(moscowToday)).thenReturn(
            DailyRiskSnapshot(
                id = 1,
                tradeDate = moscowToday,
                dailyPnl = BigDecimal("-6000"),
                limitReached = true,
                maxDrawdownToday = BigDecimal("-6000"),
            ),
        )
        val s = service(BigDecimal("5000"))

        assertTrue(s.isDailyLossLimitReached())
    }
}
