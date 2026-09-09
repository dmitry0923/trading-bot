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
 * - [moexColumn] — имя столбца funding в таблице источника.
 * - [moexLotMultiplier] — конвертация raw-значения → RUB/контракт/клиринг (CNYRUBF:
 *   публикуемая ставка × lot 1000 CNY). Все эндпоинт/поле/множитель — PROVISIONAL,
 *   должны быть сверены с фактическими данными MOEX ДО LIVE (docs/16).
 */
@Component
@ConfigurationProperties(prefix = "funding")
class FundingConfig {
    var moexUrl: String? = null
    var moexColumn: String = "LATESTFUNDING"
    var moexLotMultiplier: BigDecimal = BigDecimal("1000")
    var moexTtlMs: Long = 5 * 60_000L
    var requestTimeoutMs: Long = 10_000L
}
