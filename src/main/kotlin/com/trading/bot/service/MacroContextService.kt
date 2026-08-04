package com.trading.bot.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.trading.bot.config.MacroConfig
import com.trading.bot.infrastructure.metrics.MutableGauges
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import kotlinx.coroutines.reactor.awaitSingle
import mu.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import java.math.BigDecimal
import java.time.Duration

/**
 * Поставщик макроэкономического контекста для фундаментального анализа.
 *
 * - Курс USD/RUB запрашивается вживую с MOEX (валютный рынок).
 * - Ключевая ставка ЦБ и нефть Brent берутся из конфига (macro.*) с env-оверрайдами —
 *   надёжных и стабильных публичных REST-источников для них нет, поэтому fallback в конфиг.
 *
 * При любой ошибке внешнего запроса используется конфиг (graceful degradation) — бот не падает.
 */
@Service
class MacroContextService(
    private val macroConfig: MacroConfig,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry
) {
    private val logger = KotlinLogging.logger {}
    private val webClient = WebClient.create()
    private val gauges = MutableGauges(meterRegistry)

    data class MacroContext(
        val cbrRate: BigDecimal,
        val brentPrice: BigDecimal,
        val usdRub: BigDecimal
    )

    /**
     * Формирует актуальный макро-контекст: курс USD/RUB вживую с MOEX,
     * ставка ЦБ и нефть Brent из конфига. Обновляет метрики macro.*.
     *
     * @return макро-контекст (ставка, Brent, USD/RUB)
     */
    suspend fun fetch(): MacroContext {
        val liveUsdRub = fetchUsdRubLive()
        val ctx = MacroContext(
            cbrRate = macroConfig.cbrRate,
            brentPrice = macroConfig.brentPrice,
            usdRub = liveUsdRub ?: macroConfig.usdRub
        )
        gauges.set("macro.usd_rub", ctx.usdRub)
        gauges.set("macro.cbr_rate", ctx.cbrRate)
        gauges.set("macro.brent", ctx.brentPrice)
        return ctx
    }

    private suspend fun fetchUsdRubLive(): BigDecimal? {
        return try {
            val url = "https://iss.moex.com/iss/engines/currency/markets/seld/boards/CETS/securities/" +
                "${macroConfig.usdRubTicker}.json?iss.meta=off&iss.only=marketdata&marketdata.columns=SECID,LAST"
            val raw: String = webClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(String::class.java)
                .timeout(Duration.ofSeconds(5))
                .awaitSingle()

            val last = objectMapper.readTree(raw)
                .path("marketdata").path("data")
                .takeIf { it.isArray && it.size() > 0 }?.get(0)
                ?.get(1)?.asText()
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
}
