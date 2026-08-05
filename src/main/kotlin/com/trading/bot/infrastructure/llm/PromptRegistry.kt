package com.trading.bot.infrastructure.llm

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import tools.jackson.dataformat.yaml.YAMLFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Prompt Registry — единая точка доступа к промптам агентов.
 *
 * Загружает YAML-файлы из classpath:prompts (шаблон *.yml) при старте.
 * Структура файла:
 *   prompts:
 *     default:      { system: "...", user_template: "..." }
 *     conservative: { system: "...", user_template: "..." }
 *     aggressive:   { system: "...", user_template: "..." }
 *
 * Имя промпта = имя файла без расширения (например technical-analysis).
 * Шаблоны кэшируются в ConcurrentHashMap. Поддерживается версионирование.
 */
@Component
class PromptRegistry {
    private val logger = KotlinLogging.logger {}
    private val cache = ConcurrentHashMap<String, PromptTemplate>()
    private val yamlMapper = ObjectMapper(YAMLFactory())

    init {
        load()
    }

    private fun key(
        name: String,
        version: String,
    ) = "$name::$version"

    @Synchronized
    fun load() {
        cache.clear()
        val resolver = PathMatchingResourcePatternResolver()
        val resources =
            resolver.getResources("classpath:prompts/*.yml") +
                resolver.getResources("classpath:prompts/*.yaml")
        if (resources.isEmpty()) {
            logger.warn { "No prompt files found in classpath:prompts/ — PromptRegistry is empty" }
            return
        }
        for (resource in resources) {
            try {
                val filename =
                    resource.filename
                        ?.removeSuffix(".yml")
                        ?.removeSuffix(".yaml")
                        ?: continue
                val tree = yamlMapper.readTree(resource.inputStream)
                val promptsNode = tree.path("prompts")
                if (!promptsNode.isObject) {
                    logger.warn { "Prompt file $filename has no 'prompts' object, skipping" }
                    continue
                }
                var loaded = 0
                promptsNode.properties().forEach { (version, v) ->
                    val template =
                        PromptTemplate(
                            name = filename,
                            version = version,
                            system = v.path("system").asString(""),
                            userTemplate = v.path("user_template").asString(""),
                        )
                    cache[key(filename, version)] = template
                    loaded++
                }
                logger.info { "PromptRegistry loaded $loaded version(s) from $filename" }
            } catch (e: Exception) {
                logger.error(e) { "Failed to parse prompt file ${resource.filename}" }
            }
        }
        logger.info { "PromptRegistry total templates: ${cache.size}" }
    }

    /** Возвращает шаблон по имени и версии (по умолчанию default). */
    fun getTemplate(
        name: String,
        version: String = DEFAULT_VERSION,
    ): PromptTemplate =
        cache[key(name, version)]
            ?: cache[key(name, DEFAULT_VERSION)]
            ?: throw NoSuchElementException(
                "Prompt template not found: name=$name, version=$version. " +
                    "Available: ${availableNames()}",
            )

    /** Все загруженные имена промптов (для диагностики). */
    fun availableNames(): List<String> =
        cache.keys
            .map { it.substringBefore("::") }
            .distinct()
            .sorted()

    companion object {
        const val DEFAULT_VERSION = "default"
        const val CONSERVATIVE_VERSION = "conservative"
    }
}
