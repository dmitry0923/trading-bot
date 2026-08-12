package com.trading.bot.client

import com.trading.bot.config.AlorConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.Instant

/**
 * Общий источник access-токена Alor для всех транспортов (REST и WS).
 *
 * Кэширует токен до истечения [AlorConfig.token], при необходимости продлевает
 * через refresh-токен (`/oauth/token`). Единственный экземпляр состояния токена
 * в приложении — [AlorClient] (quotes/reconciliation) и [RestOrderTransport]
 * используют его, а [WsOrderTransport] берёт токен из [AlorConfig.token]
 * (WS-команды и подписки).
 */
@Component
class AlorTokenProvider(
    private val alorConfig: AlorConfig,
    private val objectMapper: ObjectMapper,
) {
    private val logger = KotlinLogging.logger {}
    private val webClient = WebClient.create()

    private var accessToken: String = ""
    private var tokenExpiresAt: Instant = Instant.EPOCH

    suspend fun actualToken(): String {
        if (Instant.now().isBefore(tokenExpiresAt.minusSeconds(60)) && accessToken.isNotBlank()) {
            return accessToken
        }
        if (accessToken.isBlank()) accessToken = alorConfig.token
        if (alorConfig.refreshToken.isBlank()) return accessToken

        return try {
            val body = mapOf("refreshToken" to alorConfig.refreshToken)
            val raw: String =
                webClient
                    .post()
                    .uri("${alorConfig.apiUrl}/oauth/token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(objectMapper.writeValueAsString(body))
                    .retrieve()
                    .bodyToMono(String::class.java)
                    .timeout(Duration.ofSeconds(5))
                    .awaitSingle()

            val j = objectMapper.readTree(raw)
            accessToken = j.path("accessToken").asString(accessToken)
            val expiresIn = j.path("expiresIn").asLong(3600)
            tokenExpiresAt = Instant.now().plusSeconds(expiresIn)
            logger.info { "Alor access token refreshed (expires in ${expiresIn}s)" }
            accessToken
        } catch (e: Exception) {
            logger.warn(e) { "Token refresh failed, using existing token" }
            accessToken
        }
    }
}
