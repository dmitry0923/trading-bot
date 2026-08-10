package com.trading.bot.application.risk

import com.trading.bot.domain.risk.RiskVerdict
import io.github.oshai.kotlinlogging.KLogger
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags

internal fun rejected(
    reason: String,
    meterRegistry: MeterRegistry,
    logger: KLogger,
): RiskVerdict {
    meterRegistry.counter("risk.entry.rejected", Tags.of("reason", reason)).increment()
    logger.warn { "Entry REJECTED: $reason" }
    return RiskVerdict.Rejected(reason)
}
