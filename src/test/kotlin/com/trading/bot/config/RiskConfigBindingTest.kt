package com.trading.bot.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource

/**
 * Биндинг риск-лимитов из `application.yml`.
 *
 * Ключи yml (`max-rolling-loss-7d-percent`, `max-portfolio-var-percent`) должны
 * соответствовать именам свойств `RiskConfig` после relaxed-binding. Раньше свойства
 * назывались `maxRollingLossPercent7d`/`maxPortfolioVaRPercent`, канонические имена
 * (`maxrollinglosspercent7d`, `maxportfoliovarrollpercent`-подобные) не совпадали с yml →
 * ключи молча не применялись (оператор «ужесточал» лимит, значение не менялось) — скан-5 P2.
 */
class RiskConfigBindingTest {
    @Test
    fun `yaml risk limit keys bind to RiskConfig properties`() {
        val source =
            MapConfigurationPropertySource(
                mapOf(
                    "risk.max-rolling-loss-7d-percent" to "3.0",
                    "risk.max-rolling-loss-30d-percent" to "4.5",
                    "risk.max-portfolio-var-percent" to "1.5",
                ),
            )

        val config =
            Binder(source)
                .bind("risk", Bindable.of(RiskConfig::class.java))
                .orElseThrow { IllegalStateException("risk config did not bind") }

        assertEquals(3.0, config.maxRollingLoss7dPercent)
        assertEquals(4.5, config.maxRollingLoss30dPercent)
        assertEquals(1.5, config.maxPortfolioVarPercent)
    }
}
