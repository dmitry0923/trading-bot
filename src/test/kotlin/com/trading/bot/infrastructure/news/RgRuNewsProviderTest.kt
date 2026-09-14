package com.trading.bot.infrastructure.news

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.trading.bot.config.NewsConfig
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.ratelimiter.RateLimiterRegistry
import io.github.resilience4j.retry.RetryRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.springframework.data.redis.core.StringRedisTemplate
import tools.jackson.databind.ObjectMapper
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

/**
 * RgRuNewsProvider: live-источник новостей эмитентов (платная подписка).
 * Покрытие: парсинг ответа (хорошая/плохая новость), ограничение по времени и
 * количеству, 401 (нет подписки) → пустой список + метрика, выключенный
 * конфиг → пустой список, dedup по url/title, resilience (retry при 5xx,
 * 429 → throttled-метрика). HTTP замокан JDK HttpServer (см. MoexFundingProviderTest).
 */
class RgRuNewsProviderTest {
    private val objectMapper = ObjectMapper()
    private val fixedClock =
        Clock.fixed(Instant.parse("2026-09-11T10:00:00Z"), ZoneId.of("Europe/Moscow"))
    private val redis: StringRedisTemplate = mock()

    @Test
    fun `parses good news into items`() =
        runBlocking {
            val body =
                """[{"title":"Газпром удвоил дивиденды","publishedAt":"2026-09-11T08:00:00Z",""" +
                    """"url":"https://x","snippet":"положительная"},""" +
                    """{"title":"Сбер отчитался о росте прибыли","publishedAt":"2026-09-10T12:00:00Z"}]"""
            val server = jsonServer(body)
            try {
                val provider = provider(server, NewsConfig().apply { enabled = true })
                val items = provider.newsFor("GAZP", 24)

                assertEquals(2, items.size)
                assertEquals("Газпром удвоил дивиденды", items[0].title)
                assertTrue(items[0].snippet.contains("положительная"))
                assertEquals("https://x", items[0].url)
            } finally {
                server.stop(0)
            }
        }

    @Test
    fun `filters out old news beyond hours window`() =
        runBlocking {
            val body =
                """[{"title":"новое","publishedAt":"2026-09-11T09:00:00Z"},""" +
                    """{"title":"старое","publishedAt":"2026-09-01T09:00:00Z"}]"""
            val server = jsonServer(body)
            try {
                val provider = provider(server, NewsConfig().apply { enabled = true })
                val items = provider.newsFor("GAZP", 24)

                assertEquals(1, items.size)
                assertEquals("новое", items[0].title)
            } finally {
                server.stop(0)
            }
        }

    @Test
    fun `limits items to maxItems in recency order`() =
        runBlocking {
            val body =
                (1..5).joinToString(",") { i ->
                    """{"title":"n$i","publishedAt":"2026-09-11T0$i:00:00Z"}"""
                }
            val server = jsonServer("[$body]")
            val config = NewsConfig()
            config.enabled = true
            config.maxItems = 3
            try {
                val provider = provider(server, config)
                val items = provider.newsFor("GAZP", 24)

                assertEquals(3, items.size)
                assertEquals("n5", items[0].title)
            } finally {
                server.stop(0)
            }
        }

    @Test
    fun `deduplicates items with same url`() =
        runBlocking {
            val body =
                """[{"title":"a","url":"https://x","publishedAt":"2026-09-11T09:00:00Z"},""" +
                    """{"title":"b","url":"https://x","publishedAt":"2026-09-11T08:00:00Z"}]"""
            val server = jsonServer(body)
            try {
                val provider = provider(server, NewsConfig().apply { enabled = true })
                val items = provider.newsFor("GAZP", 24)

                assertEquals(1, items.size)
                assertEquals("a", items[0].title)
            } finally {
                server.stop(0)
            }
        }

    @Test
    fun `retries transient 5xx and returns items`() =
        runBlocking {
            val hits = AtomicInteger(0)
            val server = flakyServer(hits)
            val config = NewsConfig().apply { enabled = true }
            try {
                val provider = provider(server, config, resilience = true)
                val items = provider.newsFor("GAZP", 24)

                assertEquals(1, items.size)
                assertTrue(hits.get() >= 2)
            } finally {
                server.stop(0)
            }
        }

    @Test
    fun `429 rate limit yields empty list and throttled metric`() =
        runBlocking {
            val server = jsonServer("", statusCode = 429)
            try {
                val meter = SimpleMeterRegistry()
                val provider = provider(server, NewsConfig().apply { enabled = true }, meter, resilience = true)
                val items = provider.newsFor("GAZP", 24)

                assertTrue(items.isEmpty())
                assertEquals(1.0, meter.counter("news.provider.throttled", "ticker", "GAZP").count())
            } finally {
                server.stop(0)
            }
        }

