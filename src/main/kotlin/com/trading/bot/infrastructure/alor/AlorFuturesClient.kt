package com.trading.bot.infrastructure.alor

import com.trading.bot.config.AlorConfig
import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.config.RiskConfig
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
import java.util.concurrent.ConcurrentHashMap

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
 *
 * Свежесть (P0): в LIVE значения кэшируются с TTL [RiskConfig.maxGoAgeMs].
 * Данные в пределах TTL отдаются из кэша без повторного запроса; по истечении
 * TTL выполняется новый запрос к API. При недоступности API И устаревшем кэше
 * возвращается null (fail-closed) — сайзинг по устаревшему ГО/балансу запрещён.
 */
@Component
class AlorFuturesClient(
    private val alorConfig: AlorConfig,
    private val tradingConfig: TradingConfig,
    private val objectMapper: ObjectMapper,
    private val instrumentsConfig: InstrumentsConfig,
    private val meterRegistry: MeterRegistry,
    private val riskConfig: RiskConfig,
) {
    private val logger = KotlinLogging.logger {}
    private val webClient = WebClient.create()

    private val isLive: Boolean get() = tradingConfig.mode == "LIVE"
    private val defaultPortfolioMoney: BigDecimal = BigDecimal("50000")

    private data class TimedValue(
        val value: BigDecimal,
        val fetchedAtMs: Long,
    )

    private val goCache = ConcurrentHashMap<String, TimedValue>()
    private val moneyCache = ConcurrentHashMap<String, TimedValue>()

    private fun fresh(
        timed: TimedValue?,
        nowMs: Long,
    ): Boolean = timed != null && nowMs - timed.fetchedAtMs <= riskConfig.maxGoAgeMs

    /**
     * Текущее GO фьючерса.
     *
     * @return конфиг-GO в SIMULATION; реальное GO в LIVE; null в LIVE при ошибке
     *   API (и устаревшем кэше) или отсутствии поля initialMargin (P1: нельзя
     *   сайзить от устаревшего конфиг-GO — вход блокируется, как при недоступном
     *   капитале EXEC-005).
     */
    suspend fun getFuturesGO(ticker: String): BigDecimal? {
        val configGo = instrumentsConfig.find(ticker)?.go ?: BigDecimal("15000")
        if (!isLive) return configGo

        val now = System.currentTimeMillis()
        val cached = goCache[ticker]
        if (fresh(cached, now)) return cached!!.value

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

            goCache[ticker] = TimedValue(go, System.currentTimeMillis())
            MutableGauges.set(meterRegistry, "futures.go", go.toDouble(), Tags.of("ticker", ticker))
            logger.info { "Futures GO for $ticker = $go ₽" }
            go
        } catch (e: Exception) {
            // Устаревший кэш в LIVE НЕ переиспользуется: вход блокируется (fail-closed).
            logger.warn(e) { "getFuturesGO failed for $ticker (no config fallback in LIVE, stale cache rejected)" }
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
     *   ошибке API (и устаревшем кэше) или отсутствии баланса в ответе
     *   (EXEC-005: блокировать вход, а не сайзить от фиктивных 50k).
     */
    suspend fun getPortfolioMoney(portfolio: String = alorConfig.portfolio): BigDecimal? {
        if (!isLive) return defaultPortfolioMoney

        val now = System.currentTimeMillis()
        val cached = moneyCache[portfolio]
        if (fresh(cached, now)) return cached!!.value

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

            moneyCache[portfolio] = TimedValue(money, System.currentTimeMillis())
            MutableGauges.set(meterRegistry, "futures.portfolio.money", money.toDouble())
            logger.info { "Portfolio money = $money ₽" }
            money
        } catch (e: Exception) {
            // Устаревший кэш в LIVE НЕ переиспользуется: вход блокируется (fail-closed).
            logger.warn(e) { "getPortfolioMoney failed (no fallback in LIVE, stale cache rejected)" }
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
