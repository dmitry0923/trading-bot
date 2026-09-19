package com.trading.bot.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * Конфигурация LIVE-funding (prefix = "funding").
 *
 * CNYRUBF funding — ДИНАМИЧЕСКАЯ величина биржи (MOEX); фиксированное
 * [InstrumentsConfig.InstrumentSpec.fundingRubPerContractPerDay] пригодно только
 * для SIM/backtest. В LIVE при недоступности MOEX клиринг помечается
 * FUNDING_UNKNOWN (метрика `funding.live.provider_unavailable`) — CONFIG НЕ
 * подставляется (никакого «тихого 0.5»).
 *
 * LIVE-источник — MOEX ISS (или любой источник, отдающий ISS-таблицу columns/data):
 * - [moexUrl] — URL-шаблон; плейсхолдер `{ticker}` заменяется на тикер инструмента.
 *   Пустой → MOEX-источник выключен (в LIVE все пережитые клиринги = FUNDING_UNKNOWN —
 *   funding не является триггером отказа ордера, но P&L сделок по такому инструменту
 *   без авторитетного funding помечается funding-uncertain).
 * - [moexColumn] — имя столбца funding в таблице источника. Для perpetual-фьючерсов
 *   MOEX — `SWAPRATE` (RUB за 1 единицу базового актива; подтверждено по реальным
 *   данным CNYRUBF 2026-09-08..10: 0.00278 / 0.00256)
 *   — `LATESTFUNDING` в MOEX ISS НЕ существует (верифицировано 2026-09-10).
 * - [moexLotMultiplier] — конвертация raw-значения → RUB/контракт/клиринг (CNYRUBF:
 *   ставка за 1 CNY × лот 1000).
 *
 * Research-источник ИСТОРИИ funding (донакачка SWAPRATE в
 * `funding_history` для P&L бэктеста / калибровки funding-veto, открытый P1):
 * - [moexHistoryUrl] — URL-шаблон ISS history endpoint; плейсхолдеры `{ticker}`,
 *   `{from}`, `{till}` заменяются соответственно. Пустой → донакачка отключена
 *   (loader возвращает пустой результат, backtest остаётся на configured-ставке).
 * Семантика значения/конвертации — та же, что у [moexColumn]/[moexLotMultiplier].
 */
@Component
@ConfigurationProperties(prefix = "funding")
class FundingConfig {
    var moexUrl: String? = null
    var moexColumn: String = "SWAPRATE"
    var moexLotMultiplier: BigDecimal = BigDecimal("1000")
    var moexTtlMs: Long = 5 * 60_000L
    var requestTimeoutMs: Long = 10_000L

    /**
     * URL-шаблон ISS HISTORY endpoint для донакачки funding (research, P1):
     * `https://iss.moex.com/iss/history/engines/futures/markets/forts/securities/{ticker}.json?iss.meta=off&from={from}&till={till}`
     * Плейсхолдеры — `{ticker}`, `{from}` (yyyy-MM-dd), `{till}` (yyyy-MM-dd).
     */
    var moexHistoryUrl: String? = null
}
