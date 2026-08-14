package com.trading.bot.infrastructure.llm

import com.trading.bot.config.LlmConfig
import com.trading.bot.model.StrategyAction
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Guardrails — жёсткая постобработка сигналов агентов (не подлежит обсуждению с LLM).
 *
 * Правила (только направление сделки — размер и стопы вычисляет риск-этап):
 * 1. riskLevel == CRITICAL → HOLD (детерминированный override)
 * 2. signalStrength < adaptiveThreshold → HOLD
 * 3. отклонение цены от рыночной > 3% → корректировать targetPrice до рыночной
 *
 * Дневной лимит убытка / Shadow-режим / количество позиций — этап RiskEngine
 * (не здесь и не в агентах).
 */
@Component
class Guardrails(
    private val llmConfig: LlmConfig,
    private val meterRegistry: MeterRegistry,
) {
    data class Signal(
        val action: StrategyAction,
        val targetPrice: BigDecimal,
        val signalStrength: Double,
    ) {
        companion object {
            fun hold(marketPrice: BigDecimal): Signal =
                Signal(
                    action = StrategyAction.HOLD,
                    targetPrice = marketPrice,
                    signalStrength = 0.0,
                )
        }
    }

    data class GuardedSignal(
        val signal: Signal,
        val overridden: Boolean,
        val overrideReason: String?,
        val appliedRules: List<String>,
    )

    fun apply(
        signal: Signal,
        marketPrice: BigDecimal,
        adaptiveThreshold: Double,
        riskLevel: String = "LOW",
    ): GuardedSignal {
        var current = signal
        val applied = mutableListOf<String>()

        if (current.action == StrategyAction.HOLD) {
            return GuardedSignal(current, overridden = false, overrideReason = null, appliedRules = applied)
        }

        if (riskLevel == "CRITICAL") {
            recordOverride("RISK_CRITICAL")
            applied += "riskLevel=CRITICAL -> HOLD"
            return GuardedSignal(
                hold(marketPrice),
                overridden = true,
                overrideReason = "DETERMINISTIC: RISK_CRITICAL",
                appliedRules = applied,
            )
        }

        if (current.signalStrength < adaptiveThreshold) {
            recordOverride("LOW_CONFIDENCE")
            applied += "signalStrength=${current.signalStrength} < threshold=$adaptiveThreshold -> HOLD"
            current = current.copy(action = StrategyAction.HOLD)
            return GuardedSignal(current, overridden = true, overrideReason = "GUARDRAIL: LOW_CONFIDENCE", appliedRules = applied)
        }

        val deviation =
            current.targetPrice
                .subtract(marketPrice)
                .abs()
                .divide(marketPrice, 4, RoundingMode.HALF_UP)
        val maxDeviation = BigDecimal(llmConfig.guardrailsMaxPriceDeviationPercent).divide(BigDecimal("100"))
        if (deviation > maxDeviation) {
            recordOverride("PRICE_DEVIATION")
            applied += "price deviation $deviation > $maxDeviation -> adjust target to market"
            current = current.copy(targetPrice = marketPrice)
            return GuardedSignal(current, overridden = true, overrideReason = "GUARDRAIL: PRICE_DEVIATION", appliedRules = applied)
        }

        return GuardedSignal(current, overridden = false, overrideReason = null, appliedRules = applied)
    }

    private fun hold(marketPrice: BigDecimal): Signal = Signal(StrategyAction.HOLD, marketPrice, 0.0)

    private fun recordOverride(reason: String) {
        meterRegistry.counter("arbitrator.deterministic.override", Tags.of("reason", reason)).increment()
    }
}
