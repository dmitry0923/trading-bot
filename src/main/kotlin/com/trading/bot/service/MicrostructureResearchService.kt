package com.trading.bot.service

import com.trading.bot.model.dto.MicrostructureCoverage
import com.trading.bot.model.entity.MicrostructureSnapshot
import com.trading.bot.model.entity.OrderbookBbo
import com.trading.bot.model.entity.TradeTick
import com.trading.bot.repository.HistoricalBboRepository
import com.trading.bot.repository.MicrostructureSnapshotRepository
import com.trading.bot.repository.TradeTickRepository
import org.springframework.stereotype.Service
import java.time.LocalDateTime

/**
 * Read-слой над микроструктурными таблицами для research (PLAN-001, шаг «а»).
 *
 * До этого слоя записанные данные (forward-L1 `microstructure_snapshots`,
 * исторические `orderbook_bbo`/`ticks`) были недоступны ни одному потребителю:
 * репозитории умели только писать. Сервис даёт единый вход — покрытие, оконное
 * чтение и CSV-выгрузку — не задевая торговый путь (слой наблюдательный).
 *
 * Семантика окна — полуоткрытый интервал `[from, to)`: правый бакет ещё не
 * закрыт, его включение дало бы lookahead в point-in-time признаках (как у
 * `candles`). Если `from`/`to` не заданы — берётся весь диапазон источника.
 *
 * Неограниченная выгрузка опасна: все методы чтения бьют `LIMIT` с
 * предохранителем [MAX_LIMIT].
 */
