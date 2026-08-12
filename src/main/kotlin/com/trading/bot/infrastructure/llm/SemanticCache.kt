package com.trading.bot.infrastructure.llm

import com.trading.bot.config.LlmConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.Duration
import java.time.LocalTime

/**
 * Semantic Cache поверх Redis.
 *
 * Ключ: SHA-256("agent:ticker:marketFingerprint").
 *
 * marketFingerprint описывает рыночные условия, инвариантные к шуму:
 *  - цена (округление до 1 знака)
 *  - RSI-бакет (по 10 пунктов)
 *  - тренд (UP/DOWN/SIDEWAYS)
 *  - режим волатильности (ATR)
 *  - направление MACD-гистограммы
 *  - ATR-перцентиль (LOW/MED/HIGH)
 *  - сессия дня (MORNING/DAY/EVENING)
 *
 * Одинаковая рыночная ситуация -> одинаковый ключ -> попадание в кэш.
 * TTL по умолчанию из `llm.semantic-cache-ttl-minutes` (30 мин в проде).
 * Хранит [LlmResponse] сериализованным в JSON.
 */
@Component
class SemanticCache(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
    private val llmConfig: LlmConfig,
) {
    private val logger = KotlinLogging.logger {}
    private val prefix = "llm:semantic:"

    private val ttl: Duration
        get() = Duration.ofMinutes(llmConfig.semanticCacheTtlMinutes)

    /**
     * Семантический отпечаток рынка для инвариантности ключа.
     *
     * @param price текущая цена инструмента
     * @param rsi значение RSI
     * @param trend направление тренда (UP/DOWN/SIDEWAYS)
     * @param volatilityRegime режим волатильности (LOW/MEDIUM/HIGH)
     * @param macdHistogram значение MACD-гистограммы (NaN — не учитывать)
     * @param atrPercentile перцентиль ATR относительно окна свечей (0..100, -1 — не учитывать)
     * @param session торговая сессия (по умолчанию — текущая)
     */
    fun fingerprint(
        price: BigDecimal,
        rsi: Double,
        trend: String,
        volatilityRegime: String,
        macdHistogram: Double = Double.NaN,
        atrPercentile: Int = -1,
        session: String = sessionOf(LocalTime.now()),
    ): String {
        val pricePart = price.setScale(1, RoundingMode.HALF_UP).toPlainString()
        val rsiBucket = (rsi.coerceIn(0.0, 100.0) / 10).toInt().coerceIn(0, 10)
        val macdPart =
            when {
                macdHistogram.isNaN() -> "MNA"
                macdHistogram > 0.0 -> "M+"
                macdHistogram < 0.0 -> "M-"
                else -> "M0"
            }
        val atrPart =
            when {
                atrPercentile < 0 -> "AN"
                atrPercentile <= 25 -> "AL"
                atrPercentile <= 75 -> "AM"
                else -> "AH"
            }
        return "$pricePart:$rsiBucket:$trend:$volatilityRegime:$macdPart:$atrPart:$session"
    }

    /**
     * Универсальный отпечаток из произвольных компонент (для агентов без
     * рыночных индикаторов, например Fundamental). Разделитель — двоеточие.
     */
    fun genericFingerprint(vararg components: Any?): String = components.joinToString(":") { it?.toString() ?: "NA" }

    /**
     * Ключ кэша. `namespace` изолирует область значений (например "backtest"):
     * бэктест не читает/не пишет live-кэш, иначе исторический бар мог бы получить
     * «будущий» ответ (look-ahead bias) и наоборот — live мог бы получить
     * бэктест-ответ по похожему отпечатку.
     */
    fun key(
        agent: String,
        ticker: String,
        fingerprint: String,
        namespace: String? = null,
    ): String {
        val ns = if (namespace.isNullOrBlank()) "" else ":$namespace"
        val raw = "$agent:$ticker$ns:$fingerprint"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return prefix + digest.joinToString("") { "%02x".format(it) }
    }

    fun get(
        agent: String,
        ticker: String,
        fingerprint: String,
        namespace: String? = null,
    ): LlmResponse? {
        if (!llmConfig.semanticCacheEnabled) {
            meterRegistry.counter("llm.cache.miss", Tags.of("agent", agent)).increment()
            return null
        }
        val key = key(agent, ticker, fingerprint, namespace)
        return try {
            redisTemplate.opsForValue().get(key)?.let { json ->
                objectMapper.readValue(json, LlmResponse::class.java).copy(fromCache = true).also {
                    meterRegistry.counter("llm.cache.hit", Tags.of("agent", agent)).increment()
                    logger.debug { "Semantic cache HIT $agent:$ticker (${it.latencyMs}ms)" }
                }
            } ?: run {
                meterRegistry.counter("llm.cache.miss", Tags.of("agent", agent)).increment()
                null
            }
        } catch (e: Exception) {
            logger.warn(e) { "Semantic cache read error for $agent:$ticker" }
            meterRegistry.counter("llm.cache.error", Tags.of("agent", agent)).increment()
            null
        }
    }

    fun put(
        agent: String,
        ticker: String,
        fingerprint: String,
        response: LlmResponse,
        namespace: String? = null,
    ) {
        if (!llmConfig.semanticCacheEnabled || response.isFallback) return
        val key = key(agent, ticker, fingerprint, namespace)
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(response), ttl)
            logger.debug { "Semantic cache PUT $agent:$ticker ttl=${ttl.seconds}s" }
        } catch (e: Exception) {
            logger.warn(e) { "Semantic cache write error for $agent:$ticker" }
            meterRegistry.counter("llm.cache.error", Tags.of("agent", agent)).increment()
        }
    }

    /** Сессия дня для ключа кэша: MORNING (<13ч), DAY (13-16ч), EVENING (>16ч). */
    private fun sessionOf(time: LocalTime): String =
        when {
            time.hour < 13 -> "MORNING"
            time.hour < 16 -> "DAY"
            else -> "EVENING"
        }
}
