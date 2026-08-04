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
 * Правила:
 * 1. riskLevel == CRITICAL → HOLD (детерминированный override)
 * 2. дневной лимит убытка достигнут → HOLD
 * 3. confidence < adaptiveThreshold → HOLD
 * 4. отклонение цены от рыночной > 3% → корректировать targetPrice до рыночной
 * 5. action != HOLD при quantity <= 0 → HOLD
 */
@Component
class Guardrails(
    private val llmConfig: LlmConfig,
    private val meterRegistry: MeterRegistry
) {
    data class Signal(
        val action: StrategyAction,
        val targetPrice: BigDecimal,
        val quantity: Int,
        val stopLoss: BigDecimal?,
        val takeProfit: BigDecimal?,
        val trailingStop: Boolean,
        val confidence: Double
    ) {
        companion object {
            fun hold(marketPrice: BigDecimal): Signal = Signal(
                action = StrategyAction.HOLD,
                targetPrice = marketPrice,
                quantity = 0,
                stopLoss = null,
                takeProfit = null,
                trailingStop = false,
                confidence = 0.0
            )
        }
    }

    data class GuardedSignal(
        val signal: Signal,
        val overridden: Boolean,
        val overrideReason: String?,
        val appliedRules: List<String>
    )

    fun apply(
        signal: Signal,
        marketPrice: BigDecimal,
        adaptiveThreshold: Double,
        riskLevel: String = "LOW",
        dailyLossLimitReached: Boolean = false
    ): GuardedSignal {
        var current = signal
        val applied = mutableListOf<String>()

        if (marketPrice <= BigDecimal.ZERO) {
            recordOverride("INVALID_MARKET_PRICE")
            applied += "marketPrice<=0 -> HOLD"
            return GuardedSignal(
                hold(marketPrice),
                overridden = true,
                overrideReason = "GUARDRAIL: INVALID_MARKET_PRICE",
                appliedRules = applied,
            )
        }

        if (!current.confidence.isFinite()) {
            recordOverride("INVALID_CONFIDENCE")
            applied += "confidence is not finite -> HOLD"
            return GuardedSignal(
                hold(marketPrice),
                overridden = true,
                overrideReason = "GUARDRAIL: INVALID_CONFIDENCE",
                appliedRules = applied,
            )
        }

        if (current.confidence !in 0.0..1.0) {
            current = current.copy(confidence = current.confidence.coerceIn(0.0, 1.0))
            applied += "confidence clamped to [0,1]"
        }

        if (current.action == StrategyAction.HOLD) {
            return GuardedSignal(
                current,
                overridden = applied.isNotEmpty(),
                overrideReason = applied.takeIf { it.isNotEmpty() }?.let { "GUARDRAIL: VALUE_CLAMPED" },
                appliedRules = applied,
            )
        }

        if (riskLevel == "CRITICAL") {
            recordOverride("RISK_CRITICAL")
            applied += "riskLevel=CRITICAL -> HOLD"
            return GuardedSignal(hold(marketPrice), overridden = true, overrideReason = "DETERMINISTIC: RISK_CRITICAL", appliedRules = applied)
        }

        if (dailyLossLimitReached) {
            recordOverride("DAILY_LOSS_LIMIT")
            applied += "dailyLossLimitReached -> HOLD"
            return GuardedSignal(hold(marketPrice), overridden = true, overrideReason = "DETERMINISTIC: DAILY_LOSS_LIMIT", appliedRules = applied)
        }

        if (current.confidence < adaptiveThreshold) {
            recordOverride("LOW_CONFIDENCE")
            applied += "confidence=${current.confidence} < threshold=$adaptiveThreshold -> HOLD"
            current = current.copy(action = StrategyAction.HOLD, quantity = 0)
            return GuardedSignal(current, overridden = true, overrideReason = "GUARDRAIL: LOW_CONFIDENCE", appliedRules = applied)
        }

        if (current.quantity <= 0) {
            recordOverride("ZERO_QUANTITY")
            applied += "quantity<=0 -> HOLD"
            current = current.copy(action = StrategyAction.HOLD)
            return GuardedSignal(current, overridden = true, overrideReason = "GUARDRAIL: ZERO_QUANTITY", appliedRules = applied)
        }

        if (current.targetPrice <= BigDecimal.ZERO) {
            recordOverride("INVALID_TARGET_PRICE")
            applied += "targetPrice<=0 -> adjust target to market"
            current = current.copy(targetPrice = marketPrice)
            return GuardedSignal(
                current,
                overridden = true,
                overrideReason = "GUARDRAIL: INVALID_TARGET_PRICE",
                appliedRules = applied,
            )
        }

        val deviation = current.targetPrice.subtract(marketPrice).abs()
            .divide(marketPrice, 4, RoundingMode.HALF_UP)
        val maxDeviation = BigDecimal(llmConfig.guardrailsMaxPriceDeviationPercent).divide(BigDecimal("100"))
        if (deviation > maxDeviation) {
            recordOverride("PRICE_DEVIATION")
            applied += "price deviation $deviation > $maxDeviation -> adjust target to market"
            current = current.copy(targetPrice = marketPrice)
            return GuardedSignal(current, overridden = true, overrideReason = "GUARDRAIL: PRICE_DEVIATION", appliedRules = applied)
        }

        return GuardedSignal(
            current,
            overridden = applied.isNotEmpty(),
            overrideReason = applied.takeIf { it.isNotEmpty() }?.let { "GUARDRAIL: VALUE_CLAMPED" },
            appliedRules = applied,
        )
    }

    private fun hold(marketPrice: BigDecimal): Signal =
        Signal(StrategyAction.HOLD, marketPrice, 0, null, null, false, 0.0)

    private fun recordOverride(reason: String) {
        meterRegistry.counter("arbitrator.deterministic.override", Tags.of("reason", reason)).increment()
    }
}