    @Test
    fun `401 unauthorized yields empty list and metric`() =
        runBlocking {
            val server = jsonServer("", statusCode = 401)
            try {
                val meter = SimpleMeterRegistry()
                val provider = provider(server, NewsConfig().apply { enabled = true }, meter)
                val items = provider.newsFor("GAZP", 24)

                assertTrue(items.isEmpty())
                assertEquals(1.0, meter.counter("news.provider.unauthorized", "ticker", "GAZP").count())
            } finally {
                server.stop(0)
            }
        }

    @Test
    fun `disabled config yields empty list without http call`() =
        runBlocking {
            val meter = SimpleMeterRegistry()
            val provider = provider(null, NewsConfig(), meter)

            assertTrue(provider.newsFor("GAZP", 24).isEmpty())
            assertEquals(1.0, meter.counter("news.provider.disabled", "ticker", "GAZP").count())
        }

    @Test
    fun `unparsable response yields empty list`() =
        runBlocking {
            val server = jsonServer("not-json{{")
            try {
                val provider = provider(server, NewsConfig().apply { enabled = true })
                assertTrue(provider.newsFor("GAZP", 24).isEmpty())
            } finally {
                server.stop(0)
            }
        }

    @Test
    fun `parses naive timestamps as Moscow timezone not UTC`() =
        runBlocking {
            val body =
                """[{"title":"наивное время","publishedAt":"2026-09-11 08:00:00"},""" +
                    """{"title":"с явным offset","publishedAt":"2026-09-11T08:00:00+03:00"}]"""
            val server = jsonServer(body)
            try {
                val provider = provider(server, NewsConfig().apply { enabled = true })
                val items = provider.newsFor("GAZP", 24)

                assertEquals(2, items.size)
                // naive "08:00" трактуется как МСК (UTC+3): 05:00 UTC, а не 08:00 UTC.
                assertEquals(Instant.parse("2026-09-11T05:00:00Z"), items.first { it.title == "наивное время" }.publishedAt)
                assertEquals(Instant.parse("2026-09-11T05:00:00Z"), items.first { it.title == "с явным offset" }.publishedAt)
            } finally {
                server.stop(0)
            }
        }

    @Test
    fun `drops news without publish date to avoid look-ahead bias`() =
        runBlocking {
            val body =
                """[{"title":"без даты","snippet":"когда опубликовано — неизвестно"},""" +
                    """{"title":"с датой","publishedAt":"2026-09-11T09:00:00Z"}]"""
            val server = jsonServer(body)
            try {
                val provider = provider(server, NewsConfig().apply { enabled = true })
                val items = provider.newsFor("GAZP", 24)

                assertEquals(1, items.size)
                assertEquals("с датой", items.single().title)
            } finally {
                server.stop(0)
            }
        }

    private fun provider(
        server: HttpServer?,
        config: NewsConfig,
        meter: SimpleMeterRegistry = SimpleMeterRegistry(),
        resilience: Boolean = false,
    ): RgRuNewsProvider {
        if (server != null) {
            config.baseUrl = "http://127.0.0.1:${server.address.port}/news/{ticker}?hours={hours}"
        }
        config.apiKey = "test-key"
        config.resilienceEnabled = resilience
        return RgRuNewsProvider(
            config,
            objectMapper,
            redis,
            meter,
            fixedClock,
            RetryRegistry.ofDefaults(),
            RateLimiterRegistry.ofDefaults(),
            CircuitBreakerRegistry.ofDefaults(),
        )
    }

    private fun jsonServer(
        body: String,
        statusCode: Int = 200,
    ): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> respond(exchange, body, statusCode) }
        server.start()
        return server
    }

    /** Сервер: первый запрос отвечает 500, последующие — валидным телом. */
    private fun flakyServer(hits: AtomicInteger): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            if (hits.incrementAndGet() == 1) {
                respond(exchange, "", 500)
            } else {
                respond(exchange, """[{"title":"после ретрая","url":"https://y","publishedAt":"2026-09-11T09:00:00Z"}]""", 200)
            }
        }
        server.start()
        return server
    }

    private fun respond(
        exchange: HttpExchange,
        body: String,
        statusCode: Int,
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        if (statusCode in 200..299) {
            exchange.sendResponseHeaders(statusCode, bytes.size.toLong())
            exchange.responseBody.write(bytes)
        } else {
            exchange.sendResponseHeaders(statusCode, -1)
        }
        exchange.close()
    }
}
