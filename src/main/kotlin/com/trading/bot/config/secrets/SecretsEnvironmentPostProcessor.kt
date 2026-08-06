package com.trading.bot.config.secrets

import org.springframework.boot.EnvironmentPostProcessor
import org.springframework.boot.SpringApplication
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource

/**
 * Подмешивает секреты из Yandex Lockbox в окружение ДО создания контекста,
 * чтобы `${ALOR_TOKEN}`, `${LLM_API_KEY}`, `${AUTH_PASSWORD}` и т.д.
 * резолвились из Lockbox (и перекрывали env-переменные).
 *
 * Включение: `lockbox.enabled=true` + `lockbox.secret-id` (см. application.yml).
 * При включённом режиме и ошибке загрузки — контекст не стартует (fail-fast).
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
class SecretsEnvironmentPostProcessor : EnvironmentPostProcessor {
    override fun postProcessEnvironment(
        environment: ConfigurableEnvironment,
        application: SpringApplication,
    ) {
        if (!enabled(environment)) return
        val resolver =
            LockboxSecretResolver(
                secretId = value(environment, "lockbox.secret-id", ""),
                iamToken = value(environment, "lockbox.iam-token", ""),
                saKeyJson = value(environment, "lockbox.sa-key-json", ""),
            )
        val secrets =
            try {
                resolver.resolve()
            } catch (e: Exception) {
                throw IllegalStateException("Failed to load secrets from Yandex Lockbox (lockbox.enabled=true)", e)
            }
        environment.propertySources.addFirst(MapPropertySource("lockbox-secrets", secrets))
    }

    private fun enabled(environment: ConfigurableEnvironment): Boolean = value(environment, "lockbox.enabled", "false").toBoolean()

    private fun value(
        environment: ConfigurableEnvironment,
        key: String,
        default: String,
    ): String {
        val raw = environment.getProperty(key) ?: return default
        return environment.resolvePlaceholders(raw)
    }
}
