package com.trading.bot.application.funding

import com.trading.bot.config.FundingConfig
import com.trading.bot.config.InstrumentsConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * LIVE-источник funding из MOEX ISS: запрашивает [FundingConfig.moexUrl] (плейсхолдер
 * `{ticker}`) и извлекает значение funding из столбца [FundingConfig.moexColumn]
 * (таблица `columns`/`data` стандартного ISS-ответа).
 *
 * Значение на выходе — raw (в единицах источника); конвертация в RUB/контракт/клиринг —
 * [FundingConfig.moexLotMultiplier] (CNYRUBF: публикуемая ставка × 1000 CNY =
 * RUB/контракт).
 *
 * Недоступность (пустой URL / ошибка API / отсутствие столбца / невалидное число) →
 * null → [FundingSnapshotService] помечает клиринги за сегодня как FUNDING_UNKNOWN
 * (метрика `funding.live.provider_unavailable`); в LIVE CONFIG не подставляется.
 * Эндпоинт/поле/множитель — PROVISIONAL, сверяются с фактическими данными MOEX ДО LIVE
 * (docs/16).
 */
@Component
class MoexFundingProvider(
    private val fundingConfig: FundingConfig,
    private val instrumentsConfig: InstrumentsConfig,
    private val objectMapper: ObjectMapper,
) : FundingProvider {
    private val logger = KotlinLogging.logger {}
    private val webClient = WebClient.create()

    override suspend fun currentSnapshot(ticker: String): FundingSnapshot? {
        val template = fundingConfig.moexUrl
        if (template.isNullOrBlank()) return null
        val url =
            template.replace(
                "{ticker}",
                instrumentsConfig.find(ticker)?.effectiveTicker() ?: ticker,
            )
        return try {
            val raw: String =
                webClient
                    .get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String::class.java)
                    .timeout(Duration.ofMillis(fundingConfig.requestTimeoutMs))
                    .awaitSingle()
            val rawValue = parseFundingValue(raw, fundingConfig.moexColumn)
            if (rawValue == null) {
                logger.warn { "Funding column '${fundingConfig.moexColumn}' missing/non-numeric for $ticker" }
                return null
            }
            FundingSnapshot(
                ticker = ticker,
                clearingDate = LocalDate.now(),
                rawValue = rawValue,
                unit = FundingUnit.RAW_UNKNOWN,
                valueRubPerContractPerClearing = rawValue.multiply(fundingConfig.moexLotMultiplier),
                source = FundingSource.MOEX,
                timestamp = LocalDateTime.now(),
            )
        } catch (e: Exception) {
            logger.warn(e) { "Funding (MOEX) fetch failed for $ticker" }
            null
        }
    }

    /**
     * Извлекает значение funding из ISS-ответа: первый блок с `columns`/`data`,
     * столбец [column], первая строка таблицы.
     */
    internal fun parseFundingValue(
        raw: String,
        column: String,
    ): BigDecimal? {
        val root = objectMapper.readTree(raw)
        val block = firstBlockWithColumnsAndData(root) ?: return null
        val columns = block.path("columns").toList().map { it.asString() }
        val idx = columns.indexOf(column)
        if (idx < 0) return null
        val firstRow = block.path("data").path(0)
        if (firstRow.isMissingNode || firstRow.isEmpty) return null
        val node = firstRow.get(idx)
        if (node == null || node.isNull || node.asString().isBlank()) return null
        return node.asString().toBigDecimalOrNull()
    }

    private fun firstBlockWithColumnsAndData(root: JsonNode): JsonNode? {
        val properties = root.properties()
        for (property in properties) {
            val block = property.value
            if (block.has("columns") && block.has("data") && block.path("data").isArray) return block
        }
        return null
    }
}
