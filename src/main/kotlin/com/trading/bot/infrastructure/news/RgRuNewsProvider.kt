package com.trading.bot.infrastructure.news

import com.trading.bot.config.NewsConfig
import com.trading.bot.model.dto.NewsItem
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.kotlin.circuitbreaker.decorateSuspendFunction
import io.github.resilience4j.kotlin.ratelimiter.decorateSuspendFunction
import io.github.resilience4j.kotlin.retry.decorateSuspendFunction
import io.github.resilience4j.ratelimiter.RateLimiterRegistry
import io.github.resilience4j.retry.RetryRegistry
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Источник новостей эмитентов rg.ru (платная подписка в LIVE).
 *
 * Fail-closed: при любом сбое/401/403/невалидном ответе возвращает пустой список —
 * фундаментальный агент получает NEUTRAL-базу без новостей, вход не блокируется.
 * Ответ кэшируется в Redis (ключ `news:rgru:{ticker}:{hours}`, TTL `news.ttl-minutes`).
 *
 * Resilience (P0): при `news.resilience-enabled=true` HTTP-вызов обёрнут resilience4j
 * инстансами `rgru` (Retry + RateLimiter + CircuitBreaker, конфиг в application.yml):
 *   - 429/5xx/таймауты ретраятся (с экспоненциальным waitDuration по Retry-After-подобной логике);
 *   - 401/403 НЕ ретраятся (подписка закрыта — нет смысла долбить);
 *   - 429 после исчерпания ретраев логируется как `news.provider.throttled`.
 * Повторяющиеся новости дедуплицируются по url/title (contentHash).
 */
