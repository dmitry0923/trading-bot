package com.trading.bot.infrastructure.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Перенос блокирующих операций (Redis, любые нереактивные клиенты) с диспатчера корутин
 * на [Dispatchers.IO], чтобы не занимать потоки compute-пула.
 *
 * Примечание: после миграции персистентного слоя на R2DBC JDBC-обёртки больше не нужны —
 * репозитории стали suspend. BlockingDb остаётся только для блокирующего Redis
 * ([com.trading.bot.service.RedisCacheService]) и прочих блокирующих библиотек.
 *
 * Каждая блокирующая операция, вызываемая из корутины, должна быть
 * обёрнута: `BlockingDb.io { redis.getStrategy(ticker) }`.
 */
object BlockingDb {
    suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
}
