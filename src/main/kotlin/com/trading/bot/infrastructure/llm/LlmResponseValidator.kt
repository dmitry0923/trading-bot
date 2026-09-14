package com.trading.bot.infrastructure.llm

import org.springframework.stereotype.Component
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper

/**
 * Функциональный интерфейс валидатора LLM-ответов по JSON Schema.
 *
 * Объявлен интерфейсом, чтобы агенты могли заменить реализацию (например стабом
 * в тестах) и чтобы не зависеть от конкретной JSON-библиотеки.
 */
fun interface JsonSchemaValidator {
    /**
     * @return true, если [json] структурно соответствует [schema] (ограниченный
     * поднабор JSON Schema: type, properties, items, required, enum,
     * additionalProperties, minimum/maximum, minLength/maxLength).
     */
    fun isValid(
        json: String,
        schema: String,
    ): Boolean
}

/**
 * Схемы-контракты ответов LLM-агентов (P0, research/llm-signal-source).
 *
 * Публичная витрина контрактов: схему публикуем здесь, чтобы любой агент,
 * участвующий в [JsonSchemaValidator], и LlmPromptInjectionTest использовали
 * одну и ту же точку истины.
 */
object LlmResponseSchemas {
    /** Унифицированный контракт аналитических отчётов (technical/fundamental). */
    val AGENT_CONCLUSION: String =
        // language=json
        """
        {
          "type": "object",
          "required": ["conclusion", "signalStrength", "reasoning"],
          "additionalProperties": false,
          "properties": {
            "conclusion": {
              "type": "string",
              "enum": ["BULLISH", "BEARISH", "NEUTRAL"]
            },
            "signalStrength": {
              "type": "number",
              "minimum": 0.0,
              "maximum": 1.0
            },
            "reasoning": {
              "type": "string",
              "minLength": 1
            }
          }
        }
        """.trimIndent()

    /**
     * Агрессивный кандидат (для стратегий, допускающих пустое reasoning):
     * сигнатура решения стратегии/арбитра должна минимум содержать action.
     */
    val STRATEGY_DECISION: String =
        // language=json
        """
        {
          "type": "object",
          "required": ["action", "signalStrength"],
          "additionalProperties": false,
          "properties": {
            "action": {
              "type": "string",
              "enum": ["BUY", "SELL", "HOLD"]
            },
            "targetPrice": {
              "type": "number",
              "minimum": 0.0
            },
            "signalStrength": {
              "type": "number",
              "minimum": 0.0,
              "maximum": 1.0
            },
            "reasoning": {
              "type": "string"
            }
          }
        }
        """.trimIndent()
}

/**
 * Реализация [JsonSchemaValidator] на Jackson (tools.jackson).
 *
 * Схема и документ разбираются в типизированные структуры (Map/List/примитивы),
 * что исключает зависимость от изменчивых методов JsonNode и упрощает тесты.
 */
@Component
class DefaultJsonSchemaValidator(
    private val objectMapper: ObjectMapper,
) : JsonSchemaValidator {
    override fun isValid(
        json: String,
        schema: String,
    ): Boolean =
        try {
            val document = parseRoot(json) ?: return false
            val schemaDoc = parseRoot(schema) ?: return false
            validate(document, schemaDoc, "/")
        } catch (_: Exception) {
            false
        }

    private fun parseRoot(text: String): Map<String, Any?>? =
        runCatching {
            objectMapper.readValue(text, object : TypeReference<Map<String, Any?>>() {})
        }.getOrNull()

    @Suppress("UNCHECKED_CAST")
    private fun validate(
        node: Any?,
        schema: Map<String, Any?>,
        path: String,
    ): Boolean {
        val type = schema["type"] as? String
        if (type != null && !matchesType(node, type)) {
            return false
        }

        val enumValues = schema["enum"] as? List<Any?>
        if (enumValues != null && node !in enumValues) {
            return false
        }

        if (node is Map<*, *>) {
            val required = schema["required"] as? List<Any?>
            if (required != null && required.any { name -> node[name] == null }) {
                return false
            }

            val properties = schema["properties"] as? Map<String, Any?>
            if (properties != null) {
                for ((name, childSchema) in properties) {
                    val child = node[name]
                    if (child != null && childSchema is Map<*, *>) {
                        if (!validate(child, childSchema as Map<String, Any?>, "$path/$name")) {
                            return false
                        }
                    }
                }
            }

            if (schema["additionalProperties"] == false) {
                val known = (schema["properties"] as? Map<*, *>)?.keys ?: emptySet<Any?>()
                if (node.keys.any { it !in known }) {
                    return false
                }
            }
        }

        if (type == "number" || type == "integer") {
            val number = node as? Number ?: return false
            val min = (schema["minimum"] as? Number)?.toDouble()
            val max = (schema["maximum"] as? Number)?.toDouble()
            val value = number.toDouble()
            if (!value.isFinite()) return false
            if (min != null && value < min) return false
            if (max != null && value > max) return false
            if (type == "integer" && value % 1.0 != 0.0) return false
        }

        if (type == "string") {
            val text = node as? String ?: return false
            val minLength = (schema["minLength"] as? Number)?.toInt()
            val maxLength = (schema["maxLength"] as? Number)?.toInt()
            if (minLength != null && text.length < minLength) return false
            if (maxLength != null && text.length > maxLength) return false
        }

        if (type == "array") {
            val items = schema["items"]
            if (node !is List<*>) return false
            if (items is Map<*, *>) {
                for ((index, child) in node.withIndex()) {
                    if (child != null) {
                        if (!validate(child, items as Map<String, Any?>, "$path/[$index]")) {
                            return false
                        }
                    }
                }
            }
        }

        return true
    }

    private fun matchesType(
        node: Any?,
        type: String,
    ): Boolean =
        when (type) {
            "object" -> node is Map<*, *>
            "array" -> node is List<*>
            "string" -> node is String
            "number" -> node is Number
            "integer" -> node is Number && node.toDouble() % 1.0 == 0.0
            "boolean" -> node is Boolean
            "null" -> node == null
            else -> true
        }
}
