package com.trading.bot.infrastructure.alor

import com.trading.bot.config.AlorConfig
import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.config.TradingConfig
import com.trading.bot.infrastructure.metrics.MutableGauges
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.time.Duration

/**
 * Расширение Alor REST-клиента для фьючерсов.
 *
 * - getFuturesGO(ticker): текущее гарантийное обеспечение через
 *   GET /md/v2/Securities/MOEX/{ticker}/risk
 *   Fallback: instruments.*.go из конфига, если API недоступен.
 * - getPortfolioMoney(): свободные средства портфеля.
 *   SIMULATION: 50 000 ₽ (депозит по умолчанию).
 *   LIVE: реальный баланс, null при ошибке API / отсутствии поля — нельзя
 *   использовать fallback-капитал для сайзинга (EXEC-005).
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
     * Текущее GO фьючерса.
     *
     * @return конфиг-GO в SIMULATION; реальное GO в LIVE; null в LIVE при ошибке
     *   API или отсутствии поля initialMargin (P1: нельзя сайзить от устаревшего
     *   конфиг-GO — вход блокируется, как при недоступном капитале EXEC-005).
     */
    suspend fun getFuturesGO(ticker: String): BigDecimal? {
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

            val go = parseFuturesGo(raw)
            if (go == null) {
                logger.warn { "getFuturesGO: initialMargin field missing for $ticker" }
                return null
            }

            MutableGauges.set(meterRegistry, "futures.go", go.toDouble(), Tags.of("ticker", ticker))
            logger.info { "Futures GO for $ticker = $go ₽" }
            go
        } catch (e: Exception) {
            logger.warn(e) { "getFuturesGO failed for $ticker (no config fallback in LIVE)" }
            null
        }
    }

    /** Разбор GO из /md/v2/Securities/{exchange}/{ticker}/risk. null при отсутствии поля. */
    internal fun parseFuturesGo(raw: String): BigDecimal? =
        runCatching {
            val j = objectMapper.readTree(raw)
            val initialMargin =
                j
                    .path("long")
                    .path("initialMargin")
                    .asString()
            initialMargin
                .takeIf { it.isNotBlank() }
                ?.toBigDecimalOrNull()
        }.getOrNull()

    /**
     * Свободные средства портфеля (buying power).
     *
     * @return баланс в LIVE, конфиг-депозит в SIMULATION, null в LIVE при
     *   ошибке API или отсутствии баланса в ответе (EXEC-005: блокировать вход,
     *   а не сайзить от фиктивных 50k).
     */
    suspend fun getPortfolioMoney(portfolio: String = alorConfig.portfolio): BigDecimal? {
        if (!isLive) return defaultPortfolioMoney

        return try {
            val raw: String =
                webClient
                    .get()
                    .uri("${alorConfig.apiUrl}/md/v2/Clients/$portfolio/summaries")
                    .header("Authorization", "Bearer ${alorConfig.token}")
                    .retrieve()
                    .bodyToMono(String::class.java)
                    .timeout(Duration.ofSeconds(10))
                    .awaitSingle()

            val money = parsePortfolioMoney(raw)
            if (money == null) {
                logger.warn { "getPortfolioMoney: balance field missing in API response" }
                return null
            }

            MutableGauges.set(meterRegistry, "futures.portfolio.money", money.toDouble())
            logger.info { "Portfolio money = $money ₽" }
            money
        } catch (e: Exception) {
            logger.warn(e) { "getPortfolioMoney failed" }
            null
        }
    }

    /** Разбор баланса из /md/v2/Clients/{portfolio}/summaries. null при отсутствии поля. */
    internal fun parsePortfolioMoney(raw: String): BigDecimal? =
        runCatching {
            val j = objectMapper.readTree(raw)
            j.path("moneyAmount").asString().toBigDecimalOrNull()
                ?: j.path("money").asString().toBigDecimalOrNull()
        }.getOrNull()
}
