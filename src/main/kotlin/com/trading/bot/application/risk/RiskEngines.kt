package com.trading.bot.application.risk

import com.trading.bot.domain.risk.RiskVerdict
import com.trading.bot.infrastructure.metrics.MutableGauges
import io.github.oshai.kotlinlogging.KLogger
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags

internal fun rejected(
    reason: String,
    meterRegistry: MeterRegistry,
    logger: KLogger,
    allowedGauge: String? = null,
): RiskVerdict {
    meterRegistry.counter("risk.entry.rejected", Tags.of("reason", reason)).increment()
    // Гейт «входы разрешены» обязан возвращаться в 0: иначе после первого же разрешения
    // панель навсегда показывает 1 и не отличает «ок» от «всё заблокировано» (скан-17 P2).
    if (allowedGauge != null) {
        MutableGauges.set(meterRegistry, allowedGauge, 0.0)
    }
    logger.warn { "Entry REJECTED: $reason" }
    return RiskVerdict.Rejected(reason)
}
