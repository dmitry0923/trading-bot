package com.trading.bot.service

import com.trading.bot.model.PositionDirection
import com.trading.bot.model.entity.Position
import com.trading.bot.repository.PositionRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * Unit-тест [ProfitForecastService]: нейтральный прогноз без истории,
 * нулевая волатильность на одном торговом дне и отбрасывание сделок без даты
 * закрытия (они не образуют дневную доходность).
 */
class ProfitForecastServiceTest {
    private val positionRepo = Mockito.mock(PositionRepository::class.java)
    private val service = ProfitForecastService(positionRepo)

    private fun anyDateTime(): LocalDateTime {
        Mockito.any(LocalDateTime::class.java)
        return LocalDateTime.now()
    }

    private fun closed(
        pnl: String?,
        closedAt: LocalDateTime?,
    ): Position =
        Position(
            ticker = "SBER",
            direction = PositionDirection.LONG,
            quantity = 1,
            entryPrice = BigDecimal("100"),
            pnl = pnl?.toBigDecimal(),
            closedAt = closedAt,
        )

    @Test
    fun `empty history yields a neutral forecast`(): Unit =
        runBlocking {
            Mockito.`when`(positionRepo.findClosedSince(anyDateTime())).thenReturn(emptyList())

            val forecast = service.forecast(horizonDays = 30, capitalBase = BigDecimal("1000"))

            assertEquals(0, forecast.tradesAnalyzed)
            assertEquals(0.0, forecast.expectedReturnPercent, 1e-9)
        }

    @Test
    fun `a single closed day has zero volatility`(): Unit =
        runBlocking {
            Mockito
                .`when`(positionRepo.findClosedSince(anyDateTime()))
                .thenReturn(listOf(closed("10", LocalDateTime.of(2026, 1, 5, 18, 45))))

            val forecast = service.forecast(horizonDays = 30, capitalBase = BigDecimal("1000"))

            assertEquals(1, forecast.tradesAnalyzed)
            assertEquals(0.0, forecast.dailyVolatilityPercent, 1e-9)
            assertEquals(0.0, forecast.confidenceLowPercent - forecast.expectedReturnPercent + 0.0, 1e-9)
        }

    @Test
    fun `trades without a close date do not form daily returns`(): Unit =
        runBlocking {
            Mockito
                .`when`(positionRepo.findClosedSince(anyDateTime()))
                .thenReturn(listOf(closed("10", null), closed(null, LocalDateTime.of(2026, 1, 6, 18, 45))))

            val forecast = service.forecast(horizonDays = 10, capitalBase = BigDecimal("1000"))

            assertEquals(2, forecast.tradesAnalyzed)
        }
}
