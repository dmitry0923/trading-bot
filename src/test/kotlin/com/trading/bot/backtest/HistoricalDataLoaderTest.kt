package com.trading.bot.backtest

import com.trading.bot.client.MoexClient
import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.model.entity.Candle
import com.trading.bot.repository.CandleRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * [HistoricalDataLoader] — ветви фьючерсного и акционного путей загрузки,
 * дедупликация по времени, метрика и параллельный батч.
 */
class HistoricalDataLoaderTest {
    private val moexClient = mock<MoexClient>()
    private val candleRepo = mock<CandleRepository>()
    private val registry = SimpleMeterRegistry()
    private val clock = Clock.fixed(Instant.parse("2026-10-01T07:00:00Z"), ZoneId.of("Europe/Moscow"))
    private val loader =
        HistoricalDataLoader(
            moexClient = moexClient,
            candleRepo = candleRepo,
            meterRegistry = registry,
            instrumentsConfig = InstrumentsConfig(),
            clock = clock,
        )

    private fun candle(
        ticker: String,
        minutes: Long,
    ): Candle =
        Candle(
            ticker = ticker,
            timeframe = "MINUTE_10",
            openPrice = BigDecimal("100"),
            highPrice = BigDecimal("101"),
            lowPrice = BigDecimal("99"),
            closePrice = BigDecimal("100"),
            volume = 10,
            time = LocalDateTime.of(2026, 9, 30, 10, 0).plusMinutes(minutes),
        )

    @Test
    fun `futures ticker loads paged futures candles with ticker stamping and dedup`() =
        runBlocking {
            whenever(moexClient.getFuturesCandlesPaged(Mockito.anyString(), any(), any(), Mockito.anyInt())).thenReturn(
                listOf(candle("", 0), candle("", 10), candle("", 0)),
            )
            whenever(candleRepo.saveAll(anyList())).thenReturn(2)

            val result = loader.loadAndSave("CNYRUBF", days = 30)

            assertEquals("CNYRUBF", result.ticker)
            assertEquals(2, result.loaded)
            assertEquals(2, result.saved)
            assertEquals(2.0, registry.counter("backtest.history.loaded", "ticker", "CNYRUBF").count())
            val saved = Mockito.mockingDetails(candleRepo).invocations.single { it.method.name == "saveAll" }

            @Suppress("UNCHECKED_CAST")
            val candles = saved.arguments[0] as List<Candle>
            assertEquals(listOf("CNYRUBF", "CNYRUBF"), candles.map { it.ticker })
        }

    @Test
    fun `stock ticker loads plain stock candles`(): Unit =
        runBlocking {
            whenever(moexClient.getCandlesPaged(Mockito.anyString(), any(), any(), Mockito.anyInt())).thenReturn(listOf(candle("SBER", 0)))
            whenever(candleRepo.saveAll(anyList())).thenReturn(1)

            val result = loader.loadAndSave("SBER", days = 7)

            assertEquals(1, result.loaded)
            assertEquals(1, result.saved)
        }

    @Test
    fun `batch load maps every ticker to its result`(): Unit =
        runBlocking {
            whenever(moexClient.getCandlesPaged(Mockito.anyString(), any(), any(), Mockito.anyInt())).thenAnswer { invocation ->
                if (invocation.getArgument<String>(0) == "SBER") listOf(candle("SBER", 0)) else emptyList()
            }
            whenever(candleRepo.saveAll(anyList())).thenReturn(0)

            val results = loader.loadAndSaveAll(listOf("SBER", "GAZP"), days = 7)

            assertEquals(setOf("SBER", "GAZP"), results.keys)
            assertEquals(1, results.getValue("SBER").loaded)
            assertEquals(0, results.getValue("GAZP").loaded)
        }

    private fun anyList(): List<Candle> {
        Mockito.anyList<Candle>()
        return emptyList()
    }
}
