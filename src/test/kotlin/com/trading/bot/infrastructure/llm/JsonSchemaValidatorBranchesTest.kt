package com.trading.bot.infrastructure.llm

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper

/**
 * Ветви [DefaultJsonSchemaValidator], которые не задеваются реальными схемами агентов
 * (скан-19: 92/143 ветвей): ветвление по ключевым словам JSON Schema — неверные типы
 * ключевых слов, `required`/`enum`/`properties` не того типа, `additionalProperties`
 * без `properties`, `null`-тип, массивы с `items`, `minimum`/`maximum`,
 * `minLength`/`maxLength`, строковые скаляры от Anthropic и нечисловые значения.
 *
 * Валидатор fail-closed: любая ошибка разбора/неизвестный документ — `false`.
 */
class JsonSchemaValidatorBranchesTest {
    private val validator = DefaultJsonSchemaValidator(ObjectMapper())

    private fun valid(
        json: String,
        schema: String,
    ): Boolean = validator.isValid(json, schema)

    /** Схема «объект с одним свойством `a`»: ключевые слова применяются к значению, не к корню. */
    private fun propSchema(schema: String): String = """{"type":"object","properties":{"a":$schema}}"""

    @Test
    fun `non object document and malformed texts are rejected`() {
        assertFalse(valid("[1,2,3]", """{"type":"object"}"""), "массив вместо объекта")
        assertFalse(valid("""{"a":1}""", "{not-a-json"), "битая схема")
        assertFalse(valid("not-a-json", """{"type":"object"}"""), "битый документ")
    }

    @Test
    fun `unknown schema type accepts anything`() {
        assertTrue(valid("""{"a":1}""", """{"type":"exotic"}"""))
        assertTrue(valid("""{"a":1}""", propSchema("""{"type":"exotic"}""")))
    }

    @Test
    fun `type mismatch is rejected for every known type`() {
        assertFalse(valid("""{"a":1}""", """{"type":"array"}"""))
        assertFalse(valid("""[1]""", """{"type":"object"}"""))
        assertFalse(valid("""{"a":1}""", """{"type":"string"}"""))
        assertFalse(valid("""{"a":true}""", propSchema("""{"type":"number"}""")))
        assertFalse(valid("""{"a":"x"}""", propSchema("""{"type":"integer"}""")))
        assertFalse(valid("""{"a":1}""", propSchema("""{"type":"null"}""")))
    }

    @Test
    fun `boolean accepts a boolean string but not other strings`() {
        val schema = propSchema("""{"type":"boolean"}""")
        assertTrue(valid("""{"a":true}""", schema))
        assertTrue(valid("""{"a":"true"}""", schema))
        assertFalse(valid("""{"a":"yes"}""", schema))
        assertFalse(valid("""{"a":1}""", schema))
    }

    @Test
    fun `null type accepts only null node`() {
        val schema = propSchema("""{"type":"null"}""")
        assertTrue(valid("""{"a":null}""", schema))
        assertFalse(valid("""{"a":1}""", schema))
    }

    @Test
    fun `enum mismatch is rejected and non-list enum is ignored`() {
        val schema = propSchema("""{"enum":["X","Y"]}""")
        assertTrue(valid("""{"a":"X"}""", schema))
        assertFalse(valid("""{"a":"Z"}""", schema))
        assertTrue(valid("""{"a":"Z"}""", propSchema("""{"enum":"X"}""")), "enum не список — игнорируется")
    }

    @Test
    fun `required is enforced only when it is a list`() {
        val schema = """{"type":"object","properties":{"a":{"type":"number"}},"required":["a"]}"""
        assertTrue(valid("""{"a":1}""", schema))
        assertFalse(valid("""{"b":1}""", schema))
        assertTrue(
            valid("""{"b":1}""", """{"type":"object","required":"a"}"""),
            "required не список — игнорируется",
        )
    }

