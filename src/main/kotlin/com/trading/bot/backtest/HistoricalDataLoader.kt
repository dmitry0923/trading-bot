package com.trading.bot.backtest

import com.trading.bot.client.MoexClient
import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.repository.CandleRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.springframework.stereotype.Service
import java.time.LocalDateTime

/**
 * Результат загрузки исторических данных по одному тикеру.
 *
 * @param ticker тикер
 * @param loaded количество свечей, полученных от MOEX
 * @param saved количество новых свечей, записанных в БД
 */
data class LoadResult(
    val ticker: String,
    val loaded: Int,
    val saved: Int,
)

/**
 * Загрузчик исторических данных MOEX для бэктеста.
 *
 * - Тянет ВСЕ свечи за N дней через пагинированный ISS API ([MoexClient.getCandlesPaged])
 * - Пишет свечи в БД идемпотентно (ON CONFLICT DO NOTHING в CandleRepository)
 * - Загружает тикеры параллельно ([loadAndSaveAll])
 *
 * Пример: 2 года = 730 дней ≈ 26 000 10-минутных свечей на тикер.
 */
@Service
class HistoricalDataLoader(
    private val moexClient: MoexClient,
    private val candleRepo: CandleRepository,
    private val meterRegistry: MeterRegistry,
    private val instrumentsConfig: InstrumentsConfig = InstrumentsConfig(),
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Загружает и сохраняет историю по одному тикеру.
     *
     * @param ticker тикер инструмента
     * @param days глубина истории в днях
     * @return [LoadResult] — сколько загружено и сохранено
     */
    suspend fun loadAndSave(
        ticker: String,
        days: Int,
    ): LoadResult {
        val from = LocalDateTime.now().minusDays(days.toLong())
        val to = LocalDateTime.now()
        val candles =
            if (instrumentsConfig.isFutures(ticker)) {
                moexClient
                    .getFuturesCandlesPaged(ticker, from, to)
                    .map { it.copy(ticker = ticker) }
                    .distinctBy { it.time }
            } else {
                moexClient.getCandlesPaged(ticker, from, to)
            }
        val saved = candleRepo.saveAll(candles)
        meterRegistry.counter("backtest.history.loaded", Tags.of("ticker", ticker)).increment(candles.size.toDouble())
        logger.info { "HistoricalDataLoader $ticker: loaded=${candles.size}, saved=$saved (days=$days)" }
        return LoadResult(ticker = ticker, loaded = candles.size, saved = saved)
    }

    /**
     * Загружает историю для всех тикеров параллельно.
     *
     * @param tickers список тикеров
     * @param days глубина истории в днях
     * @return отображение тикер -> [LoadResult]
     */
    suspend fun loadAndSaveAll(
        tickers: List<String>,
        days: Int,
    ): Map<String, LoadResult> =
        coroutineScope {
            tickers
                .map { ticker -> async { ticker to loadAndSave(ticker, days) } }
                .awaitAll()
                .toMap()
        }
}
