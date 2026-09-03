package com.trading.bot.service

import com.trading.bot.backtest.StrategyFingerprint
import com.trading.bot.config.BacktestConfig
import com.trading.bot.config.RiskConfig
import org.springframework.stereotype.Component

/**
 * Резолвер runtime-фрintprиntа стратегии из ЗАМОРОЖЕННОЙ production-конфигурации.
 *
 * Исполнительный слой (transport при [com.trading.bot.config.TradingConfig.mode] == "LIVE")
 * сравнивает этот фрintprиnt с сохранённым при approve (см. [DeploymentApprovalService]).
 * Совпадение гарантирует: одобрена именно та стратегия (версия + параметры решения и
 * сайзинга), которой в данный момент торгует бот. Любое изменение одного из параметров
 * ниже меняет фрintprиnt → real-ордер для тикера блокируется до нового deployment-цикла.
 */
@Component
class LiveStrategyFingerprintProvider(
    private val backtestConfig: BacktestConfig,
    private val riskConfig: RiskConfig,
) {
    /**
     * Версия стратегии/логики. Должна увеличиваться при любом изменении логики
     * сигнала/исполнения (не только конфигов) — иначе старый approve будет
     * «легитимизировать» новую, неперевалидированную стратегию.
     */
    val strategyVersion: String = "live-v1"

    fun fingerprint(): String {
        val b = backtestConfig
        val r = riskConfig
        val content =
            buildString {
                append("strategyVersion=").append(strategyVersion).append('\n')
                append("bt.adaptiveConfidenceThreshold=").append(b.adaptiveConfidenceThreshold).append('\n')
                append("bt.slPercent=").append(b.slPercent).append('\n')
                append("bt.tpPercent=").append(b.tpPercent).append('\n')
                append("bt.timeframe=").append(b.timeframe).append('\n')
                append("bt.mcMethod=").append(b.mcMethod).append('\n')
                append("bt.mcAvgBlockLength=").append(b.mcAvgBlockLength).append('\n')
                append("bt.mcBlockLength=").append(b.mcBlockLength).append('\n')
                append("bt.monteCarloSimulations=").append(b.monteCarloSimulations).append('\n')
                append("bt.monteCarloSeed=").append(b.monteCarloSeed).append('\n')
                append("bt.holdoutFraction=").append(b.holdoutFraction).append('\n')
                append("risk.riskPerTradePercent=").append(r.riskPerTradePercent).append('\n')
                append("risk.kellyFraction=").append(r.kellyFraction).append('\n')
                append("risk.kellyMaxPositionFraction=").append(r.kellyMaxPositionFraction).append('\n')
                append("risk.defaultStopLossPercent=").append(r.defaultStopLossPercent).append('\n')
                append("risk.defaultTakeProfitPercent=").append(r.defaultTakeProfitPercent).append('\n')
                append("risk.defaultStopLossPoints=").append(r.defaultStopLossPoints).append('\n')
                append("risk.defaultTakeProfitPoints=").append(r.defaultTakeProfitPoints).append('\n')
                append("risk.maxMarginUsagePercent=").append(r.maxMarginUsagePercent).append('\n')
                append("risk.maxContractsPerPosition=").append(r.maxContractsPerPosition).append('\n')
                append("risk.confidenceCalibrationEnabled=").append(r.confidenceCalibrationEnabled).append('\n')
                append("risk.confidenceSizingEnabled=").append(r.confidenceSizingEnabled).append('\n')
                append("risk.volatilityTargetPercent=").append(r.volatilityTargetPercent).append('\n')
                append("risk.maxGrossExposurePercent=").append(r.maxGrossExposurePercent).append('\n')
                append("risk.maxNetExposurePercent=").append(r.maxNetExposurePercent).append('\n')
                append("risk.drawdownScaleTiers=").append(r.drawdownScaleTiers).append('\n')
                append("risk.regime=")
                    .append(r.regimeLookbackDays)
                    .append('/')
                    .append(r.regimeMinBars)
                    .append('/')
                    .append(r.regimeCrashAtrMultiplier)
                    .append('/')
                    .append(r.regimePumpAtrMultiplier)
                    .append('\n')
            }
        return StrategyFingerprint.sha256(content)
    }
}
