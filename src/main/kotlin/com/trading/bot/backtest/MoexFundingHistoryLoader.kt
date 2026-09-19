package com.trading.bot.backtest

import com.trading.bot.config.FundingConfig
import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.model.entity.FundingHistoryRecord
import com.trading.bot.repository.FundingHistoryRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Загрузчик исторического ряда funding (MOEX ISS history endpoint, research P1).
 *
 * Тянет фактические SWAPRATE за период from..till по тикеру (пагинация start,
 * как в [MoexClient.fetchCandlesPaged]) и сохраняет в funding_history
 * (RUB/контракт/клиринг после [FundingConfig.moexLotMultiplier]).
 *
 * Источник — [FundingConfig.moexHistoryUrl] (плейсхолдеры {ticker}, {from},
 * {till}). Пустой URL → донакачка отключена (возврат пустого [LoadResult]).
 * Значение/конвертация — те же, что в LIVE [FundingConfig.moexColumn]=SWAPRATE,
 * raw в RUB за 1 ед. базового актива.
 *
 * Назначение: P&L бэктеста по ФАКТИЧЕСКОМУ funding (вместо фиксированного
 * fundingRubPerContractPerDay) и калибровка порогов funding-veto (WFA) —
 * после донакачки снимается открытый P1.
 */
@Service
class MoexFundingHistoryLoader(
    private val fundingConfig: FundingConfig,
    private val instrumentsConfig: InstrumentsConfig,
    private val fundingHistoryRepository: FundingHistoryRepository,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) {
    private val logger = KotlinLogging.logger {}
    private val webClient =
        WebClient
            .builder()
            .codecs { it.defaultCodecs().maxInMemorySize(16 * 1024 * 1024) }
            .build()
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /**
     * Загружает и сохраняет историю funding по тикеру за [days] полных дней.
     *
     * @return сколько строк получено от MOEX и сохранено в БД (новых)
     */
    suspend fun loadAndSave(
        ticker: String,
        days: Int,
    ): LoadResult {
        val template = fundingConfig.moexHistoryUrl
        if (template.isNullOrBlank()) {
            logger.warn { "MoexFundingHistoryLoader: funding.moex-history-url не задан — донакачка off для $ticker" }
            return LoadResult(ticker = ticker, loaded = 0, saved = 0)
        }
        val effective = instrumentsConfig.find(ticker)?.effectiveTicker() ?: ticker
        val till = LocalDate.now()
        val from = till.minusDays(days.toLong())
        val records = ArrayList<FundingHistoryRecord>()
        var start = 0
        val pageSize = 100
        while (true) {
            val page =
                fetchPage(
                    template,
                    effective,
                    from,
                    till,
                    start,
                    ticker,
                )
            if (page.isEmpty()) break
            records.addAll(page)
            start += page.size
            if (page.size < pageSize || start > 200_000) break
        }
        val distinct = records.distinctBy { it.clearingDate }
        val saved = fundingHistoryRepository.saveAll(distinct)
        meterRegistry.counter("funding.history.loaded", Tags.of("ticker", ticker)).increment(distinct.size.toDouble())
        logger.info { "MoexFundingHistoryLoader $ticker: loaded=${distinct.size}, saved=$saved (days=$days)" }
        return LoadResult(ticker = ticker, loaded = distinct.size, saved = saved)
    }

    private suspend fun fetchPage(
        template: String,
        effectiveTicker: String,
        from: LocalDate,
        till: LocalDate,
        start: Int,
        originalTicker: String,
    ): List<FundingHistoryRecord> {
        val url =
            template
                .replace("{ticker}", effectiveTicker)
                .replace("{from}", from.format(dateFormatter))
                .replace("{till}", till.format(dateFormatter))
                .let { if (start > 0) "$it&start=$start" else it }
        return try {
            val raw: String =
                webClient
                    .get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String::class.java)
                    .timeout(Duration.ofMillis(fundingConfig.requestTimeoutMs))
                    .awaitSingle()
            parseHistory(raw, originalTicker)
        } catch (e: Exception) {
            logger.warn(e) { "MoexFundingHistoryLoader: MOEX history failed for $originalTicker at start=$start" }
            emptyList()
        }
    }

    /**
     * Парсит ISS history ответ: все строки блока, содержащего столбец
     * [FundingConfig.moexColumn] (SWAPRATE). Дата — из столбца `TRADEDATE`,
     * конвертация raw → RUB/контракт/клиринг умножителем [FundingConfig.moexLotMultiplier].
     */
    internal fun parseHistory(
        raw: String,
        ticker: String,
    ): List<FundingHistoryRecord> {
        val root = objectMapper.readTree(raw)
        val block = firstBlockWithColumn(root, fundingConfig.moexColumn) ?: return emptyList()
        val columns = block.path("columns").toList().map { it.asString() }
        val dateIdx = columns.indexOf("TRADEDATE")
        val rateIdx = columns.indexOf(fundingConfig.moexColumn)
        if (dateIdx < 0 || rateIdx < 0) return emptyList()
        val result = ArrayList<FundingHistoryRecord>()
        for (row in block.path("data")) {
            if (!row.isArray) continue
            val dateNode = row.get(dateIdx)?.asString()
            val rateNode = row.get(rateIdx)?.asString()
            if (dateNode.isNullOrBlank() || rateNode.isNullOrBlank()) continue
            val date = runCatching { LocalDate.parse(dateNode) }.getOrNull() ?: continue
            val rawValue = rateNode.toBigDecimalOrNull() ?: continue
            result.add(
                FundingHistoryRecord(
                    ticker = ticker,
                    clearingDate = date,
                    rawValue = rawValue,
                    valueRubPerContract = rawValue.multiply(fundingConfig.moexLotMultiplier),
                    source = "MOEX",
                ),
            )
        }
        return result.sortedBy { it.clearingDate }
    }

    private fun firstBlockWithColumn(
        root: JsonNode,
        column: String,
    ): JsonNode? {
        val properties = root.properties()
        for (property in properties) {
            val block = property.value
            val columns =
                block
                    .path("columns")
                    .toList()
                    .map { it.asString() }
            if (columns.contains(column) && block.path("data").isArray) return block
        }
        return null
    }
}
