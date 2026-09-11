package com.trading.bot.infrastructure.news

import com.trading.bot.config.NewsConfig
import com.trading.bot.model.dto.NewsItem
import io.github.oshai.kotlinlogging.KotlinLogging
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
import java.time.ZoneId

/**
 * Источник новостей эмитентов rg.ru (платная подписка в LIVE).
 *
 * Fail-closed: при любом сбое/401/403/невалидном ответе возвращает пустой список —
 * фундаментальный агент получает NEUTRAL-базу без новостей, вход не блокируется.
 * Ответ кэшируется в Redis (ключ `news:rgru:{ticker}:{hours}`, TTL `news.ttl-minutes`).
 */
@Component
class RgRuNewsProvider(
    private val newsConfig: NewsConfig,
    private val objectMapper: ObjectMapper,
    private val redisTemplate: StringRedisTemplate,
    private val meterRegistry: MeterRegistry,
    private val clock: java.time.Clock = java.time.Clock.system(ZoneId.of("Europe/Moscow")),
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
        return try {
            val raw: String =
                webClient
                    .get()
                    .uri(url)
                    .header("Authorization", newsConfig.apiKey)
                    .retrieve()
                    .bodyToMono(String::class.java)
                    .timeout(Duration.ofMillis(newsConfig.timeoutMs))
                    .awaitSingle()
            parse(raw, hours)
        } catch (e: Exception) {
            val message = e.message ?: ""
            if ("401" in message || "403" in message) {
                logger.warn { "rg.ru subscription unauthorized/no access for $ticker (401/403)" }
                meterRegistry.counter("news.provider.unauthorized", Tags.of("ticker", ticker)).increment()
            } else {
                logger.warn(e) { "rg.ru news fetch failed for $ticker" }
                meterRegistry.counter("news.provider.error", Tags.of("ticker", ticker)).increment()
            }
            emptyList()
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
                    .filter { item -> item.publishedAt == null || item.publishedAt.isAfter(now.minusMillis(hours * 3_600_000L)) }
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
        return NewsItem(
            title = title,
            url = firstString(node, "url", "link"),
            publishedAt = parseInstant(node),
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

    /** ISO-8601 (`yyyy-MM-dd'T'HH:mm:ss[.SSS][Z]`) или `yyyy-MM-dd HH:mm:ss`. */
    private fun parseFlexible(raw: String): Instant? {
        val cleaned = raw.trim().replace(" ", "T")
        val normalized =
            if (cleaned.endsWith("Z") || cleaned.contains('+')) cleaned else "${cleaned}Z"
        return runCatching { Instant.parse(normalized) }
            .getOrElse {
                runCatching {
                    val date = LocalDate.parse(normalized.take(10))
                    date.atStartOfDay(ZoneId.of("Europe/Moscow")).toInstant()
                }.getOrNull()
            }
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
