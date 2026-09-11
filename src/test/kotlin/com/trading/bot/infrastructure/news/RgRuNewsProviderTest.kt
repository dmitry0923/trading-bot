package com.trading.bot.infrastructure.news

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.trading.bot.config.NewsConfig
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

/**
 * RgRuNewsProvider: live-источник новостей эмитентов (платная подписка).
 * Покрытие: парсинг ответа (хорошая/плохая новость), ограничение по времени и
 * количеству, 401 (нет подписки) → пустой список + метрика, выключенный
 * конфиг → пустой список. HTTP замокан JDK HttpServer (см. MoexFundingProviderTest).
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

    private fun provider(
        server: HttpServer?,
        config: NewsConfig,
        meter: SimpleMeterRegistry = SimpleMeterRegistry(),
    ): RgRuNewsProvider {
        if (server != null) {
            config.baseUrl = "http://127.0.0.1:${server.address.port}/news/{ticker}?hours={hours}"
        }
        config.apiKey = "test-key"
        return RgRuNewsProvider(config, objectMapper, redis, meter, fixedClock)
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
