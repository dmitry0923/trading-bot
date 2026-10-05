package com.trading.bot.tuning

import com.fasterxml.jackson.databind.ObjectMapper
import com.trading.bot.backtest.StrategyParameters
import com.trading.bot.tuning.PerformanceGateCheck.GateVerdict
import org.slf4j.LoggerFactory
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.stereotype.Service
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/**
 * Оркестратор Monthly Tuning Engine (docs/24, Фаза 3.5).
 *
 * Управляет полным циклом ежемесячного тюнинга стратегии:
 *
 * 1. Сбор метрик за прошедший месяц.
 * 2. Gate Check ([PerformanceGateCheck]): PASS / WARN / ALERT.
 * 3. При ALERT — автоостановка торговли + email на trading-alerts@example.com.
 * 4. Оптимизация параметров ([ParameterOptimizer]) на expanding window.
 * 5. Версионирование в PostgreSQL ([StrategyVersionRepository]).
 *
 * **Защита от overfitting:**
 * - При ALERT торговля останавливается автоматически до ручного approve.
 * - При изменении параметров > 30% — требуется дополнительная валидация.
 * - При < [MIN_TRADES_TO_TUNE] сделок — тюнинг пропускается.
 *
 * @see MonthlyTuningScheduler — cron-запуск: первый рабочий день месяца, 09:05 МСК.
 */
