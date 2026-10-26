package com.trading.bot.client

import com.sun.net.httpserver.HttpServer
import com.trading.bot.config.AlorConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit-тесты [AlorTokenProvider].
 *
 * Регресс P0: `actualToken()` не был потокобезопасным — параллельные вызовы
 * (REST + WS-потоки + futures-клиент) при протухшем токене запускали N
 * одновременных refresh, ротируя токен N раз (часть вызовов уходила с уже
 * отозванным токеном). Refresh обязан быть ровно один.
 */
class AlorTokenProviderTest {
    @Test
    fun `concurrent actualToken when token expired refreshes exactly once`(): Unit =
        runBlocking {
            val logins = AtomicInteger()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.executor = Executors.newCachedThreadPool()
            server.createContext("/refresh") { exchange ->
                logins.incrementAndGet()
                // Держим ответ, чтобы оба вызова гарантированно попали в окно refresh.
                Thread.sleep(150)
                val body = """{"AccessToken":"fresh-token"}""".toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            server.start()
            try {
                val alorConfig =
                    AlorConfig().apply {
                        oauthUrl = "http://127.0.0.1:${server.address.port}/refresh"
                        token = "stale-token"
                        refreshToken = "refresh-token"
                    }
                val provider = AlorTokenProvider(alorConfig, jacksonObjectMapper())

                val tokens =
                    coroutineScope {
                        listOf(
                            async(Dispatchers.IO) { provider.actualToken() },
                            async(Dispatchers.IO) { provider.actualToken() },
                        ).awaitAll()
                    }

                assertEquals(listOf("fresh-token", "fresh-token"), tokens)
                assertEquals(1, logins.get(), "refresh должен выполниться ровно один раз")
                assertEquals("fresh-token", alorConfig.token, "успешный refresh публикуется в конфиг")
            } finally {
                server.stop(0)
            }
        }

    @Test
    fun `cached fresh token does not hit oauth endpoint`(): Unit =
        runBlocking {
            val logins = AtomicInteger()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.executor = Executors.newCachedThreadPool()
            server.createContext("/refresh") { exchange ->
                logins.incrementAndGet()
                val body = """{"AccessToken":"fresh-token"}""".toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            server.start()
            try {
                val alorConfig =
                    AlorConfig().apply {
                        oauthUrl = "http://127.0.0.1:${server.address.port}/refresh"
                        token = "stale-token"
                        refreshToken = "refresh-token"
                    }
                val provider = AlorTokenProvider(alorConfig, jacksonObjectMapper())

                assertEquals("fresh-token", provider.actualToken())
                assertEquals("fresh-token", provider.actualToken())
                assertEquals(1, logins.get(), "свежий токен отдаётся из кэша без HTTP")
            } finally {
                server.stop(0)
            }
        }

    /**
     * Регресс: при сбое refresh `tokenExpiresAt` оставался EPOCH, поэтому КАЖДЫЙ
     * `actualToken()` брал мьютекс и делал 5-секундную HTTP-попытку (head-of-line
     * blocking всех REST/WS-вызовов заказа — «непрерывный цикл Token refresh failed»).
     * В окне backoff попыток должно быть РОВНО одна.
     */
    @Test
    fun `failed refresh is throttled to one http attempt within backoff window`(): Unit =
        runBlocking {
            val attempts = AtomicInteger()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.executor = Executors.newCachedThreadPool()
            server.createContext("/refresh") { exchange ->
                attempts.incrementAndGet()
                exchange.sendResponseHeaders(500, -1)
                exchange.close()
            }
            server.start()
            try {
                val alorConfig =
                    AlorConfig().apply {
                        oauthUrl = "http://127.0.0.1:${server.address.port}/refresh"
                        token = "stale-token"
                        refreshToken = "refresh-token"
                    }
                val provider = AlorTokenProvider(alorConfig, jacksonObjectMapper())

                assertEquals("stale-token", provider.actualToken())
                assertEquals("stale-token", provider.actualToken())
                assertEquals("stale-token", provider.actualToken())

                assertEquals(1, attempts.get(), "в окне backoff HTTP-refresh выполняется один раз")
            } finally {
                server.stop(0)
            }
        }

    @Test
    fun `refresh is retried after backoff expires`(): Unit =
        runBlocking {
            val attempts = AtomicInteger()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.executor = Executors.newCachedThreadPool()
            server.createContext("/refresh") { exchange ->
                if (attempts.incrementAndGet() == 1) {
                    exchange.sendResponseHeaders(500, -1)
                } else {
                    val body = """{"AccessToken":"fresh-token"}""".toByteArray()
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                }
                exchange.close()
            }
            server.start()
            try {
                val alorConfig =
                    AlorConfig().apply {
                        oauthUrl = "http://127.0.0.1:${server.address.port}/refresh"
                        token = "stale-token"
                        refreshToken = "refresh-token"
                    }
                val provider = AlorTokenProvider(alorConfig, jacksonObjectMapper())

                assertEquals("stale-token", provider.actualToken())
                assertEquals(1, attempts.get())

                // Первая пауза backoff — 1 с.
                Thread.sleep(1_100)

                assertEquals("fresh-token", provider.actualToken())
                assertEquals(2, attempts.get(), "после backoff refresh повторяется")
            } finally {
                server.stop(0)
            }
        }

    @Test
    fun `invalidate forces a refresh before the token expiry`(): Unit =
        runBlocking {
            // 401 от биржи не должен ждать истечения TTL (~30 мин): инвалидация
            // заставляет следующий вызов сходить за новым токеном (скан-5 P2).
            val logins = AtomicInteger()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.executor = Executors.newCachedThreadPool()
            server.createContext("/refresh") { exchange ->
                val n = logins.incrementAndGet()
                val body = """{"AccessToken":"token-$n"}""".toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            server.start()
            try {
                val alorConfig =
                    AlorConfig().apply {
                        oauthUrl = "http://127.0.0.1:${server.address.port}/refresh"
                        token = "stale-token"
                        refreshToken = "refresh-token"
                    }
                val provider = AlorTokenProvider(alorConfig, jacksonObjectMapper())

                assertEquals("token-1", provider.actualToken())
                assertEquals("token-1", provider.actualToken(), "свежий токен не перезапрашивается")

                provider.invalidate()

                assertEquals("token-2", provider.actualToken())
                assertEquals(2, logins.get(), "после invalidate выполняется новый refresh")
            } finally {
                server.stop(0)
            }
        }
}
