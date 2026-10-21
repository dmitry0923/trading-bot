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
 * @property liveRateEnabled брать ключевую ставку из ряда ЦБ (true = последняя из БД,
 *   false = статичный [cbrRate])
 * @property cbrSoapUrl SOAP-сервис ЦБ `DailyInfoWebServ` (методы KeyRate/Ruonia)
 * @property cbrRequestTimeoutMs таймаут запроса к SOAP-сервису ЦБ, мс
 * @property cbrRefreshIntervalMs период фоновой донакачки ряда ЦБ, мс
 * @property cbrRefreshDays глубина окна фоновой донакачки, дней
 * @property liveRateStaleDays возраст последней ставки (дней), после которого пишем WARN
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

    // P2 c1: ряд ставок ЦБ РФ. live-rate-enabled=true — MacroContextService читает
    // последнюю ставку из cbr_rates_history (соответствует действительности: 14,0%
    // с 2026-07-27 против устаревшего cbrRate=16.0); fail-soft fallback на cbrRate
    // при пустом ряде/ошибке БД. Ряд обновляет CbrRatesRefreshScheduler.
    var liveRateEnabled: Boolean = true
    var cbrSoapUrl: String = "https://www.cbr.ru/DailyInfoWebServ/DailyInfo.asmx"
    var cbrRequestTimeoutMs: Long = 10_000L
    var cbrRefreshIntervalMs: Long = 6 * 60 * 60 * 1000L
    var cbrRefreshDays: Int = 30
    var liveRateStaleDays: Long = 14
}
