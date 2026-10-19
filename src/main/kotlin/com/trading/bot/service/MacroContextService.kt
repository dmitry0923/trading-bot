package com.trading.bot.service

import com.trading.bot.config.MacroConfig
import com.trading.bot.infrastructure.metrics.MutableGauges
import com.trading.bot.repository.CbrRatesHistoryRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Поставщик макроэкономического контекста для фундаментального анализа.
 *
 * - Курс USD/RUB запрашивается вживую с MOEX (валютный рынок).
 * - Ключевая ставка ЦБ берётся из ряда ЦБ (`cbr_rates_history`) при
 *   `macro.live-rate-enabled=true` (проектный дефолт); при выключенном флаге,
 *   пустом ряде или ошибке БД — статичная `macro.cbr-rate` (graceful degradation).
 *   Ряд обновляет [com.trading.bot.infrastructure.cbr.CbrRatesRefreshScheduler].
 * - Нефть Brent берётся из конфига (macro.*) — надёжного публичного REST-источника нет.
 *
 * При любой ошибке внешнего запроса используется конфиг — бот не падает.
 */
@Service
class MacroContextService(
    private val macroConfig: MacroConfig,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
    private val cbrRatesHistoryRepository: CbrRatesHistoryRepository? = null,
    private val clock: Clock = Clock.system(ZoneId.of("Europe/Moscow")),
) {
    private val logger = KotlinLogging.logger {}
    private val webClient = WebClient.create()

    data class MacroContext(
        val cbrRate: BigDecimal,
        val brentPrice: BigDecimal,
        val usdRub: BigDecimal,
    )

    /**
     * Формирует актуальный макро-контекст: курс USD/RUB вживую с MOEX,
     * ставка ЦБ — из ряда ЦБ при `macro.live-rate-enabled` (иначе конфиг),
     * нефть Brent — из конфига. Обновляет метрики macro.*.
     *
     * @return макро-контекст (ставка, Brent, USD/RUB)
     */
    suspend fun fetch(): MacroContext {
        val liveUsdRub = fetchUsdRubLive()
        val ctx =
            MacroContext(
                cbrRate = latestKeyRateOrConfig(),
                brentPrice = macroConfig.brentPrice,
                usdRub = liveUsdRub ?: macroConfig.usdRub,
            )
        MutableGauges.set(meterRegistry, "macro.usd_rub", ctx.usdRub.toDouble())
        MutableGauges.set(meterRegistry, "macro.cbr_rate", ctx.cbrRate.toDouble())
        MutableGauges.set(meterRegistry, "macro.brent", ctx.brentPrice.toDouble())
        return ctx
    }

    /**
     * Ключевая ставка ЦБ: последняя из [CbrRatesHistoryRepository] при
     * `macro.live-rate-enabled=true`, иначе статичная `macro.cbr-rate`.
     * Fail-soft: пустой ряд или ошибка БД → статичная ставка (live-контур не падает).
     *
     * Устаревание (старше `macro.live-rate-stale-days`) **не** подменяется конфигом:
     * ключевая ставка — ступенчатая функция, поэтому последнее наблюдение остаётся
     * лучшей оценкой, а статичный конфиг не свежее. Вместо подмены — WARN в логе и
     * метрика `macro.cbr_rate.live{status=STALE}` (отказ донакачки виден, но не
     * меняет ставку молча).
     */
    internal suspend fun latestKeyRateOrConfig(): BigDecimal {
        if (!macroConfig.liveRateEnabled) return macroConfig.cbrRate
        val repository = cbrRatesHistoryRepository ?: return macroConfig.cbrRate
        val latest =
            try {
                repository.findLatestKeyRateRecord()
            } catch (e: Exception) {
                logger.warn(e) { "CBR key rate read failed, using config default ${macroConfig.cbrRate}" }
                meterRegistry.counter("macro.cbr_rate.live", Tags.of("status", "FALLBACK")).increment()
                return macroConfig.cbrRate
            }
        val liveRate = latest?.keyRate
        if (liveRate == null) {
            meterRegistry.counter("macro.cbr_rate.live", Tags.of("status", "EMPTY")).increment()
            return macroConfig.cbrRate
        }
        val ageDays = ChronoUnit.DAYS.between(latest.rateDate, LocalDate.now(clock))
        if (ageDays > macroConfig.liveRateStaleDays) {
            logger.warn {
                "CBR key rate is stale: latest=${latest.rateDate} age=${ageDays}d " +
                    "exceeds macro.live-rate-stale-days=${macroConfig.liveRateStaleDays} " +
                    "- check CbrRatesRefreshScheduler / CBR truststore"
            }
            meterRegistry.counter("macro.cbr_rate.live", Tags.of("status", "STALE")).increment()
            return liveRate
        }
        meterRegistry.counter("macro.cbr_rate.live", Tags.of("status", "OK")).increment()
        logger.info { "CBR key rate from history: $liveRate (${latest.rateDate})" }
        return liveRate
    }

    private suspend fun fetchUsdRubLive(): BigDecimal? =
        try {
            val url =
                "https://iss.moex.com/iss/engines/currency/markets/seld/boards/CETS/securities/" +
                    "${macroConfig.usdRubTicker}.json?iss.meta=off&iss.only=marketdata&marketdata.columns=SECID,LAST"
            val raw: String =
                webClient
                    .get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String::class.java)
                    .timeout(Duration.ofSeconds(5))
                    .awaitSingle()

            val last =
                objectMapper
                    .readTree(raw)
                    .path("marketdata")
                    .path("data")
                    .takeIf { it.isArray && it.size() > 0 }
                    ?.get(0)
                    ?.get(1)
                    ?.asString()
            last?.toBigDecimalOrNull()?.also {
                meterRegistry.counter("macro.usd_rub.live", Tags.of("status", "OK")).increment()
                logger.info { "USD/RUB live: $it" }
            }
        } catch (e: Exception) {
            logger.warn(e) { "USD/RUB live fetch failed, using config default ${macroConfig.usdRub}" }
            meterRegistry.counter("macro.usd_rub.live", Tags.of("status", "FALLBACK")).increment()
            null
        }
}
