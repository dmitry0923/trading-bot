package com.trading.bot.service

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.runBlocking
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/**
 * Обслуживание месячных партиций `positions` (RANGE по `opened_at`) и `agent_logs`
 * (RANGE по `created_at`), создаваемых миграцией 019 (PostgreSQL native partitioning,
 * раздел 6.4).
 *
 * Гарантия «горячего» диапазона:
 * - при старте приложения ([ApplicationReadyEvent]) синхронно создаётся партиция
 *   текущего месяца — первый торговый цикл никогда не ловит отсутствие партиции
 *   (страховка после горизонта, заложенного миграцией);
 * - @Scheduled-задача (раз в 6 часов) создаёт партиции на 3 месяца вперёд.
 *
 * Создание идемпотентно (проверка `to_regclass` перед `CREATE TABLE ... PARTITION OF`).
 * Если партиция создаётся для диапазона, где строки уже попали в DEFAULT-партицию,
 * PostgreSQL отклонит ATTACH с ошибкой «updated partition constraint ... would be violated» —
 * ошибка логируется, торговля продолжается (строки остаются в DEFAULT),
 * создание повторится на следующем цикле.
 */
@Service
class PartitionMaintenanceService(
    private val databaseClient: DatabaseClient,
) {
    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @PreDestroy
    fun close() {
        scope.cancel()
    }

    private data class PartitionedTable(
        val table: String,
        val partitionColumn: String,
    )

    companion object {
        private val MONTH = DateTimeFormatter.ofPattern("yyyyMM")
        private val TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        private const val LOOKAHEAD_MONTHS = 3

        private val TABLES =
            listOf(
                PartitionedTable("agent_logs", "created_at"),
                PartitionedTable("positions", "opened_at"),
            )
    }

    /** Стартовая гарантия: партиция текущего месяца создаётся до первого торгового цикла. */
    @EventListener(ApplicationReadyEvent::class)
    fun init() {
        try {
            runBlocking { ensureMonths(YearMonth.now(), 1) }
        } catch (e: Exception) {
            logger.warn(e) { "Partition maintenance init failed" }
        }
    }

    /** Фоновая поддержка: партиции на [LOOKAHEAD_MONTHS] месяца вперёд. */
    @Scheduled(fixedDelay = 6L * 60 * 60 * 1000, initialDelay = 60_000L)
    fun maintain() {
        scope.launch {
            try {
                ensureMonths(YearMonth.now(), LOOKAHEAD_MONTHS)
            } catch (e: Exception) {
                logger.error(e) { "Partition maintenance failed" }
            }
        }
    }

    /**
     * Создаёт месячные партиции для [TABLES] начиная с [start] на [count] месяцев
     * (включая стартовый). Повторные вызовы идемпотентны.
     *
     * @return имена созданных партиций
     */
    suspend fun ensureMonths(
        start: YearMonth,
        count: Int,
    ): List<String> {
        val created = mutableListOf<String>()
        for (offset in 0 until count) {
            val month = start.plusMonths(offset.toLong())
            TABLES.forEach { table ->
                if (createPartitionIfMissing(table, month)) {
                    created += "${table.table}_${month.format(MONTH)}"
                }
            }
        }
        if (created.isNotEmpty()) {
            logger.info { "Partition maintenance: created $created" }
        }
        return created
    }

    private suspend fun createPartitionIfMissing(
        table: PartitionedTable,
        month: YearMonth,
    ): Boolean {
        val partition = "${table.table}_${month.format(MONTH)}"
        val exists =
            databaseClient
                .sql("SELECT to_regclass(current_schema() || '.' || :name) IS NOT NULL AS found")
                .bind("name", partition)
                .map { row, _ -> row.get("found", Boolean::class.javaObjectType) ?: false }
                .one()
                .awaitSingle()
        if (exists) return false

        val from = month.atDay(1).atStartOfDay().format(TIMESTAMP)
        val to =
            month
                .plusMonths(1)
                .atDay(1)
                .atStartOfDay()
                .format(TIMESTAMP)
        databaseClient
            .sql(
                "CREATE TABLE $partition PARTITION OF ${table.table} " +
                    "FOR VALUES FROM ('$from') TO ('$to')",
            ).then()
            .awaitSingleOrNull()
        logger.info { "Partition $partition created (${table.partitionColumn} RANGE)" }
        return true
    }
}
