package com.trading.bot.infrastructure.http

import io.netty.channel.ChannelOption
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Scope
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import reactor.netty.resources.ConnectionProvider
import java.time.Duration

/**
 * Единый HTTP-пул для исходящих [WebClient] (P2-5 аудита).
 *
 * Раньше каждый сервис создавал `WebClient.create()` — собственный коннектор
 * и собственный пул соединений на каждый экземпляр. Теперь все клиенты строятся
 * из одного [ConnectionProvider]: соединения переиспользуются между Alor REST,
 * MOEX, ЦБ, новостями и микроструктурой, а «висящие» соединения закрываются
 * по `maxIdleTime`.
 *
 * - `maxIdleTime=45s` — Alor/MOEX держат keep-alive, но не бесконечно;
 * - `evictInBackground=15s` — фоновое закрытие простаивающих соединений;
 * - `CONNECT_TIMEOUT_MILLIS=3000` — быстрый fail вместо зависания на SYN.
 *
 * Response-timeout остаётся на уровне конкретных вызовов (`.timeout(...)`):
 * разные API имеют разные SLA (LLM 30s, MOEX 10s, CBR 10s).
 */
@Configuration
class HttpClientConfig {
    /**
     * Prototype: каждый компонент получает свой immutable builder, но все они
     * используют общий [ConnectionProvider] из [SharedHttpClient].
     */
    @Bean
    @Scope("prototype")
    fun webClientBuilder(): WebClient.Builder = SharedHttpClient.builder()
}

/** Фабрика WebClient с общим пулом; также служит default-значением для тестов. */
object SharedHttpClient {
    private val connectionProvider =
        ConnectionProvider
            .builder("bot-http")
            .maxIdleTime(Duration.ofSeconds(45))
            .evictInBackground(Duration.ofSeconds(15))
            .build()

    private val httpClient =
        HttpClient
            .create(connectionProvider)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3_000)

    fun builder(): WebClient.Builder =
        WebClient
            .builder()
            .clientConnector(ReactorClientHttpConnector(httpClient))
}
