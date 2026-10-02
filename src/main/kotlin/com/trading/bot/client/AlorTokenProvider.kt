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
import java.util.Base64

/**
 * Общий источник access-токена Alor для всех транспортов (REST и WS).
 *
 * Кэширует токен до истечения срока (refresh за 60 с до конца), при необходимости
 * продлевает его refresh-токеном через [AlorConfig.oauthUrl]
 * (`POST oauth.alor.ru/refresh?token=<refresh>`, в ответе — `AccessToken`;
 * прежний `api.alor.ru/oauth/token` отвечает 404 и больше не используется).
 * Единственный экземпляр состояния токена в приложении: [AlorClient]
 * (quotes/reconciliation), [RestOrderTransport] и [AlorWebSocketClient]
 * (WS-команды и подписки) берут токен отсюда.
 *
 * Успешный refresh публикуется обратно в [AlorConfig.token], поэтому потребители,
 * читающие конфигурацию напрямую, не работают с протухшим токеном.
 *
 * Fail-safe: при ошибке refresh возвращается ранее известный токен
 * (fail-soft — торговля не должна падать из-за непродления токена).
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
            val raw: String =
                webClient
                    .post()
                    .uri("${alorConfig.oauthUrl}?token=${alorConfig.refreshToken}")
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .bodyToMono(String::class.java)
                    .timeout(Duration.ofSeconds(5))
                    .awaitSingle()

            val j = objectMapper.readTree(raw)
            val refreshed = j.path("AccessToken").asString("").ifBlank { j.path("accessToken").asString("") }
            if (refreshed.isBlank()) {
                logger.warn { "Token refresh returned no AccessToken, using existing token" }
                return accessToken
            }
            accessToken = refreshed
            tokenExpiresAt = expiryFromJwt(refreshed)
            alorConfig.token = refreshed
            logger.info { "Alor access token refreshed (expires at $tokenExpiresAt)" }
            accessToken
        } catch (e: Exception) {
            logger.warn(e) { "Token refresh failed, using existing token" }
            accessToken
        }
    }

    /**
     * Срок действия из JWT-claim `exp`; при отсутствии — константа [FALLBACK_TTL_SECONDS]
     * (Alor выдаёт access-токен на 30 минут и не возвращает TTL в теле ответа).
     */
    private fun expiryFromJwt(token: String): Instant {
        val parts = token.split(".")
        if (parts.size == 3) {
            try {
                val payload = String(Base64.getUrlDecoder().decode(parts[1]))
                val exp = objectMapper.readTree(payload).path("exp").asLong(0L)
                if (exp > 0L) return Instant.ofEpochSecond(exp)
            } catch (e: Exception) {
                logger.debug(e) { "Cannot read exp claim from access token, using fallback TTL" }
            }
        }
        return Instant.now().plusSeconds(FALLBACK_TTL_SECONDS)
    }

    private companion object {
        /** Alor не отдаёт TTL в теле refresh-ответа; access-токен живёт 30 минут. */
        const val FALLBACK_TTL_SECONDS = 1_800L
    }
}
