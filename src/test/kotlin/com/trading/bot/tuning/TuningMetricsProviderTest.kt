package com.trading.bot.tuning

import com.trading.bot.backtest.StrategyParameters
import com.trading.bot.model.InstrumentType
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.entity.Position
import com.trading.bot.repository.PositionRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Unit-тесты [TuningMetricsProvider]: сбор метрик прошедшего месяца из закрытых
 * позиций (PF/WinRate/Sharpe/MaxDD) с отсечением правой границы окна и дефолтами
 * для пустого месяца; текущие параметры берутся из последней активной версии.
 */
class TuningMetricsProviderTest {
    private val positionRepository = Mockito.mock(PositionRepository::class.java)
    private val versionRepository = Mockito.mock(StrategyVersionRepository::class.java)
    private val provider = TuningMetricsProvider(positionRepository, versionRepository)

    private val now: LocalDate = LocalDate.now()
    private val monthStart: LocalDateTime = now.withDayOfMonth(1).minusMonths(1).atStartOfDay()
    private val monthEnd: LocalDate = now.withDayOfMonth(1)

    @Test
    fun `empty month falls back to neutral defaults`() {
        runBlocking {
            Mockito.`when`(positionRepository.findClosedSince(monthStart)).thenReturn(emptyList())
        }

        val data = runBlocking { provider.collectCurrentMonthlyData() }

        assertEquals(0, data.metrics.tradesCount)
        assertEquals(1.0, data.metrics.profitFactor)
        assertEquals(50.0, data.metrics.winRatePct)
        assertEquals(0.0, data.metrics.sharpe)
        assertEquals(0.0, data.metrics.maxDrawdownPct)
        assertEquals(StrategyParameters(), data.currentParameters)
    }

    @Test
    fun `metrics are computed only for positions closed inside the month window`() {
        val inWindow =
            listOf(
                closedPosition(BigDecimal("100"), monthStart.plusDays(1)),
                closedPosition(BigDecimal("50"), monthStart.plusDays(2)),
                closedPosition(BigDecimal("-100"), monthEnd.atStartOfDay().minusHours(1)),
            )
        // Позиция с закрытием уже в новом месяце должна быть отброшена правой границей окна.
        val nextMonth = closedPosition(BigDecimal("-500"), monthEnd.atStartOfDay().plusMinutes(1))

        runBlocking {
            Mockito.`when`(positionRepository.findClosedSince(monthStart)).thenReturn(inWindow + nextMonth)
        }

        val data = runBlocking { provider.collectCurrentMonthlyData() }

        assertEquals(3, data.metrics.tradesCount, "position from the next month must be filtered out")
        assertEquals(1.5, data.metrics.profitFactor) // 150 / 100
        assertEquals(200.0 / 3.0, data.metrics.winRatePct, 1e-9)
        // Кривая 100 → 150 → 50: пик 150, просадка (150-50)/150 = 66.67%.
        assertEquals(66.6666, data.metrics.maxDrawdownPct, 1e-3)
        assertEquals(0.1961, data.metrics.sharpe, 1e-3)
    }

    @Test
    fun `metrics without losses use profit factor 1 and zero drawdown`() {
        runBlocking {
            Mockito
                .`when`(positionRepository.findClosedSince(monthStart))
                .thenReturn(listOf(closedPosition(BigDecimal("300"), monthStart.plusDays(1))))
        }

        val data = runBlocking { provider.collectCurrentMonthlyData() }

        assertEquals(1, data.metrics.tradesCount)
        assertEquals(1.0, data.metrics.profitFactor, "no losses → no division by zero")
        assertEquals(100.0, data.metrics.winRatePct)
        assertEquals(0.0, data.metrics.maxDrawdownPct, "monotonically growing equity has no drawdown")
        assertEquals(0.0, data.metrics.sharpe, "single trade is not enough for sharpe")
    }

    @Test
    fun `current parameters are read from the last active version when present`() {
        runBlocking {
            Mockito.`when`(positionRepository.findClosedSince(monthStart)).thenReturn(emptyList())
            Mockito
                .`when`(versionRepository.findFirstByRolledBackFalseOrderByDeployedAtDesc())
                .thenReturn(
                    StrategyVersion(
                        id = 7,
                        version = "2026-09",
                        parameters = """{"slPoints":150}""",
                        gateStatus = "PASS",
                        metrics = "{}",
                    ),
                )
        }

        val data = runBlocking { provider.collectCurrentMonthlyData() }

        // Репозиторий был опрошен (ветка "версия найдена").
        runBlocking { Mockito.verify(versionRepository).findFirstByRolledBackFalseOrderByDeployedAtDesc() }
        assertEquals(StrategyParameters(), data.currentParameters)
    }

    private fun closedPosition(
        realizedPnl: BigDecimal,
        closedAt: LocalDateTime,
    ): Position =
        Position(
            ticker = "CNYRUBF",
            direction = PositionDirection.LONG,
            quantity = 1,
            entryPrice = BigDecimal("100"),
            realizedPnl = realizedPnl,
            status = PositionStatus.CLOSED,
            instrumentType = InstrumentType.FUTURES,
            openedAt = closedAt.minusHours(1),
            closedAt = closedAt,
        )
}
