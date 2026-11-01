package com.trading.bot.backtest

import com.trading.bot.config.BacktestConfig
import com.trading.bot.config.RiskConfig
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [BacktestSignalGeneratorConfig] — бин создаётся с regime-конфигом и без него.
 */
class BacktestSignalGeneratorConfigTest {
    @Test
    fun `generator bean is built without and with regime detection`() {
        val plain = BacktestSignalGeneratorConfig(BacktestConfig(), RiskConfig())
        assertTrue(plain.liveStrategyBacktestSignalGenerator() is LiveStrategyBacktestSignalGenerator)

        val regimeEnabled = BacktestConfig().apply { regimeDetectionEnabled = true }
        val withRegime = BacktestSignalGeneratorConfig(regimeEnabled, RiskConfig())
        assertTrue(withRegime.liveStrategyBacktestSignalGenerator() is LiveStrategyBacktestSignalGenerator)
    }
}
