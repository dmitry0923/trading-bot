package com.trading.bot.client

/**
 * Назначение (интент) ордера для execution interlock.
 *
 * Разделяет risk-INCREASING ордера ([ENTRY] — открытие/наращивание позиции) и
 * risk-REDUCING ([CLOSE], [SL], [TP] — закрытие/защита существующей позиции).
 *
 * Interlock применяется строго к ENTRY: тикер должен быть LIVE-approved,
 * fingerprint совпадать и build SHA совпадать. CLOSE/SL/TP при этом разрешены,
 * если тикер approved ЛИБО для него существует открытая позиция (см.
 * [com.trading.bot.service.LiveFrozenStrategyResolver]) — после revoke / смены
 * build SHA бот обязан остаться способным выйти из открытой позиции (P1-a):
 * отказать в risk-reducing ордере опаснее, чем исполнить его.
 */
enum class OrderPurpose(val code: String) {
    /** Открытие/наращивание позиции — требует полного LIVE-approval. */
    ENTRY("entry"),

    /** Закрытие позиции по рынку (SL/TP/trailing/strategy/emergency/liquidation). */
    CLOSE("close"),

    /** Размещение/замена биржевой stop-loss заявки защищающей открытую позицию. */
    SL("sl"),

    /** Размещение/замена биржевой take-profit заявки открытой позиции. */
    TP("tp");

    companion object {
        /** Значение из payload outbox ("entry"/"close"/"sl"/"tp"); null — неизвестно (трактуется как [ENTRY]). */
        fun from(code: String?): OrderPurpose? = entries.firstOrNull { it.code == code }
    }
}