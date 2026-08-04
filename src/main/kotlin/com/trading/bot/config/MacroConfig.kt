package com.trading.bot.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * Конфигурация макроэкономического контекста (prefix = "macro").
 *
 * @property cbrRate ключевая ставка ЦБ, %
 * @property brentPrice цена Brent, $
 * @property usdRub курс USD/RUB
 * @property usdRubTicker тикер пары USD/RUB на MOEX для live-обновления
 */
@Component
@ConfigurationProperties(prefix = "macro")
class MacroConfig {
    var cbrRate: BigDecimal = BigDecimal("16.0")
    var brentPrice: BigDecimal = BigDecimal("75.0")
    var usdRub: BigDecimal = BigDecimal("90.0")
    var usdRubTicker: String = "USD000UTSTOM"
}
