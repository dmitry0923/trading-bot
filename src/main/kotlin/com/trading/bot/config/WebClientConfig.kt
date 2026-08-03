package com.trading.bot.config
import io.netty.channel.ChannelOption
import io.netty.handler.timeout.ReadTimeoutHandler
import io.netty.handler.timeout.WriteTimeoutHandler
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import java.time.Duration
import java.util.concurrent.TimeUnit
@Configuration
class WebClientConfig {
    @Bean fun webClient(): WebClient {
        val http = HttpClient.create().option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 30000).responseTimeout(Duration.ofSeconds(60))
            .doOnConnected { it.addHandlerLast(ReadTimeoutHandler(60, TimeUnit.SECONDS)).addHandlerLast(WriteTimeoutHandler(60, TimeUnit.SECONDS)) }
        return WebClient.builder().clientConnector(ReactorClientHttpConnector(http)).build()
    }
}
