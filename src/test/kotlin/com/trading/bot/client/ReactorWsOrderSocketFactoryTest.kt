package com.trading.bot.client

import com.trading.bot.config.AlorConfig
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Unit-тесты [ReactorWsOrderSocketFactory].
 *
 * Регресс: WS-URL канала ордеров собирался из статического [AlorConfig.token],
 * поэтому после refresh соединение открывалось с протухшим токеном (401 →
 * транспорт уходил в вечный reconnect). URL обязан содержать
 * [AlorTokenProvider.actualToken].
 */
class ReactorWsOrderSocketFactoryTest {
    @Test
    fun `ws url keeps an existing query string with ampersand`(): Unit =
        runBlocking {
            val requestLines = CopyOnWriteArrayList<String>()
            val connected = CountDownLatch(1)
            val serverSocket = ServerSocket(0)
            val acceptor =
                thread(isDaemon = true) {
                    try {
                        serverSocket.accept().use { socket ->
                            val reader = socket.getInputStream().bufferedReader()
                            val line = reader.readLine()
                            if (line != null) requestLines += line
                        }
                    } finally {
                        connected.countDown()
                    }
                }
            try {
                val alorConfig =
                    AlorConfig().apply {
                        wsUrl = "ws://127.0.0.1:${serverSocket.localPort}/ws?x=1"
                    }
                val tokenProvider = Mockito.mock(AlorTokenProvider::class.java) { _ -> "tok" }
                val factory = ReactorWsOrderSocketFactory(alorConfig, tokenProvider)

                runCatching { factory.open() }

                assertTrue(connected.await(10, TimeUnit.SECONDS))
                assertTrue(
                    requestLines.any { it.contains("GET /ws?x=1&token=tok") },
                    "request line: $requestLines",
                )
            } finally {
                serverSocket.close()
                acceptor.join(1_000)
            }
        }

    @Test
    fun `open returns a sendable connection and closes it`(): Unit =
        runBlocking {
            val received = AtomicInteger()
            val server =
                reactor.netty.http.server.HttpServer
                    .create()
                    .host("127.0.0.1")
                    .port(0)
                    .handle { _, response ->
                        response.sendWebsocket { inbound, outbound ->
                            inbound
                                .receive()
                                .doOnNext { received.incrementAndGet() }
                                .then()
                        }
                    }.bindNow()
            try {
                val alorConfig =
                    AlorConfig().apply {
                        wsUrl = "ws://127.0.0.1:${server.port()}/ws"
                    }
                val tokenProvider = Mockito.mock(AlorTokenProvider::class.java) { _ -> "tok" }
                val factory = ReactorWsOrderSocketFactory(alorConfig, tokenProvider)

                val connection = factory.open()
                runCatching { connection.send("""{"opcode":"ping"}""") }
                val deadline = System.currentTimeMillis() + 5_000
                while (received.get() == 0 && System.currentTimeMillis() < deadline) {
                    Thread.sleep(20)
                }
                runCatching { connection.close() }

                assertTrue(received.get() >= 1, "received frames=${received.get()}")
            } finally {
                server.disposeNow()
            }
        }

    @Test
    fun `open uses refreshed token from token provider in ws url`(): Unit =
        runBlocking {
            val requestLines = CopyOnWriteArrayList<String>()
            val connected = CountDownLatch(1)
            val serverSocket = ServerSocket(0)
            val acceptor =
                thread(isDaemon = true) {
                    try {
                        serverSocket.accept().use { socket ->
                            val reader = socket.getInputStream().bufferedReader()
                            val line = reader.readLine()
                            if (line != null) requestLines += line
                        }
                    } finally {
                        connected.countDown()
                    }
                }
            try {
                val alorConfig =
                    AlorConfig().apply {
                        wsUrl = "ws://127.0.0.1:${serverSocket.localPort}/ws"
                        token = "stale-token"
                    }
                val tokenProvider = Mockito.mock(AlorTokenProvider::class.java) { _ -> "refreshed-token" }
                val factory = ReactorWsOrderSocketFactory(alorConfig, tokenProvider)

                // Соединение заведомо не завершится (сервер не делает WS-хендшейк) — важен сам запрос.
                runCatching { factory.open() }

                assertTrue(connected.await(10, TimeUnit.SECONDS), "WS-клиент должен открыть TCP-соединение")
                assertTrue(
                    requestLines.any { it.contains("GET /ws?token=refreshed-token") },
                    "WS URL должен нести токен из AlorTokenProvider, получено: $requestLines",
                )
            } finally {
                serverSocket.close()
                acceptor.join(1_000)
            }
        }
}
