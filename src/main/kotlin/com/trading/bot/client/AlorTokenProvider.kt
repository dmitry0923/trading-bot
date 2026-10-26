package com.trading.bot.client

import com.trading.bot.config.AlorConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * Потокобезопасность: поля токена `@Volatile`, а refresh сериализован мьютексом —
 * параллельные вызовы [actualToken] (REST + WS-потоки + futures-клиент) выполняют
 * РОВНО один HTTP-refresh, а не N (иначе токен ротируется N раз и часть вызовов
 * уходит с уже отозванным токеном).
 *
 * Fail-safe: при ошибке refresh возвращается ранее известный токен
 * (fail-soft — торговля не должна падать из-за непродления токена).
 *
 * Троттлинг сбоев: неудачный refresh не оставляет `tokenExpiresAt = EPOCH` как
 * единственный маркер (тогда КАЖДЫЙ вызов [actualToken] брал мьютекс и делал
 * 5-секундный HTTP — head-of-line blocking всех REST/WS-вызовов, наблюдалось
 * как непрерывный цикл «Token refresh failed»). После сбоя повторный HTTP
 * откладывается на экспоненциальный backoff ([REFRESH_BACKOFF_BASE_SECONDS] …
 * [REFRESH_BACKOFF_MAX_SECONDS]); в окне backoff возвращается последний
 * известный токен без мьютекса и без сети.
 */
@Component
class AlorTokenProvider(
    private val alorConfig: AlorConfig,
    private val objectMapper: ObjectMapper,
) {
    private val logger = KotlinLogging.logger {}
    private val webClient = WebClient.create()

    private val refreshMutex = Mutex()

    @Volatile
    private var accessToken: String = ""

    @Volatile
    private var tokenExpiresAt: Instant = Instant.EPOCH

    /** Раньше этого момента повторный refresh не выполняется (backoff после сбоя). */
    @Volatile
    private var nextAttemptAt: Instant = Instant.EPOCH

    /** Число подряд неудачных refresh — степень двойки для backoff (под мьютексом). */
    private var consecutiveFailures: Int = 0

    suspend fun actualToken(): String {
        if (isFresh()) return accessToken
        // Окно backoff: сеть и мьютекс не трогаем — отдаём последний известный токен,
        // иначе каждый вызов заново блокирует остальные REST/WS-вызовы на 5 с.
        if (isRefreshThrottled()) return lastKnownToken()
        return refreshMutex.withLock {
            // Double-check: пока ждали мьютекс, другой вызов мог уже обновить токен
            // (или наоборот — словить сбой и открыть окно backoff).
            if (isFresh()) return@withLock accessToken
            if (isRefreshThrottled()) return@withLock lastKnownToken()
            if (accessToken.isBlank()) accessToken = alorConfig.token
            if (alorConfig.refreshToken.isBlank()) return@withLock accessToken
            refreshLocked()
        }
    }

    private fun isFresh(): Boolean = accessToken.isNotBlank() && Instant.now().isBefore(tokenExpiresAt.minusSeconds(60))

    /** Абсолютный запрет refresh до [nextAttemptAt] (сбой уже случился). */
    private fun isRefreshThrottled(): Boolean = Instant.now().isBefore(nextAttemptAt)

    /**
     * Последний известный токен без мутации состояния: пустой кэш -> значение
     * из конфигурации (может быть протухшим — fail-soft, как и раньше).
     */
    private fun lastKnownToken(): String = accessToken.ifBlank { alorConfig.token }

    /** Refresh под захваченным [refreshMutex] — единственная точка HTTP-вызова. */
    private suspend fun refreshLocked(): String {
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
                onRefreshFailure()
                return accessToken
            }
            accessToken = refreshed
            tokenExpiresAt = expiryFromJwt(refreshed)
            consecutiveFailures = 0
            nextAttemptAt = Instant.EPOCH
            alorConfig.token = refreshed
            logger.info { "Alor access token refreshed (expires at $tokenExpiresAt)" }
            accessToken
        } catch (e: Exception) {
            logger.warn(e) { "Token refresh failed, using existing token" }
            onRefreshFailure()
            accessToken
        }
    }

    /**
     * Учитывает сбой refresh: экспоненциальный backoff 1, 2, 4, … 60 с.
     * Вызывается только под [refreshMutex] — счётчик не нуждается в атомарности.
     */
    private fun onRefreshFailure() {
        consecutiveFailures += 1
        val backoffSeconds =
            minOf(
                REFRESH_BACKOFF_MAX_SECONDS,
                REFRESH_BACKOFF_BASE_SECONDS shl (consecutiveFailures - 1).coerceAtMost(6),
            )
        nextAttemptAt = Instant.now().plusSeconds(backoffSeconds)
        logger.warn { "Token refresh throttled for ${backoffSeconds}s (failures=$consecutiveFailures)" }
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

        /** Первая пауза после сбоя refresh (экспоненциально удваивается). */
        const val REFRESH_BACKOFF_BASE_SECONDS = 1L

        /** Потолок backoff: не чаще одного HTTP-refresh в минуту при затяжном сбое. */
        const val REFRESH_BACKOFF_MAX_SECONDS = 60L
    }
}
