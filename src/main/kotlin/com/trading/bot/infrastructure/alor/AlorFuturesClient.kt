package com.trading.bot.infrastructure.alor

import com.fasterxml.jackson.databind.ObjectMapper
import com.trading.bot.config.AlorConfig
import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.config.TradingConfig
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import kotlinx.coroutines.reactor.awaitSingle
import mu.KotlinLogging
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import java.math.BigDecimal
import java.time.Duration

/**
 * Расширение Alor REST-клиента для фьючерсов.
 *
 * - getFuturesGO(ticker): текущее гарантийное обеспечение через
 *   GET /md/v2/Securities/MOEX/{ticker}/risk
 *   Fallback: instruments.*.go из конфига, если API недоступен.
 * - getPortfolioMoney(): свободные средства портфеля.
 *   Fallback: 50 000 ₽ (депозит по умолчанию) в SIMULATION.
 *
 * В SIMULATION режиме все вызовы возвращают значения из конфига.
 */
@Component
class AlorFuturesClient(
    private val alorConfig: AlorConfig,
    private val tradingConfig: TradingConfig,
    private val objectMapper: ObjectMapper,
    private val instrumentsConfig: InstrumentsConfig,
    private val meterRegistry: MeterRegistry,
) {
    private val logger = KotlinLogging.logger {}
    private val webClient = WebClient.create()

    private val isLive: Boolean get() = tradingConfig.mode == "LIVE"
    private val defaultPortfolioMoney: BigDecimal = BigDecimal("50000")

    /**
     * Текущее GO фьючерса Si. Fallback на конфиг при любой ошибке.
     */
    suspend fun getFuturesGO(ticker: String): BigDecimal {
        val configGo = instrumentsConfig.find(ticker)?.go ?: BigDecimal("15000")
        if (!isLive) return configGo

        return try {
            val raw: String =
                webClient
                    .get()
                    .uri("${alorConfig.apiUrl}/md/v2/Securities/${alorConfig.exchange}/$ticker/risk")
                    .header("Authorization", "Bearer ${alorConfig.token}")
                    .retrieve()
                    .bodyToMono(String::class.java)
                    .timeout(Duration.ofSeconds(10))
                    .awaitSingle()

            val j = objectMapper.readTree(raw)
            val go =
                j
                    .path("long")
                    .path("initialMargin")
                    .asText()
                    .takeIf { it.isNotBlank() }
                    ?.toBigDecimalOrNull()
                    ?: configGo

            meterRegistry.gauge("futures.go", Tags.of("ticker", ticker), go.toDouble())
            logger.info { "Futures GO for $ticker = $go ₽" }
            go
        } catch (e: Exception) {
            logger.warn(e) { "getFuturesGO failed for $ticker, using config fallback $configGo" }
            configGo
        }
    }

    /**
     * Свободные средства портфеля (buying power).
     */
    suspend fun getPortfolioMoney(): BigDecimal {
        if (!isLive) return defaultPortfolioMoney

        return try {
            val raw: String =
                webClient
                    .get()
                    .uri("${alorConfig.apiUrl}/md/v2/Clients/${alorConfig.portfolio}/summaries")
                    .header("Authorization", "Bearer ${alorConfig.token}")
                    .retrieve()
                    .bodyToMono(String::class.java)
                    .timeout(Duration.ofSeconds(10))
                    .awaitSingle()

            val j = objectMapper.readTree(raw)
            val money =
                j.path("moneyAmount").asText().toBigDecimalOrNull()
                    ?: j.path("money").asText().toBigDecimalOrNull()
                    ?: defaultPortfolioMoney

            meterRegistry.gauge("futures.portfolio.money", money.toDouble())
            logger.info { "Portfolio money = $money ₽" }
            money
        } catch (e: Exception) {
            logger.warn(e) { "getPortfolioMoney failed, using default $defaultPortfolioMoney" }
            defaultPortfolioMoney
        }
    }
}
