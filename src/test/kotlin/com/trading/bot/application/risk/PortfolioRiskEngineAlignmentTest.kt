package com.trading.bot.application.risk

import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.config.RiskConfig
import com.trading.bot.domain.risk.PortfolioDataQuality
import com.trading.bot.domain.risk.PortfolioRiskRequest
import com.trading.bot.domain.risk.ResolvedCorrelationMatrix
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.entity.Candle
import com.trading.bot.model.entity.Position
import com.trading.bot.service.CandleCacheService
import com.trading.bot.service.CorrelationMatrixProvider
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln

/**
 * Регресс исторического VaR/CVaR: серии дневных лог-доходностей выравниваются
 * ПО ДАТЕ (inner join), а не «последние n» каждого ряда.
 *
 * Дефект: у инструментов разные торговые календари (CNYRUBF до 2026-10-16,
 * GAZP до 2026-10-15), и «последние n» перемножали доходности РАЗНЫХ дней.
 * На сдвинутом на день календаре это могло показать «идеальный хедж» там, где
 * его нет (VaR 0 вместо реального), и наоборот — и это прямо кормит
 * `historicalVar95Rub` → `effectiveVar95Rub` → `varPercent` → BLOCK/SCALE входа.
 */
class PortfolioRiskEngineAlignmentTest {
    private val riskConfig = RiskConfig()
    private val correlationProvider = Mockito.mock(CorrelationMatrixProvider::class.java)
    private val candleCache = Mockito.mock(CandleCacheService::class.java)
    private val meterRegistry = SimpleMeterRegistry()

    private val engine =
        PortfolioRiskEngineImpl(riskConfig, InstrumentsConfig(), correlationProvider, candleCache, meterRegistry)

    private val up = BigDecimal("1.02")
    private val down = BigDecimal("0.98")
    private val baseDate = LocalDateTime.of(2026, 8, 3, 10, 0)

    private fun position(
        ticker: String,
        price: BigDecimal = BigDecimal("100"),
    ): Position =
        Position(
            id = ticker.hashCode().toLong(),
            ticker = ticker,
            direction = PositionDirection.LONG,
            quantity = 1,
            entryPrice = price,
        )

    private fun stubVols(vararg tickers: String) {
        tickers.forEach { t ->
            Mockito.`when`(candleCache.calculateRealizedVolatility(t, "DAY_1", 20)).thenReturn(0.5)
        }
    }

    private fun stubCorrelation(tickers: List<String>) {
        Mockito.`when`(correlationProvider.resolvedWithQuality(tickers, "MINUTE_10", 50)).thenReturn(
            ResolvedCorrelationMatrix(
                matrix = tickers.map { a -> tickers.map { b -> if (a == b) 1.0 else 0.0 } },
                quality = PortfolioDataQuality.KNOWN,
            ),
        )
    }

    /** Закрытия DAY_1: `steps` шагов множителя [factorAt] от базы 100. */
    private fun closes(
        steps: Int,
        factorAt: (Int) -> BigDecimal,
    ): List<BigDecimal> {
        var value = BigDecimal("100")
        val out = mutableListOf(value)
        for (k in 1..steps) {
            value = value.multiply(factorAt(k)).setScale(8, RoundingMode.HALF_UP)
            out.add(value)
        }
        return out
    }

    private fun stubCandles(
        ticker: String,
        startDate: LocalDateTime,
        closes: List<BigDecimal>,
    ) {
        Mockito
            .`when`(candleCache.getRecentCandles(ticker, "DAY_1", riskConfig.portfolioHistoricalLookbackDays + 1))
            .thenReturn(
                closes.mapIndexed { i, close ->
                    Candle(
                        ticker = ticker,
                        timeframe = "DAY_1",
                        openPrice = close,
                        highPrice = close,
                        lowPrice = close,
                        closePrice = close,
                        volume = 0,
                        time = startDate.plusDays(i.toLong()),
                    )
                },
            )
    }

