package com.trading.bot.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.web.bind.annotation.RequestParam
import java.lang.reflect.Method

/**
 * `/analytics/trade-stats` и `/analytics/time-pattern/{ticker}` принимали `days`
 * без зажима: отрицательное значение уводило окно в будущее, огромное —
 * растягивало запрос на всю таблицу сделок (полный скан). Окно ограничено
 * диапазоном [1, 365], как в `TradingAccountController.dailyPnlHistory`.
 */
class AnalyticsDaysClampTest {
    @Test
    fun `analytics day window is clamped to a bounded range`() {
        assertEquals(1, clampAnalyticsDays(0))
        assertEquals(1, clampAnalyticsDays(-5))
        assertEquals(1, clampAnalyticsDays(Int.MIN_VALUE))
        assertEquals(365, clampAnalyticsDays(100_000))
        assertEquals(365, clampAnalyticsDays(Int.MAX_VALUE))
        assertEquals(14, clampAnalyticsDays(14))
        assertEquals(1, clampAnalyticsDays(1))
        assertEquals(365, clampAnalyticsDays(365))
    }

    @Test
    fun `analytics endpoints expose days as an optional query parameter`() {
        val defaults = mutableMapOf<String, String>()
        for (name in listOf("getTradeStats", "getTimePattern")) {
            val method: Method =
                ApiController::class.java.declaredMethods.firstOrNull { it.name == name }
                    ?: error("ApiController.$name не найден")
            val days =
                method.parameters
                    .filter { it.getAnnotation(RequestParam::class.java) != null }
                    .firstOrNull { it.name == "days" }
            assertNotNull(days, "$name: параметр days отсутствует")
            val defaultValue = days!!.getAnnotation(RequestParam::class.java).defaultValue
            assertTrue(defaultValue.toInt() >= 1, "$name: default days должен быть в допустимом окне")
            defaults[name] = defaultValue
        }
        assertEquals("14", defaults["getTradeStats"])
        assertEquals("30", defaults["getTimePattern"])
    }
}