@Service
class MicrostructureResearchService(
    private val snapshotRepository: MicrostructureSnapshotRepository,
    private val bboRepository: HistoricalBboRepository,
    private val tickRepository: TradeTickRepository,
) {
    /** Покрытие всех трёх источников по тикеру (по одной строке на источник). */
    suspend fun coverage(ticker: String): List<MicrostructureCoverage> =
        listOf(
            snapshotRepository.coverage(ticker),
            bboRepository.coverage(ticker),
            tickRepository.coverage(ticker),
        )

    /** Forward-L1 снапшоты тикера на окне `[from, to)`. */
    suspend fun snapshots(
        ticker: String,
        from: LocalDateTime?,
        to: LocalDateTime?,
        limit: Int?,
    ): List<MicrostructureSnapshot> =
        snapshotRepository.findByTickerAndTimeBetween(
            ticker,
            from ?: MIN_TIMESTAMP,
            to ?: MAX_TIMESTAMP,
            clampLimit(limit),
        )

    /** Исторические BBO-бакеты тикера на окне `[from, to)`. */
    suspend fun bbo(
        ticker: String,
        from: LocalDateTime?,
        to: LocalDateTime?,
        limit: Int?,
    ): List<OrderbookBbo> =
        bboRepository.findByTickerAndTimeBetween(
            ticker,
            from ?: MIN_TIMESTAMP,
            to ?: MAX_TIMESTAMP,
            clampLimit(limit),
        )

    /** Исторические сделки тикера на окне `[from, to)`. */
    suspend fun ticks(
        ticker: String,
        from: LocalDateTime?,
        to: LocalDateTime?,
        limit: Int?,
    ): List<TradeTick> =
        tickRepository.findByTickerAndTimeBetween(
            ticker,
            from ?: MIN_TIMESTAMP,
            to ?: MAX_TIMESTAMP,
            clampLimit(limit),
        )

    /**
     * CSV-выгрузка одного источника на окне `[from, to)`.
     *
     * @param source одно из [SOURCES]
     * @throws IllegalArgumentException при неизвестном источнике
     */
    suspend fun exportCsv(
        source: String,
        ticker: String,
        from: LocalDateTime?,
        to: LocalDateTime?,
        limit: Int?,
    ): String =
        when (requireSource(source)) {
            SOURCE_SNAPSHOTS -> snapshotsCsv(ticker, from, to, limit)
            SOURCE_BBO -> bboCsv(ticker, from, to, limit)
            else -> ticksCsv(ticker, from, to, limit)
        }

    private suspend fun snapshotsCsv(
        ticker: String,
        from: LocalDateTime?,
        to: LocalDateTime?,
        limit: Int?,
    ): String {
        val rows = snapshots(ticker, from, to, limit)
        return csv(SNAPSHOT_HEADER, rows) { s ->
            listOf(
                s.ticker,
                s.time,
                s.updateCount,
                s.price,
                s.bid,
                s.ask,
                s.bidSize,
                s.askSize,
                s.spreadBps,
                s.obi,
                s.microprice,
                s.micropriceDeviationBps,
            )
        }
    }

    private suspend fun bboCsv(
        ticker: String,
        from: LocalDateTime?,
        to: LocalDateTime?,
        limit: Int?,
    ): String {
        val rows = bbo(ticker, from, to, limit)
        return csv(BBO_HEADER, rows) { b ->
            listOf(
                b.ticker,
                b.time,
                b.quoteCount,
                b.price,
                b.bid,
                b.ask,
                b.bidSize,
                b.askSize,
                b.spreadBps,
                b.obi,
                b.microprice,
                b.micropriceDeviationBps,
            )
        }
    }

    private suspend fun ticksCsv(
        ticker: String,
        from: LocalDateTime?,
        to: LocalDateTime?,
        limit: Int?,
    ): String {
        val rows = ticks(ticker, from, to, limit)
        return csv(TICK_HEADER, rows) { t ->
            listOf(
                t.ticker,
                t.time,
                t.dealId,
                t.price,
                t.volume,
                t.direction,
                t.openInterest,
            )
        }
    }

    private fun <T> csv(
        header: String,
        rows: List<T>,
        cells: (T) -> List<Any?>,
    ): String =
        buildString {
            append(header)
            append('\n')
            rows.forEach { row ->
                append(cells(row).joinToString(",") { cell -> cell?.toString() ?: "" })
                append('\n')
            }
        }

    private fun requireSource(source: String): String =
        source.lowercase().also {
            require(it in SOURCES) { "unknown microstructure source: $source (expected one of $SOURCES)" }
        }

    private fun clampLimit(limit: Int?): Int =
        when {
            limit == null -> DEFAULT_LIMIT
            limit < 1 -> 1
            limit > MAX_LIMIT -> MAX_LIMIT
            else -> limit
        }

    companion object {
        const val SOURCE_SNAPSHOTS = "snapshots"
        const val SOURCE_BBO = "bbo"
        const val SOURCE_TICKS = "ticks"

        /** Допустимые значения [exportCsv]-параметра `source`. */
        val SOURCES: Set<String> = setOf(SOURCE_SNAPSHOTS, SOURCE_BBO, SOURCE_TICKS)

        /** Размер выгрузки по умолчанию, если `limit` не задан. */
        const val DEFAULT_LIMIT = 100_000

        /**
         * Потолок выгрузки: CSV собирается в памяти одной строкой, 1 000 000 строк ≈ 100 МБ
         * и мог уронить контейнер по OOM авторизованным read-запросом (скан-7 P3).
         */
        const val MAX_LIMIT = 100_000
        private const val SNAPSHOT_HEADER =
            "ticker,time,update_count,price,bid,ask,bid_size,ask_size," +
                "spread_bps,obi,microprice,microprice_deviation_bps"
        private const val BBO_HEADER =
            "ticker,time,quote_count,price,bid,ask,bid_size,ask_size," +
                "spread_bps,obi,microprice,microprice_deviation_bps"
        private const val TICK_HEADER =
            "ticker,time,deal_id,price,volume,direction,open_interest"

        // Широкие границы «весь диапазон»: конкретные данные всё равно ограничены LIMIT.
        private val MIN_TIMESTAMP: LocalDateTime = LocalDateTime.of(1970, 1, 1, 0, 0)
        private val MAX_TIMESTAMP: LocalDateTime = LocalDateTime.of(2100, 1, 1, 0, 0)
    }
}
