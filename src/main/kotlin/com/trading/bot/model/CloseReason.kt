package com.trading.bot.model

/**
 * Единый источник истины для причин закрытия позиции.
 *
 * Значения `.code` совпадают со строками, ранее разбросанными по кодовой базе
 * ("STOP_LOSS", "TAKE_PROFIT" и т.д.) — хранение в БД остаётся varchar,
 * конвертер работает на уровне Kotlin.
 */
enum class CloseReason(
    val code: String,
) {
    STOP_LOSS("STOP_LOSS"),
    TAKE_PROFIT("TAKE_PROFIT"),
    TRAILING_STOP("TRAILING_STOP"),
    STRATEGY_CLOSE("STRATEGY_CLOSE"),
    EXECUTION_FILL("EXECUTION_FILL"),
    FORCE_CLOSE("FORCE_CLOSE"),
    FORCE_CLOSE_SCHEDULED("FORCE_CLOSE_SCHEDULED"),
    EMERGENCY_STOP("EMERGENCY_STOP"),
    LIQUIDATION_CRITICAL("LIQUIDATION_CRITICAL"),
    RECONCILIATION("RECONCILIATION"),
    RECONCILE_CLOSED_ON_EXCHANGE("RECONCILE_CLOSED_ON_EXCHANGE"),
    RECONCILE_PHANTOM("RECONCILE_PHANTOM"),
    CLOSED("CLOSED"),
    ENTRY_REJECTED("ENTRY_REJECTED"),
    ENTRY_NOT_CONFIRMED("ENTRY_NOT_CONFIRMED"),
    ;

    /** Является ли причина связанной с ликвидацией (starts with "LIQUIDATION" в старом коде). */
    val isLiquidation: Boolean get() = this == LIQUIDATION_CRITICAL

    /** Является ли причина принудительным закрытием (starts with "FORCE_CLOSE" в старом коде). */
    val isForceClose: Boolean get() = this == FORCE_CLOSE || this == FORCE_CLOSE_SCHEDULED

    companion object {
        fun from(code: String?): CloseReason? = entries.find { it.code == code }
    }
}
