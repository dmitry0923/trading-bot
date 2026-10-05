package com.trading.bot.tuning

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

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

    /**
     * Автоматический запуск в первый рабочий день месяца в 09:05 МСК.
     *
     * Cron: `0 5 9 1-7 * MON-FRI` — 1-7 число месяца, пн-пт, 09:05.
     */
    @Scheduled(cron = "0 5 9 1-7 * MON-FRI", zone = "Europe/Moscow")
    fun runMonthlyTuning() {
        logger.info("MonthlyTuningScheduler: запуск планового цикла тюнинга")
        runCycle()
    }

    /**
     * Ручной запуск цикла тюнинга (для тестирования или force-tuning).
     *
     * Вызывается через API или вручную в коде.
     */
    fun runNow() {
        logger.info("MonthlyTuningScheduler: ручной запуск цикла тюнинга")
        runCycle()
    }

    private fun runCycle() {
        try {
            val (metrics, parameters) = tuningMetricsProvider.collectCurrentMonthlyData()
            val result = monthlyTuningService.runTuningCycle(metrics, parameters)
            logger.info(
                "MonthlyTuningScheduler: цикл завершён — " +
                    "period=${result.period}, gate=${result.gateVerdict}, " +
                    "tradingStopped=${result.tradingStopped}",
            )
        } catch (e: Exception) {
            logger.error("MonthlyTuningScheduler: ошибка в цикле тюнинга: ${e.message}", e)
        }
    }
}
