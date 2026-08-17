package com.trading.bot.application

import java.time.Instant

/**
 * Причина блокировки торговли (новые входы).
 *
 * Глобальные (ticker == null) блокируют всю торговлю:
 * - [MANUAL_DISABLE] — ручное выключение флагом tradingEnabled;
 * - [DAILY_LOSS_LIMIT] — достигнут дневной лимит убытка (TradingHaltedEvent);
 * - [LEVERAGE_DISABLED] — плечо отключено (TradingHaltedEvent);
 * - [STATE_DESYNC] — критический рассинхрон состояния с биржей (TradingHaltedEvent);
 * - [EMERGENCY_STOP] — аварийная остановка (EmergencyStopService, source MANUAL/AUTO);
 * - [DRAWDOWN_PROTECTION] — Multi-Tier просадка (дневная/7д/30д/Shadow mode);
 * - [OUTSIDE_HOURS] — вне торгового окна.
 *
 * Per-ticker (ticker != null) блокируют вход только по конкретному инструменту:
 * - [VOLATILITY] — ATR% выше лимита;
 * - [STALE_DATA] — устаревшие рыночные данные;
 * - [TICKER_PAUSED] — адаптивная пауза по статистике тикера.
 */
enum class TradingBlockReason {
    MANUAL_DISABLE,
    DAILY_LOSS_LIMIT,
    LEVERAGE_DISABLED,
    STATE_DESYNC,
    EMERGENCY_STOP,
    DRAWDOWN_PROTECTION,
    OUTSIDE_HOURS,
    VOLATILITY,
    STALE_DATA,
    TICKER_PAUSED,
    WS_DISCONNECTED,
}

/**
 * Источник решения о блокировке.
 */
enum class TradingBlockSource {
    MANUAL,
    RISK_SYSTEM,
    TRADING_HOURS,
    STATE_RECONCILIATION,
    MARKET_DATA,
    ADAPTIVE_RISK,
}

/**
 * Один блок-фактор: причина, источник, детализация, время, (опционально) тикер.
 */
data class TradingBlock(
    val reason: TradingBlockReason,
    val source: TradingBlockSource,
    val detail: String = "",
    val timestamp: Instant = Instant.now(),
    val ticker: String? = null,
)

/**
 * Итоговый статус торговли — единая "точка отключения" с объяснением, почему.
 *
 * [enabled] = нет ни одного ГЛОБАЛЬНОГО блока (per-ticker блоки не останавливают
 * торговлю целиком, но попадают в [blocks] для диагностики).
 *
 * [reason]/[source]/[detail]/[timestamp] — первый (наиболее критичный) глобальный блок;
 * при [enabled] == true — null.
 */
data class TradingStatus(
    val blocks: List<TradingBlock>,
    val timestamp: Instant = Instant.now(),
) {
    val enabled: Boolean get() = blocks.none { it.ticker == null }

    val globalBlock: TradingBlock? get() = blocks.firstOrNull { it.ticker == null }

    val reason: TradingBlockReason? get() = globalBlock?.reason

    val source: TradingBlockSource? get() = globalBlock?.source

    val detail: String? get() = globalBlock?.detail

    val blockedAt: Instant? get() = globalBlock?.timestamp
}