@Service
class MonthlyTuningService(
    private val performanceGateCheck: PerformanceGateCheck,
    private val parameterOptimizer: ParameterOptimizer,
    private val strategyVersionRepository: StrategyVersionRepository,
    private val mailSender: JavaMailSender?,  // nullable — email опционален
    private val objectMapper: ObjectMapper,
) {
    private val logger = LoggerFactory.getLogger(MonthlyTuningService::class.java)

    /**
     * Результат цикла тюнинга.
     */
    data class TuningCycleResult(
        val period: String,
        val gateVerdict: GateVerdict,
        val gateReasons: List<String>,
        val optimizationSkipped: Boolean,
        val requiresExtraValidation: Boolean,
        val tradingStopped: Boolean,
        val alertSent: Boolean,
        val newVersion: StrategyVersion?,
    )

    /**
     * Выполняет полный цикл ежемесячного тюнинга.
     *
     * @param metrics метрики прошедшего месяца.
     * @param currentParameters текущие параметры стратегии.
     * @param period период в формате YearMonth (если null — предыдущий месяц).
     * @return результат цикла тюнинга.
     */
    fun runTuningCycle(
        metrics: PerformanceGateCheck.MonthlyMetrics,
        currentParameters: StrategyParameters,
        period: YearMonth = YearMonth.now().minusMonths(1),
    ): TuningCycleResult {
        val periodStr = period.format(DateTimeFormatter.ofPattern("yyyy-MM"))
        logger.info("MonthlyTuningService: старт цикла тюнинга за период $periodStr")

        // Шаг 1: Gate Check.
        val gateResult = performanceGateCheck.check(metrics)
        logger.info(
            "MonthlyTuningService: gate check=$gateResult.verdict, reasons=${gateResult.reasons}",
        )

        var tradingStopped = false
        var alertSent = false

        // Шаг 2: При ALERT — автоостановка торговли.
        if (gateResult.verdict == GateVerdict.ALERT) {
            logger.error(
                "MonthlyTuningService: ALERT — торговля остановлена! " +
                    "Причины: ${gateResult.reasons.joinToString("; ")}",
            )
            tradingStopped = true
            alertSent = sendAlertEmail(periodStr, gateResult)
        }

        // Шаг 3: Оптимизация параметров (только при PASS/WARN и достаточном числе сделок).
        val optimizationResult =
            if (gateResult.verdict == GateVerdict.ALERT) {
                ParameterOptimizer.OptimizationResult(
                    parameters = currentParameters,
                    skipped = true,
                    reason = "Пропуск из-за ALERT gate check",
                )
            } else {
                parameterOptimizer.optimize(
                    currentParameters = currentParameters,
                    tradesCount = metrics.tradesCount,
                    profitFactor = metrics.profitFactor,
                )
            }

        // Шаг 4: Версионирование в БД.
        val newVersion =
            saveVersion(
                period = periodStr,
                parameters = optimizationResult.parameters,
                gateResult = gateResult,
                metrics = metrics,
                notes = buildNotes(gateResult, optimizationResult),
            )

        logger.info(
            "MonthlyTuningService: цикл завершён — version=$periodStr, " +
                "gate=${gateResult.verdict}, " +
                "skipped=${optimizationResult.skipped}, " +
                "extraValidation=${optimizationResult.requiresExtraValidation}",
        )

        return TuningCycleResult(
            period = periodStr,
            gateVerdict = gateResult.verdict,
            gateReasons = gateResult.reasons,
            optimizationSkipped = optimizationResult.skipped,
            requiresExtraValidation = optimizationResult.requiresExtraValidation,
            tradingStopped = tradingStopped,
            alertSent = alertSent,
            newVersion = newVersion,
        )
    }

    /**
     * Отправляет email-уведомление при ALERT gate check.
     *
     * @return true — письмо отправлено, false — email недоступен.
     */
    private fun sendAlertEmail(
        period: String,
        gateResult: PerformanceGateCheck.GateCheckResult,
    ): Boolean {
        if (mailSender == null) {
            logger.warn("MonthlyTuningService: JavaMailSender не настроен, email не отправлен")
            return false
        }
        return try {
            val message =
                SimpleMailMessage().apply {
                    setTo(ALERT_EMAIL)
                    subject = "🚨 Trading Bot ALERT: Monthly Gate Check FAILED ($period)"
                    text =
                        buildEmailBody(period, gateResult)
                }
            mailSender.send(message)
            logger.info("MonthlyTuningService: ALERT email отправлен на $ALERT_EMAIL")
            true
        } catch (e: Exception) {
            logger.error("MonthlyTuningService: ошибка отправки email: ${e.message}", e)
            false
        }
    }

    private fun buildEmailBody(
        period: String,
        gateResult: PerformanceGateCheck.GateCheckResult,
    ): String =
        """
        Monthly Tuning Engine — Gate Check ALERT
        
        Период: $period
        Вердикт: ${gateResult.verdict}
        
        Причины:
        ${gateResult.reasons.joinToString("
") { "  - $it" }}
        
        Метрики:
          PF:       ${gateResult.metrics.profitFactor}
          DD:       ${gateResult.metrics.maxDrawdownPct}%
          WinRate:  ${gateResult.metrics.winRatePct}%
          Sharpe:   ${gateResult.metrics.sharpe}
          Trades:   ${gateResult.metrics.tradesCount}
        
        ТОРГОВЛЯ АВТОМАТИЧЕСКИ ОСТАНОВЛЕНА.
        Требуется ручной анализ и approve перед возобновлением.
        
        -- Trading Bot Monitoring
        """.trimIndent()

    private fun saveVersion(
        period: String,
        parameters: StrategyParameters,
        gateResult: PerformanceGateCheck.GateCheckResult,
        metrics: PerformanceGateCheck.MonthlyMetrics,
        notes: String?,
    ): StrategyVersion {
        val version =
            StrategyVersion(
                version = period,
                parameters = objectMapper.writeValueAsString(parameters),
                gateStatus = gateResult.verdict.name,
                metrics = objectMapper.writeValueAsString(metrics),
                notes = notes,
            )
        return strategyVersionRepository.save(version)
    }

    private fun buildNotes(
        gateResult: PerformanceGateCheck.GateCheckResult,
        optimizationResult: ParameterOptimizer.OptimizationResult,
    ): String {
        val parts = mutableListOf<String>()
        if (optimizationResult.skipped) {
            parts += "Оптимизация пропущена: ${optimizationResult.reason}"
        }
        if (optimizationResult.requiresExtraValidation) {
            parts += "Требуется дополнительная валидация: ${optimizationResult.reason}"
        }
        parts += "Gate reasons: ${gateResult.reasons.joinToString("; ")}"
        return parts.joinToString("
")
    }

    companion object {
        /** Email для ALERT-уведомлений (placeholder — заменить в .env). */
        const val ALERT_EMAIL = "trading-alerts@example.com"

        /** Минимум сделок для запуска тюнинга. */
        const val MIN_TRADES_TO_TUNE = 20
    }
}