@Component
class RgRuNewsProvider(
    private val newsConfig: NewsConfig,
    private val objectMapper: ObjectMapper,
    private val redisTemplate: StringRedisTemplate,
    private val meterRegistry: MeterRegistry,
    private val clock: java.time.Clock = java.time.Clock.system(ZoneId.of("Europe/Moscow")),
    private val retryRegistry: RetryRegistry = RetryRegistry.ofDefaults(),
    private val rateLimiterRegistry: RateLimiterRegistry = RateLimiterRegistry.ofDefaults(),
    private val circuitBreakerRegistry: CircuitBreakerRegistry = CircuitBreakerRegistry.ofDefaults(),
) : IssuerDataProvider {
    private val logger = KotlinLogging.logger {}
    private val cachePrefix = "news:rgru:"
    private val webClient = WebClient.create()

    override suspend fun newsFor(
        ticker: String,
        hours: Int,
    ): List<NewsItem> {
        if (!newsConfig.enabled || newsConfig.baseUrl.isBlank() || newsConfig.apiKey.isBlank()) {
            meterRegistry.counter("news.provider.disabled", Tags.of("ticker", ticker)).increment()
            return emptyList()
        }

        val cacheKey = "$cachePrefix$ticker:$hours"
        cached(cacheKey)?.let {
            meterRegistry.counter("news.cache.hit", Tags.of("ticker", ticker)).increment()
            return it
        }
        meterRegistry.counter("news.cache.miss", Tags.of("ticker", ticker)).increment()

        val items = fetch(ticker, hours)
        putCache(cacheKey, items)
        meterRegistry.counter("news.provider.items", Tags.of("ticker", ticker, "count", items.size.toString())).increment()
        return items
    }

    private suspend fun fetch(
        ticker: String,
        hours: Int,
    ): List<NewsItem> {
        val url = newsConfig.baseUrl.replace("{ticker}", ticker).replace("{hours}", hours.toString())
        return queryRaw(url, ticker)?.let { parse(it, hours) } ?: emptyList()
    }

    /** HTTP-вызов с resilience-обвязкой; при ошибке — null (пустой список у вызывающего). */
    private suspend fun queryRaw(
        url: String,
        ticker: String,
    ): String? {
        val base: suspend () -> String =
            {
                webClient
                    .get()
                    .uri(url)
                    .header("Authorization", newsConfig.apiKey)
                    .retrieve()
                    .bodyToMono(String::class.java)
                    .timeout(Duration.ofMillis(newsConfig.timeoutMs))
                    .awaitSingle()
            }
        val decorated: suspend () -> String =
            if (newsConfig.resilienceEnabled) {
                val withRetry: suspend () -> String = retryRegistry.retry("rgru").decorateSuspendFunction(base)
                val withRateLimit: suspend () -> String = rateLimiterRegistry.rateLimiter("rgru").decorateSuspendFunction(withRetry)
                circuitBreakerRegistry.circuitBreaker("rgru").decorateSuspendFunction(withRateLimit)
            } else {
                base
            }
        return try {
            decorated()
        } catch (e: Exception) {
            handleFetchError(e, ticker)
            null
        }
    }

    private fun handleFetchError(
        e: Exception,
        ticker: String,
    ) {
        val message = e.message ?: ""
        when {
            "401" in message || "403" in message -> {
                logger.warn { "rg.ru subscription unauthorized/no access for $ticker (401/403)" }
                meterRegistry.counter("news.provider.unauthorized", Tags.of("ticker", ticker)).increment()
            }

            "429" in message -> {
                logger.warn { "rg.ru rate limited for $ticker (429), skip this cycle" }
                meterRegistry.counter("news.provider.throttled", Tags.of("ticker", ticker)).increment()
            }

            else -> {
                logger.warn(e) { "rg.ru news fetch failed for $ticker" }
                meterRegistry.counter("news.provider.error", Tags.of("ticker", ticker)).increment()
            }
        }
    }

    /** Толерантный парсер новостного ответа; при неудаче — пустой список. */
    private fun parse(
        raw: String,
        hours: Int,
    ): List<NewsItem> =
        try {
            val root = objectMapper.readTree(raw)
            val array = candidateArray(root)
            if (array == null) {
                logger.warn { "rg.ru news response has no recognized array key" }
                emptyList()
            } else {
                val now = Instant.now(clock)
                array
                    .mapNotNull { item -> toNewsItem(item) }
                    .filter { item -> (item.publishedAt?.isAfter(now.minusMillis(hours * 3_600_000L)) == true) }
                    .distinctBy { item -> item.url.ifBlank { item.title } }
                    .sortedByDescending { it.publishedAt ?: Instant.EPOCH }
                    .take(newsConfig.maxItems)
            }
        } catch (e: Exception) {
            logger.warn(e) { "rg.ru news parse failed" }
            emptyList()
        }

    private fun candidateArray(root: JsonNode): JsonNode? {
        if (root.isArray) return root
        for (key in listOf("news", "items", "data", "articles", "result")) {
            val node = root.path(key)
            if (node.isArray) return node
            if (node.isObject) {
                val inner = candidateArray(node)
                if (inner != null) return inner
            }
        }
        return null
    }

    private fun toNewsItem(node: JsonNode): NewsItem? {
        val title = node.path("title").asString("")
        if (title.isBlank()) return null
        // Review/P2: новость без даты публикации отбрасывается — в противном случае
        // look-ahead bias (неизвестно, когда новость была фактически известна рынку).
        val publishedAt = parseInstant(node) ?: return null
        return NewsItem(
            title = title,
            url = firstString(node, "url", "link"),
            publishedAt = publishedAt,
            snippet = firstString(node, "snippet", "text", "description", "summary"),
        )
    }

    private fun firstString(
        node: JsonNode,
        vararg keys: String,
    ): String =
        keys.firstNotNullOfOrNull { key -> node.path(key).asString("").ifBlank { null } }
            ?: ""

    private fun parseInstant(node: JsonNode): Instant? {
        for (key in listOf("publishedAt", "date", "published", "pub_date", "datetime")) {
            val raw = node.path(key).asString("")
            if (raw.isBlank()) continue
            parseFlexible(raw)?.let { return it }
        }
        return null
    }

    /**
     * ISO-8601 (`yyyy-MM-dd'T'HH:mm:ss[.SSS][Z|±HH:MM]`) или `yyyy-MM-dd HH:mm:ss`.
     *
     * Review/P2: naive-времена (без offset) трактуются как МСК (Europe/Moscow), а не
     * как UTC — rg.ru публикует время в московской часовой зоне. Раньше наивный
     * timestamp получал `Z` (трактовался как UTC) и сдвигался на +3 часа — окно
     * «свежести» и захват новостей были смещены.
     */
    private fun parseFlexible(raw: String): Instant? {
        val cleaned = raw.trim().replace(" ", "T")
        // Явный offset — как есть.
        runCatching { return Instant.parse(cleaned) }
        runCatching { return OffsetDateTime.parse(cleaned, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant() }
        // Без offset — время МСК.
        runCatching {
            val local = LocalDateTime.parse(cleaned)
            return local.atZone(ZoneId.of("Europe/Moscow")).toInstant()
        }
        runCatching {
            val date = LocalDate.parse(cleaned.take(10))
            return date.atStartOfDay(ZoneId.of("Europe/Moscow")).toInstant()
        }
        return null
    }

    private fun cached(key: String): List<NewsItem>? =
        try {
            redisTemplate.opsForValue().get(key)?.let { json ->
                objectMapper.readValue(json, object : TypeReference<List<NewsItem>>() {})
            }
        } catch (e: Exception) {
            logger.warn(e) { "rg.ru news cache read error" }
            null
        }

    private fun putCache(
        key: String,
        items: List<NewsItem>,
    ) {
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(items), Duration.ofMinutes(newsConfig.ttlMinutes))
        } catch (e: Exception) {
            logger.warn(e) { "rg.ru news cache write error" }
        }
    }
}
