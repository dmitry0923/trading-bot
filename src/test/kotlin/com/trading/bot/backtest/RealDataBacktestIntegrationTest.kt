package com.trading.bot.backtest

import com.trading.bot.integration.AbstractTestContainerTest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired

/**
 * Integration-тест бэктеста на реальных данных MOEX ISS.
 *
 * Выполняется ТОЛЬКО при явном флаге: `BK_REAL_TEST=true`.
 *
 * - Тянет 2 года 10-минутных свечей через [HistoricalDataLoader] (пагинация 500)
 * - Прогоняет [BacktestEngine] и проверяет, что результат валиден (свечи загружены, кривая не пустая)
 *
 * Параметры:
 * - `BK_TEST_TICKER` — тикер (по умолчанию SBER)
 * - `BK_TEST_DAYS` — глубина истории (по умолчанию 730)
 *
 * Пример запуска:
 * ```
 * $env:BK_REAL_TEST="true"; .\gradlew.bat test --tests "*RealDataBacktestIntegrationTest*"
 * ```
 */
@EnabledIfEnvironmentVariable(named = "BK_REAL_TEST", matches = "true")
class RealDataBacktestIntegrationTest : AbstractTestContainerTest() {

    @Autowired
    lateinit var loader: HistoricalDataLoader

    @Autowired
    lateinit var engine: BacktestEngine

    @Test
    fun `backtest on two years of real MOEX data produces valid result`() {
        val ticker = System.getenv("BK_TEST_TICKER") ?: "SBER"
        val days = (System.getenv("BK_TEST_DAYS") ?: "730").toInt()

        val load = runBlocking { loader.loadAndSave(ticker, days) }
        assertTrue(load.loaded >= 1000, "Ожидалось >= 1000 свечей за $days дней по $ticker, загружено ${load.loaded}")

        val result = runBlocking { engine.run(ticker, days) }
        assertTrue(result.equityCurve.isNotEmpty(), "equityCurve не должен быть пустым")
        assertTrue(result.totalTrades >= 0)
        assertTrue(result.sharpeRatio.isFinite())
    }
}
