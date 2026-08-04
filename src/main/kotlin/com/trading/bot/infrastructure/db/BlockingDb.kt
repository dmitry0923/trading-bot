package com.trading.bot.infrastructure.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Перенос блокирующих операций (JDBC, Redis) с диспатчера корутин
 * на [Dispatchers.IO], чтобы не занимать потоки compute-пула.
 *
 * Каждая блокирующая операция репозитория, вызываемая из корутины, должна быть
 * обёрнута: `BlockingDb.io { positionRepo.findByStatus(status) }`.
 */
object BlockingDb {
    suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
}