    private fun logReturns(closes: List<BigDecimal>): List<Double> =
        (1 until closes.size).map { i ->
            ln(closes[i].divide(closes[i - 1], 8, RoundingMode.HALF_UP).toDouble())
        }

    /**
     * Оракул СТАРОГО (дефектного) поведения: индексное выравнивание «последние n»
     * без учёта дат — то, как движок склеивал ряды до фикса.
     */
    private fun naiveIndexAlignedVar95Rub(
        a: List<Double>,
        b: List<Double>,
        gross: Double,
    ): Double {
        val n = minOf(a.size, b.size)
        val series =
            (0 until n)
                .map { i -> 0.5 * a[a.size - n + i] + 0.5 * b[b.size - n + i] }
                .sorted()
        val index = (series.size - 1) * 0.05
        val lower = floor(index).toInt()
        val upper = ceil(index).toInt()
        val quantile =
            if (lower == upper) {
                series[lower]
            } else {
                series[lower] + (series[upper] - series[lower]) * (index - lower)
            }
        return (-quantile * gross).coerceAtLeast(0.0)
    }

    private fun twoPositionRequest(): PortfolioRiskRequest =
        PortfolioRiskRequest(
            candidateTicker = "B",
            candidateDirection = PositionDirection.LONG,
            candidateNotionalRub = BigDecimal("100"),
            openPositions = listOf(position("A")),
            aum = BigDecimal("1000"),
        )

    @Test
    fun `shifted calendars are inner joined by date instead of taking the last n`(): Unit =
        runBlocking {
            stubVols("A", "B")
            stubCorrelation(listOf("A", "B"))
            // A: чередование +2%/-2%, начиная с роста (даты D1..D60).
            val closesA = closes(60) { k -> if (k % 2 == 1) up else down }
            // B торгуется на день позже (D1..D61) и на общих датах D2..D60 даёт
            // ТУ ЖЕ доходность, что A (B-шаг k -> дата D(k+1)).
            val closesB = closes(60) { k -> if (k % 2 == 0) up else down }
            stubCandles("A", baseDate, closesA)
            stubCandles("B", baseDate.plusDays(1), closesB)

            val report = engine.evaluate(twoPositionRequest())

            // Общие даты D2..D60: доходности совпадают -> p = 0.5*rA + 0.5*rA = rA,
            // 5%-квантиль в зоне падений: 0.0202 * gross 200 ≈ 4.04.
            assertEquals(4.04, report.historicalVar95Rub.toDouble(), 0.1)
            assertEquals(4.04, report.cvar95Rub.toDouble(), 0.1)

            // Старое индексное выравнивание склеило бы A[Dj] с B[D(j+1)] —
            // противоположные по знаку дни -> ложный «идеальный хедж» ~0.
            val naive =
                naiveIndexAlignedVar95Rub(logReturns(closesA), logReturns(closesB), gross = 200.0)
            assertTrue(
                naive < 0.5,
                "индексное выравнивание должно давать другое (ошибочно нулевое) значение, получено $naive",
            )
        }

    @Test
    fun `no common dates yields zero historical var without throwing`(): Unit =
        runBlocking {
            stubVols("A", "B")
            stubCorrelation(listOf("A", "B"))
            stubCandles("A", baseDate, closes(60) { k -> if (k % 2 == 1) up else down })
            // Календарь B не пересекается с A вовсе (нет общих дат).
            stubCandles("B", baseDate.plusDays(200), closes(60) { k -> if (k % 2 == 0) up else down })

            val report = engine.evaluate(twoPositionRequest())

            assertEquals(0.0, report.historicalVar95Rub.toDouble(), 1e-9)
            assertEquals(0.0, report.cvar95Rub.toDouble(), 1e-9)
            // VaR не «пропал»: остаются parametric/stress-метрики.
            assertTrue(report.effectiveVar95Rub > BigDecimal.ZERO)
        }
}
