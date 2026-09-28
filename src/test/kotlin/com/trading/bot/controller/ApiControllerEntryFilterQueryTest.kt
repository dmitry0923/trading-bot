package com.trading.bot.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.web.bind.annotation.RequestParam
import java.lang.reflect.Method

/**
 * Контракт query-параметров research-входных фильтров (2026-09-28).
 *
 * Зачем рефлексия, а не MockMvc: 18 параметров дублируются в ПЯТЬ эндпоинтов
 * (`/backtest`, `/validate`, `/robustness`, `/holdout`, `/deployment-gate`).
 * Реальная ошибка этого класса - забыть параметр в одном из эндпоинтов или
 * переименовать его: компилятор молчит (значения nullable, default = null),
 * а на research-прогоне фильтр тихо не включается. Именно это уже происходило
 * в обвязке (harness-баг «элемент без `;` уходил в baseline и выглядело как
 * «фильтр не работает»).
 *
 * Проверяем: каждый параметр присутствует во всех 5 методах и имеет ожидаемый
 * тип. Семантика фильтров покрыта EntryFiltersTest, применение overrides -
 * LiveStrategyBacktestSignalGeneratorTest; здесь - только связка HTTP->overrides.
 */
class ApiControllerEntryFilterQueryTest {
    private val newParams =
        mapOf(
            "emaCrossEnabled" to Boolean::class.javaObjectType,
            "emaCrossFastPeriod" to Integer::class.java,
            "emaCrossSlowPeriod" to Integer::class.java,
            "emaCrossBlockOnUnknown" to Boolean::class.javaObjectType,
            "volumeSpikeEnabled" to Boolean::class.javaObjectType,
            "volumeSpikePeriod" to Integer::class.java,
            "volumeSpikeMultiplier" to java.lang.Double::class.java,
            "volumeSpikeBlockOnUnknown" to Boolean::class.javaObjectType,
            "vwapMrDeviationAtr" to java.lang.Double::class.java,
            "vwapMrAtrPeriod" to Integer::class.java,
            "panicUseSessionDrop" to Boolean::class.javaObjectType,
            "rangeSqueezeEnabled" to Boolean::class.javaObjectType,
            "rangeSqueezeRangePeriod" to Integer::class.java,
            "rangeSqueezeAtrPeriod" to Integer::class.java,
            "rangeSqueezeMultiplier" to java.lang.Double::class.java,
            "rangeSqueezeLookbackBars" to Integer::class.java,
            "rangeSqueezeRequireVolume" to Boolean::class.javaObjectType,
            "rangeSqueezeBlockOnUnknown" to Boolean::class.javaObjectType,
        )

    /**
     * Пять research-endpoint'ов и их Kotlin-методы (проверено по
     * `@GetMapping("/backtest/{ticker}...")`): robustness - `backtestRobustness`.
     */
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

    /**
     * Имя -> параметр для query-параметров метода. `@PathVariable` (ticker)
     * намеренно НЕ включён: у него нет @RequestParam, и он не является
     * query-параметром.
     *
     * Про `required`: у `@RequestParam(defaultValue = "false")` атрибут
     * `required` в аннотации остаётся `true` (Spring трактует default как
     * «не обязателен» только в момент резолвинга), поэтому проверять
     * `required` можно лишь точечно - см. проверку 18 новых параметров.
     */
    private fun queryParams(method: Method): Map<String, java.lang.reflect.Parameter> =
        method.parameters
            .filter { it.getAnnotation(RequestParam::class.java) != null }
            .associateBy { param ->
                val requestParam = param.getAnnotation(RequestParam::class.java)
                (param.name ?: requestParam.value)
            }

    @Test
    fun `все 5 endpoints принимают 18 новых query-параметров с ожидаемым типом`() {
        for (endpoint in endpointMethods) {
            val names = queryParams(method(endpoint))
            for ((paramName, expectedType) in newParams) {
                val actual = names[paramName]
                assertNotNull(
                    actual,
                    "В endpoint $endpoint отсутствует query-параметр $paramName " +
                        "(фильтр молча не включится на research-прогоне)",
                )
                assertEquals(
                    expectedType,
                    actual!!.type,
                    "Тип query-параметра $paramName в $endpoint не совпадает с ожидаемым",
                )
                assertTrue(
                    !actual.getAnnotation(RequestParam::class.java).required,
                    "Research-параметр $paramName в $endpoint не должен быть обязательным: " +
                        "иначе запрос без него перестанет работать, а фильтры должны быть " +
                        "выключены по умолчанию",
                )
            }
        }
    }

    @Test
    fun `параметры maxHoldBars и wfa SL TP есть в validate - иначе гейт проверяет не тот конфиг`() {
        // Регрессия, уже чинившаяся в combo730: /deployment-gate и /holdout не
        // принимали maxHoldBars, из-за чего гейт проверял конфиг БЕЗ max-hold.
        val names = queryParams(method("validateBacktest"))
        assertTrue(names.containsKey("maxHoldBars"), "validateBacktest должен принимать maxHoldBars")
        assertTrue(names.containsKey("wfaSlPoints"), "validateBacktest должен принимать wfaSlPoints")
        assertTrue(names.containsKey("wfaTpPoints"), "validateBacktest должен принимать wfaTpPoints")
    }

    @Test
    fun `maxHoldBars не дублируется в базовом URL исследовательской обвязки`() {
        // Ассертится не код контроллера, а конфиг прогонов: дубль maxHoldBars в
        // URL означает, что Spring возьмёт ПЕРВОЕ значение и max-hold молча
        // выключится (harness-баг, зафиксирован в AGENTS.md).
        val csv = java.io.File("scripts/configs_four_cores_scaled.csv")
        if (!csv.exists()) return
        val configLines =
            csv.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertTrue(configLines.isNotEmpty(), "CSV конфигов пуст")
        for (line in configLines) {
            val parts = line.split(";", limit = 2)
            assertEquals(2, parts.size, "Конфиг без разделителя `;` молча ушёл бы в baseline: $line")
            assertTrue(
                parts[1].contains("maxHoldBars="),
                "В конфиге без maxHoldBars (для S3/S4 он обязателен как явное значение): $line",
            )
        }
    }
}
