package com.trading.bot.model

enum class StrategyAction {
    BUY, SELL, HOLD, CLOSE
}

enum class PositionDirection {
    LONG, SHORT
}

enum class PositionStatus {
    OPEN, CLOSED, TAKE_PROFIT
}