    @Test
    fun `non map properties are ignored and non map child schemas skipped`() {
        assertTrue(valid("""{"a":1}""", """{"type":"object","properties":5}"""))
        assertTrue(valid("""{"a":1}""", propSchema("5")), "схема-ребёнок не map — не проверяется")
        // Ребёнка нет в документе — рекурсия не запускается.
        assertTrue(valid("""{"b":1}""", propSchema("""{"type":"string"}""")))
    }

    @Test
    fun `nested property violation fails the whole document`() {
        val schema = propSchema("""{"type":"object","properties":{"inner":{"type":"string"}}}""")
        assertTrue(valid("""{"a":{"inner":"text"}}""", schema))
        assertFalse(valid("""{"a":{"inner":42}}""", schema))
    }

    @Test
    fun `additionalProperties false rejects unknown keys and works without properties`() {
        val strict = """{"type":"object","properties":{"a":{"type":"number"}},"additionalProperties":false}"""
        assertTrue(valid("""{"a":1}""", strict))
        assertFalse(valid("""{"a":1,"b":2}""", strict))

        val noProperties = """{"type":"object","additionalProperties":false}"""
        assertTrue(valid("{}", noProperties))
        assertFalse(valid("""{"a":1}""", noProperties))

        // additionalProperties=true не ограничивает набор ключей.
        assertTrue(valid("""{"a":1,"b":2}""", """{"type":"object","additionalProperties":true}"""))
    }

    @Test
    fun `numeric bounds are enforced including string numbers`() {
        val schema = propSchema("""{"type":"number","minimum":1,"maximum":10}""")
        assertTrue(valid("""{"a":5}""", schema))
        assertTrue(valid("""{"a":"5"}""", schema), "строковое число от Anthropic")
        assertFalse(valid("""{"a":0.5}""", schema))
        assertFalse(valid("""{"a":11}""", schema))
        assertFalse(valid("""{"a":"11"}""", schema))
        assertFalse(valid("""{"a":"five"}""", schema))
    }

    @Test
    fun `infinite numeric value is rejected`() {
        // Jackson разбирает переполнение как Infinity — такой ответ модели не должен пройти.
        assertFalse(valid("""{"a":1e999}""", propSchema("""{"type":"number"}""")))
    }

    @Test
    fun `integer type rejects fractional values in both number and string form`() {
        val schema = propSchema("""{"type":"integer"}""")
        assertTrue(valid("""{"a":3}""", schema))
        assertTrue(valid("""{"a":"3"}""", schema))
        assertFalse(valid("""{"a":3.5}""", schema))
        assertFalse(valid("""{"a":"3.5"}""", schema))
    }

    @Test
    fun `string length bounds are enforced`() {
        val schema = propSchema("""{"type":"string","minLength":2,"maxLength":4}""")
        assertTrue(valid("""{"a":"abc"}""", schema))
        assertFalse(valid("""{"a":"a"}""", schema))
        assertFalse(valid("""{"a":"abcde"}""", schema))
        assertFalse(valid("""{"a":5}""", propSchema("""{"type":"string"}""")), "не строка")
    }

    @Test
    fun `array items are validated element by element`() {
        val schema = propSchema("""{"type":"array","items":{"type":"string"}}""")
        assertTrue(valid("""{"a":["x","y"]}""", schema))
        assertFalse(valid("""{"a":["x",1]}""", schema))
        assertTrue(valid("""{"a":["x",null]}""", schema), "null-элемент пропускается")
        assertTrue(
            valid("""{"a":[1,2]}""", propSchema("""{"type":"array","items":5}""")),
            "items не map — элементы не проверяются",
        )
    }

    @Test
    fun `deeply nested path violation keeps document invalid`() {
        val schema =
            """{"type":"object","properties":{"list":{"type":"array","items":""" +
                """{"type":"object","properties":{"ok":{"type":"boolean"}}}}}}"""
        assertTrue(valid("""{"list":[{"ok":true}]}""", schema))
        assertFalse(valid("""{"list":[{"ok":"nope"}]}""", schema))
    }
}
