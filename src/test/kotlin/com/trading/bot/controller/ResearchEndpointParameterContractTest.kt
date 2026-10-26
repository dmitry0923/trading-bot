package com.trading.bot.controller

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Контракт research-эндпоинтов: каждый объявляет параметры, которые реально влияют
 * на прогон.
 *
 * Класс дефекта, который этот тест закрывает: IS-прескрины слали
 * `timeframe`/`wfaSlPoints`/`riskPerTradePercent`/`maxC`/`leverage`, эндпоинт их **не
 * объявлял** — Spring молча игнорировал query, и вердикт относился к другой конфигурации
 * (скан-6). Компилятор такое не ловит, поэтому проверяем текст объявления.
 */
class ResearchEndpointParameterContractTest {
    private val source: String by lazy {
        File("src/main/kotlin/com/trading/bot/controller/ApiController.kt").readText()
    }

    private fun parameterBlock(functionName: String): String {
        val start = source.indexOf("fun $functionName(")
        assertTrue(start >= 0, "method $functionName not found in ApiController")
        val end = source.indexOf("): Map<", start)
        assertTrue(end > start, "end of signature for $functionName not found")
        return source.substring(start, end)
    }

    @Test
    fun `backtest endpoint declares every research override the engine supports`() {
        val block = parameterBlock("backtest")

        listOf(
            "timeframe",
            "wfaSlPoints",
            "wfaTpPoints",
            "riskPerTradePercent",
            "futuresMaxContractsPerPosition",
            "leverage",
            "capitalSlice",
            "maxHoldBars",
            "executionPassiveFill",
        ).forEach { name ->
            assertTrue(block.contains("$name: "), "/backtest must declare $name")
        }
    }

    @Test
    fun `holdout endpoint declares funding veto and WFA grid overrides`() {
        val block = parameterBlock("holdoutValidation")

        listOf(
            "fundingVetoEnabled",
            "fundingVetoLongThresholdRub",
            "fundingVetoShortThresholdRub",
            "fundingVetoBlockOnUnknown",
            "wfaSlPoints",
            "wfaTpPoints",
            "leverage",
        ).forEach { name ->
            assertTrue(block.contains("$name: "), "/holdout must declare $name")
        }
    }

    @Test
    fun `deployment gate declares the funding veto and WFA grid overrides`() {
        val block = parameterBlock("deploymentGate")

        listOf(
            "fundingVetoEnabled",
            "wfaSlPoints",
            "wfaTpPoints",
            "maxHoldBars",
            "executionPassiveFill",
        ).forEach { name ->
            assertTrue(block.contains("$name: "), "/deployment-gate must declare $name")
        }
    }

    @Test
    fun `every research endpoint that reads candles resamples the higher timeframe`() {
        // Ресемплинг был только у /validate: robustness/holdout/gate читали нативный ТФ
        // и проверяли другой набор баров (скан-6).
        listOf("backtestRobustness", "holdoutValidation", "deploymentGate").forEach { fn ->
            val start = source.indexOf("fun $fn(")
            val bodyEnd = source.indexOf("@GetMapping", start + 1).let { if (it < 0) source.length else it }
            val body = source.substring(start, bodyEnd)
            assertTrue(
                body.contains("researchCandles("),
                "$fn must load candles through researchCandles() to keep timeframe semantics",
            )
        }
    }
}
