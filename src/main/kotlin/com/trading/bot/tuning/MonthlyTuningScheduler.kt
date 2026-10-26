package com.trading.bot.tuning

import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
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
class MonthlyTuningScheduler(
    private val monthlyTuningService: MonthlyTuningService,
    private val tuningMetricsProvider: TuningMetricsProvider,
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

    internal fun currentPeriod(): String = YearMonth.now(moscowZone).toString()

    private fun runCycle(force: Boolean) {
        val period = currentPeriod()
        // CAS: поток cron и ручной runNow() не должны запустить два цикла (скан-8 P2).
        if (!running.compareAndSet(false, true)) {
            logger.warn("MonthlyTuningScheduler: цикл уже выполняется - запуск за период $period пропущен")
            return
        }
        if (!force && lastRunPeriod == period) {
            logger.info("MonthlyTuningScheduler: тюнинг за $period уже выполнен - пропуск")
            return
        }
        scope.launch {
            try {
                val (metrics, parameters) = tuningMetricsProvider.collectCurrentMonthlyData()
                val result = monthlyTuningService.runTuningCycle(metrics, parameters)
                lastRunPeriod = result.period.ifBlank { period }
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
