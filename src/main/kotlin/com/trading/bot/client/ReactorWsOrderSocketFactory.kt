package com.trading.bot.client

import com.trading.bot.config.AlorConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.withTimeoutOrNull
import org.springframework.stereotype.Component
import org.springframework.web.reactive.socket.WebSocketSession
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient
import reactor.core.publisher.Mono
import java.net.URI
import java.time.Duration

/**
 * Реальная фабрика WS-соединений канала ордеров на ReactorNetty
 * (roadmap 13.8.2).
 *
 * [open] блокируется до установления соединения (таймаут 10с) и возвращает
 * [WsOrderSocketConnection]: входящие сообщения публикуются в канал, [WsOrderSocketConnection.send]
 * шлёт текст поверх установленной сессии. При обрыве сокета канал закрывается —
 * поток сообщений завершается, и [WsOrderTransport] переподключается.
 */
@Component
class ReactorWsOrderSocketFactory(
    private val alorConfig: AlorConfig,
) : WsOrderSocketFactory {
    private val wsClient = ReactorNettyWebSocketClient()
    private val connectTimeoutMs = 10_000L

    override suspend fun open(): WsOrderSocketConnection {
        val url = URI.create(wsUrl())
        val channel = Channel<String>(capacity = 1000, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        val sessionReady = CompletableDeferred<WebSocketSession>()

        wsClient
            .execute(url) { session ->
                sessionReady.complete(session)
                session
                    .receive()
                    .mapNotNull { msg -> msg.payloadAsText.takeIf { it.isNotBlank() } }
                    .doOnNext { text -> channel.trySend(text) }
                    .then()
            }.subscribe(
                { channel.close() },
                { err ->
                    sessionReady.completeExceptionally(err)
                    channel.close()
                },
            )

        val session =
            withTimeoutOrNull(connectTimeoutMs) { sessionReady.await() }
                ?: run {
                    channel.close()
                    throw OrderTransportUnavailableException("WS order socket connect timeout ($connectTimeoutMs ms)")
                }

        return object : WsOrderSocketConnection {
            override val messages: Flow<String> = channel.receiveAsFlow()

            override suspend fun send(text: String) {
                session.send(Mono.just(session.textMessage(text))).awaitSingle()
            }

            override suspend fun close() {
                session.close().onErrorResume { Mono.empty() }.awaitSingle()
                channel.close()
            }
        }
    }

    private fun wsUrl(): String {
        val base = alorConfig.wsUrl.removeSuffix("/")
        return if (base.contains("?")) "$base&token=${alorConfig.token}" else "$base?token=${alorConfig.token}"
    }
}
