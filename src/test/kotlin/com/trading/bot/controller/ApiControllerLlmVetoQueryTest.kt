package com.trading.bot.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.web.bind.annotation.RequestParam
import java.lang.reflect.Method

/**
 * Контракт query-параметров research-LLM-veto (docs/20 §10, 2026-09-29).
 *
 * Отдельный класс от [ApiControllerEntryFilterQueryTest]: veto — не входной фильтр,
 * а бэкенд-вызов LLM поверх готового сигнала, и его параметры обязаны принимать ВСЕ
 * пять endpoint'ов одинаково. Иначе калибровка на `/backtest` не воспроизводится на
 * `/validate`/`/deployment-gate` (ровно тот harness-баг, что уже чинился с
 * `maxHoldBars` в combo730).
 */
class ApiControllerLlmVetoQueryTest {
    private val vetoParams =
        mapOf(
            "llmVetoEnabled" to Boolean::class.javaObjectType,
            "llmVetoBudgetMs" to java.lang.Long::class.java,
            "llmVetoBlockOnUnknown" to Boolean::class.javaObjectType,
            "llmVetoPromptVersion" to String::class.java,
            "llmVetoSampleEvery" to Integer::class.java,
        )

    private val endpointMethods =
        listOf(
            "backtest",
            "validateBacktest",
            "backtestRobustness",
            "holdoutValidation",
            "deploymentGate",
        )

    private fun method(name: String): Method =
        ApiController::class.java.declaredMethods.firstOrNull { it.name == name }
            ?: error("Метод ApiController.$name не найден")

    private fun queryParams(method: Method): Map<String, java.lang.reflect.Parameter> =
        method.parameters
            .filter { it.getAnnotation(RequestParam::class.java) != null }
            .associateBy { param ->
                val requestParam = param.getAnnotation(RequestParam::class.java)
                (param.name ?: requestParam.value)
            }

    @Test
    fun `все 5 endpoints принимают llmVeto параметры с ожидаемым типом и не делают их обязательными`() {
        for (endpoint in endpointMethods) {
            val names = queryParams(method(endpoint))
            for ((paramName, expectedType) in vetoParams) {
                val actual = names[paramName]
                assertNotNull(
                    actual,
                    "В endpoint $endpoint отсутствует query-параметр $paramName " +
                        "(veto молча не включится на этом этапе пайплайна)",
                )
                assertEquals(
                    expectedType,
                    actual!!.type,
                    "Тип query-параметра $paramName в $endpoint не совпадает с ожидаемым",
                )
                assertEquals(
                    false,
                    actual.getAnnotation(RequestParam::class.java).required,
                    "Research-параметр $paramName в $endpoint не должен быть обязательным: veto выключен по умолчанию",
                )
            }
        }
    }
}
