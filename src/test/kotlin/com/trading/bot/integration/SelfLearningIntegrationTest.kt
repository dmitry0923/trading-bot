package com.trading.bot.integration

import com.trading.bot.model.*
import com.trading.bot.repository.*
import com.trading.bot.service.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.math.BigDecimal
import java.time.LocalDateTime

@SpringBootTest
@Testcontainers
class SelfLearningIntegrationTest : AbstractTestContainerTest() {

    @Autowired
    lateinit var tradeAnalysisService: TradeAnalysisService

    @Autowired
    lateinit var adaptiveRiskService: AdaptiveRiskService

    @Autowired
    lateinit var positionRepository: PositionRepository

    @Autowired
    lateinit var blindSpotRepository: BlindSpotRepository

    @Autowired
    lateinit var adjustmentRepository: StrategyAdjustmentRepository

    @BeforeEach
    fun setup() {
        positionRepository.deleteAll()
        blindSpotRepository.deleteAll()
        adjustmentRepository.deleteAll()
    }

    @Test
    fun `trade analysis calculates correct win rate`() {
        // Given: 3 winning, 2 losing positions
        savePosition("SBER", BigDecimal("100"), BigDecimal("110"), PositionStatus.CLOSED, "TAKE_PROFIT")
        savePosition("SBER", BigDecimal("100"), BigDecimal("115"), PositionStatus.CLOSED, "TAKE_PROFIT")
        savePosition("SBER", BigDecimal("100"), BigDecimal("105"), PositionStatus.CLOSED, "TAKE_PROFIT")
        savePosition("SBER", BigDecimal("100"), BigDecimal("90"), PositionStatus.CLOSED, "STOP_LOSS")
        savePosition("SBER", BigDecimal("100"), BigDecimal("95"), PositionStatus.CLOSED, "STOP_LOSS")

        // When
        val stats = tradeAnalysisService.analyzeLastNDays(1)

        // Then
        assertTrue(stats.containsKey("SBER"))
        val sber = stats["SBER"]!!
        assertEquals(5, sber.totalTrades)
        assertEquals(3, sber.winningTrades)
        assertEquals(2, sber.losingTrades)
        assertEquals(0.6, sber.winRate, 0.01)
        assertTrue(sber.profitFactor > 1.0)
    }

    @Test
    fun `adaptive risk pauses trading after 4 consecutive losses`() {
        // Given: 4 consecutive losses
        repeat(4) {
            savePosition("GAZP", BigDecimal("200"), BigDecimal("190"), PositionStatus.CLOSED, "STOP_LOSS")
        }

        // When
        val shouldPause = adaptiveRiskService.shouldPauseTrading("GAZP")

        // Then
        assertTrue(shouldPause)
    }

    @Test
    fun `adaptive risk calculates kelly position size`() {
        // Given: 6 wins, 4 losses, avg win 150, avg loss 100
        repeat(6) {
            savePosition("LKOH", BigDecimal("1000"), BigDecimal("1150"), PositionStatus.CLOSED, "TAKE_PROFIT")
        }
        repeat(4) {
            savePosition("LKOH", BigDecimal("1000"), BigDecimal("900"), PositionStatus.CLOSED, "STOP_LOSS")
        }

        // When
        val size = adaptiveRiskService.calculateOptimalPositionSize("LKOH")

        // Then
        assertTrue(size > BigDecimal.ZERO)
        assertTrue(size <= BigDecimal("500000")) // maxPositionRub default
    }

    @Test
    fun `blind spots are persisted to database`() {
        // Given: many SL hits
        repeat(5) {
            savePosition("YNDX", BigDecimal("3000"), BigDecimal("2900"), PositionStatus.CLOSED, "STOP_LOSS")
        }

        // When
        tradeAnalysisService.analyzeLastNDays(1)

        // Then
        val spots = blindSpotRepository.findByTickerAndIsActiveTrue("YNDX")
        assertTrue(spots.isNotEmpty())
        assertTrue(spots.any { it.conditionPattern.contains("Stop-Loss") })
    }

    @Test
    fun `drawdown recovery detected after 3 consecutive losses`() {
        // Given
        repeat(3) {
            savePosition("VTBR", BigDecimal("100"), BigDecimal("90"), PositionStatus.CLOSED, "STOP_LOSS")
        }

        // When
        val inRecovery = adaptiveRiskService.isInDrawdownRecovery()

        // Then
        assertTrue(inRecovery)
    }

    @Test
    fun `time pattern analysis returns hourly win rates`() {
        // Given
        savePosition("SBER", BigDecimal("100"), BigDecimal("110"), PositionStatus.CLOSED, "TAKE_PROFIT", hour = 10)
        savePosition("SBER", BigDecimal("100"), BigDecimal("90"), PositionStatus.CLOSED, "STOP_LOSS", hour = 16)

        // When
        val pattern = tradeAnalysisService.timePatternAnalysis("SBER", 1)

        // Then
        assertEquals("SBER", pattern.ticker)
        assertTrue(pattern.hourlyWinRates.containsKey(10))
        assertTrue(pattern.hourlyWinRates.containsKey(16))
    }

    private fun savePosition(
        ticker: String,
        entry: BigDecimal,
        close: BigDecimal,
        status: PositionStatus,
        reason: String,
        hour: Int = 12
    ) {
        val pnl = close.subtract(entry).multiply(BigDecimal(10))
        val opened = LocalDateTime.now().minusDays(1).withHour(hour)
        positionRepository.save(
            Position(
                ticker = ticker,
                direction = PositionDirection.LONG,
                quantity = 10,
                entryPrice = entry,
                currentPrice = close,
                closePrice = close,
                pnl = pnl,
                status = status,
                alorOrderId = "test-${System.nanoTime()}",
                closeReason = reason,
                openedAt = opened,
                closedAt = opened.plusHours(2)
            )
        )
    }
}
