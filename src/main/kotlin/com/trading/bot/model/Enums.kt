package com.trading.bot.model

enum class StrategyAction {
    BUY,
    SELL,
    HOLD,
    CLOSE,
}

enum class PositionDirection {
    LONG,
    SHORT,
}

enum class PositionStatus {
    OPEN,
    CLOSED,
    TAKE_PROFIT,

    /**
     * Рассинхрон, требующий ручного вмешательства: позиция на бирже противоречит
     * локальной (например, direction mismatch). Не управляется ботом до разрешения.
     */
    RECONCILIATION_REQUIRED,
}

enum class InstrumentType {
    STOCK,
    FUTURES,
    FX,
}
