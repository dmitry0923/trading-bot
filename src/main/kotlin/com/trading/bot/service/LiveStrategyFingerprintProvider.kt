package com.trading.bot.service

import com.trading.bot.backtest.FrozenStrategy
import com.trading.bot.backtest.StrategyFingerprint
import org.springframework.stereotype.Component

/**
 * Резолвер fingerprint замороженной стратегии.
 *
 * Fingerprint строится ИСКЛЮЧИТЕЛЬНО из [FrozenStrategy] (того объекта, который
 * реально прошёл WFA->holdout->MC и применён к live-execution), а НЕ из текущего
 * ambient-конфига. Один и тот же fingerprint используется при approve (в
 * [com.trading.bot.backtest.DeploymentGate]) и на execution boundary (транспорт)
 * — совпадение гарантирует, что исполняется именно та перевалидированная
 * стратегия, которая была одобрена.
 *
 * В fingerprint включается immutable build identity (git commit, P2),
 * [strategyVersion] и все параметры решения/сайзинга. Любое изменение одного из
 * них меняет fingerprint → real-ордер для тикера блокируется до нового
 * deployment-цикла.
 */
@Component
class LiveStrategyFingerprintProvider(
    private val buildIdentity: BuildIdentity,
) {
    /** Версия стратегии/логики. Должна увеличиваться при любом изменении логики
     *  сигнала/исполнения (не только конфигов) — иначе старый approve будет
     *  «легитимизировать» новую, неперевалидированную стратегию. */
    val strategyVersion: String = "live-v2"

    fun fingerprint(frozen: FrozenStrategy): String {
        val content =
            buildString {
                append("strategyVersion=").append(frozen.strategyVersion).append('\n')
                append("gitCommit=").append(frozen.gitCommitSha).append('\n')
                append("ticker=").append(frozen.ticker).append('\n')
                append("slPercent=").append(frozen.slPercent).append('\n')
                append("tpPercent=").append(frozen.tpPercent).append('\n')
                append("slPoints=").append(frozen.slPoints).append('\n')
                append("tpPoints=").append(frozen.tpPoints).append('\n')
                append("confidenceThreshold=").append(frozen.confidenceThreshold).append('\n')
                append("leverage=").append(frozen.leverage).append('\n')
                append("riskPerTradePercent=").append(frozen.riskPerTradePercent).append('\n')
                append("futuresMaxContractsPerPosition=").append(frozen.futuresMaxContractsPerPosition).append('\n')
            }
        return StrategyFingerprint.sha256(content)
    }

    /** Версия в live-v2: предыдущий fingerprint строился из ambient-конфига и не
     *  отражал реальные frozen-параметры (P1-аудит). */
    val catalogVersion: String = "live-v2"
}
