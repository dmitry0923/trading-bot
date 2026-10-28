package com.trading.bot.tuning

import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.YearMonth
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cron-планировщик Monthly Tuning Engine (docs/24, Фаза 3.5).
 *
 * Запускает цикл ежемесячного тюнинга в первый рабочий день месяца в 09:05 МСК.
 *
 * **Расписание:** `0 5 9 1-7 * MON-FRI` — в диапазоне 1-7 числа месяца в понедельник-пятницу,
 * что соответствует первому рабочему дню месяца. Cron Spring использует серверное время;
 * для корректной работы сервер должен быть настроен на московский часовой пояс (Europe/Moscow)
 * или timezone указывается явно через `@Scheduled(zone = "Europe/Moscow")`.
 *
 * **Режим работы:**
 * - В production цикл запускается автоматически по расписанию.
 * - Для ручного запуска (тестирование, force-tuning) доступен метод [runNow].
 *
 * @see MonthlyTuningService — оркестратор цикла тюнинга.
 */
@Component
@ConditionalOnProperty(name = ["tuning.enabled"], havingValue = "true")
class MonthlyTuningScheduler(
    private val monthlyTuningService: MonthlyTuningService,
    private val tuningMetricsProvider: TuningMetricsProvider,
    /**
     * Журнал версий: единственный персистентный признак «тюнинг за период уже был».
     * Без него после рестарта cron (дни 2–7) повторял цикл в том же месяце — снова
     * дёргая ALERT-остановку торговли и падая на unique-индексе (скан-9 P2).
     */
    private val strategyVersionRepository: StrategyVersionRepository? = null,
) {
    private val logger = LoggerFactory.getLogger(MonthlyTuningScheduler::class.java)

    // Цикл асинхронный (suspend/R2DBC), поэтому запускается в корутине.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val moscowZone: ZoneId = ZoneId.of("Europe/Moscow")

    /** Защита от наложения: ручной запуск во время планового не стартует второй цикл. */
    private val running = AtomicBoolean(false)

    /**
     * Период последнего выполненного цикла (YYYY-MM).
     *
     * Cron `0 5 9 1-7 * MON-FRI` в Spring означает «каждый будний день с 1 по 7 число»
     * (day-of-month и day-of-week соединяются по AND), т.е. до 5 запусков в месяц, а не
     * «первый рабочий день». Повторный цикл в том же периоде падал на unique-индексе
     * `strategy_versions(version)` и повторно дёргал ALERT-остановку торговли —
     * поэтому период идемпотентен (скан-6 P2).
     */
    @Volatile
    private var lastRunPeriod: String? = null

    /**
     * Автоматический цикл: будни 1–7 числа в 09:05 МСК (фактически — первый рабочий день,
     * т.к. повтор в том же месяце отсекается [lastRunPeriod]).
     */
    @Scheduled(cron = "0 5 9 1-7 * MON-FRI", zone = "Europe/Moscow")
    fun runMonthlyTuning() {
        logger.info("MonthlyTuningScheduler: плановый запуск месячного тюнинга")
        runCycle(force = false)
    }

    /**
     * Ручной запуск цикла тюнинга (для тестирования или force-tuning).
     *
     * Вызывается через API или вручную; период-гард игнорируется (force), но защита от
     * наложения активна.
     */
    fun runNow() {
        logger.info("MonthlyTuningScheduler: ручной запуск месячного тюнинга")
        runCycle(force = true)
    }

    @PreDestroy
    fun close() {
        scope.cancel()
    }

    /**
     * Целевой период тюнинга — ПРЕДЫДУЩИЙ месяц: ровно его возвращает
     * `MonthlyTuningService.runTuningCycle` по умолчанию. Гард идемпотентности обязан
     * сравнивать тот же ключ, иначе cron 1–7 числа выполнял до 5 циклов в месяц
     * (повторные halt/письма) — скан-16 P1.
     */
    internal fun targetPeriod(): String = YearMonth.now(moscowZone).minusMonths(1).toString()

    /** @deprecated оставлено для совместимости тестов: использовать [targetPeriod]. */
    internal fun currentPeriod(): String = YearMonth.now(moscowZone).toString()

    /** Тюнинг за [period] уже зафиксирован в журнале версий (best-effort: БД может быть недоступна). */
    private suspend fun alreadyTuned(period: String): Boolean =
        runCatching { strategyVersionRepository?.findByVersion(period) != null }.getOrDefault(false)

    private fun runCycle(force: Boolean) {
        val period = targetPeriod()
        // Период-гард ДО захвата флага: иначе повторный cron-запуск в том же месяце
        // оставлял running=true и планировщик замолкал навсегда (скан-9 P1).
        if (!force && lastRunPeriod == period) {
            logger.info("MonthlyTuningScheduler: тюнинг за $period уже выполнен - пропуск")
            return
        }
        if (!running.compareAndSet(false, true)) {
            logger.warn("MonthlyTuningScheduler: цикл уже выполняется - запуск за период $period пропущен")
            return
        }
        scope.launch {
            try {
                // Персистентный гард: после рестарта период восстанавливается из БД.
                if (!force && lastRunPeriod != period && alreadyTuned(period)) {
                    lastRunPeriod = period
                    logger.info("MonthlyTuningScheduler: тюнинг за $period уже в журнале версий - пропуск")
                    return@launch
                }
                val (metrics, parameters) = tuningMetricsProvider.collectCurrentMonthlyData()
                val result = monthlyTuningService.runTuningCycle(metrics, parameters, YearMonth.parse(period))
                // Гард помнит ТОТ ЖЕ ключ, по которому проверяет (`targetPeriod`), иначе
                // повторные тики в том же месяце проходили проверку и цикл выполнялся
                // до 5 раз с повторным halt (скан-16 P1).
                lastRunPeriod = period
                logger.info(
                    "MonthlyTuningScheduler: цикл завершён - " +
                        "period=${result.period}, gate=${result.gateVerdict}, " +
                        "tradingStopped=${result.tradingStopped}",
                )
            } catch (e: Exception) {
                logger.error("MonthlyTuningScheduler: ошибка в цикле тюнинга: ${e.message}", e)
            } finally {
                running.set(false)
            }
        }
    }
}
