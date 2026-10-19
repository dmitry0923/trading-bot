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
 * @property snapshotEnabled периодический сбор исторических макро-снапшотов (13.11.2)
 * @property snapshotIntervalMs период сбора снапшотов, мс
 * @property liveRateEnabled брать ключевую ставку из ряда ЦБ (research, false = статичный [cbrRate])
 * @property cbrSoapUrl SOAP-сервис ЦБ `DailyInfoWebServ` (методы KeyRate/Ruonia)
 * @property cbrRequestTimeoutMs таймаут запроса к SOAP-сервису ЦБ, мс
 */
@Component
@ConfigurationProperties(prefix = "macro")
class MacroConfig {
    var cbrRate: BigDecimal = BigDecimal("16.0")
    var brentPrice: BigDecimal = BigDecimal("75.0")
    var usdRub: BigDecimal = BigDecimal("90.0")
    var usdRubTicker: String = "USD000UTSTOM"
    var snapshotEnabled: Boolean = false
    var snapshotIntervalMs: Long = 15 * 60 * 1000L

    // P2 c1: ряд ставок ЦБ РФ (research). live-rate-enabled=false — торговый контур
    // остаётся на статичной cbrRate; с true MacroContextService читает последнюю
    // ставку из cbr_rates_history (fail-soft fallback на cbrRate).
    var liveRateEnabled: Boolean = false
    var cbrSoapUrl: String = "https://www.cbr.ru/DailyInfoWebServ/DailyInfo.asmx"
    var cbrRequestTimeoutMs: Long = 10_000L
}
