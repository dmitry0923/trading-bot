package com.trading.bot.service

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component

/**
 * Build-identity для execution interlock (P1): immutable git SHA сборки.
 *
 * Резолвит полный git-коммит из стандартного ресурса `git.properties`
 * (генерируется git-commit-id плагином), либо из переменной окружения
 * `GIT_COMMIT`. Если ни одно недоступно — возвращает null.
 *
 * MANIFEST `implementationVersion` (например "2.0.0") НЕ используется как
 * build-identity: это не git SHA, и его подстановка сделала бы поле
 * `gitCommitSha()` нечестным. Отсутствие настоящего commit SHA у текущего
 * процесса => [gitCommitSha] возвращает null => [LiveFrozenStrategyResolver]
 * fail-closed отказывает (DENY), не легитимизируя исполнение.
 *
 * Цель: автоматическая инвалидация одобрения при новой сборке — разработчику не
 * нужно вручную повышать версию стратегии, чтобы старый LIVE-approve не
 * «легитимизировал» неперевалидированную логику.
 */
@Component
class BuildIdentity(
    private val gitPropertiesResource: ClassPathResource = ClassPathResource("git.properties"),
) {
    private val logger = KotlinLogging.logger {}

    @Volatile
    private var resolved: String? = null

    private val envOverride: String? = System.getenv("GIT_COMMIT")?.takeIf { it.isNotBlank() }

    fun gitCommitSha(): String? {
        resolved?.let { return it }
        synchronized(this) {
            resolved?.let { return it }
            val value =
                envOverride
                    ?: readFromClasspath()
            resolved = value
            if (value != null) {
                logger.info { "Build identity: gitCommit=${value.take(12)}..." }
            } else {
                logger.info { "Build identity: git.properties/GIT_COMMIT not found — LIVE execution disabled (fail-closed)" }
            }
            return value
        }
    }

    private fun readFromClasspath(): String? =
        try {
            if (!gitPropertiesResource.exists()) return null
            gitPropertiesResource
                .inputStream
                .use { stream ->
                    stream
                        .bufferedReader()
                        .useLines { lines ->
                            lines
                                .mapNotNull { line ->
                                    val kv = line.split("=", limit = 2)
                                    if (kv.size == 2) kv[0].trim() to kv[1].trim() else null
                                }.firstOrNull { (k, _) -> k == "git.commit.id.full" || k == "git.commit.id" }
                                ?.second
                        }
                }
        } catch (e: Exception) {
            logger.warn { "Failed to read git.properties: ${e.message}" }
            null
        }
}
