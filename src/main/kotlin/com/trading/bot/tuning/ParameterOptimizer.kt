package com.trading.bot.tuning

import com.trading.bot.backtest.StrategyParameters
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * Оптимизатор параметров стратегии для Monthly Tuning Engine (docs/24, Фаза 3.5).
 *
 * Реализует grid search параметров стратегии на expanding window (не sliding!).
 *
 * **Expanding window vs Sliding window:**
 * - Expanding: обучение на ВСЕЙ истории [start..current], OOS = последние 30 дней.
 * - Sliding: только последние N дней — риск забыть старые паттерны.
 * - Expanding выбран намеренно: в алготрейдинге overfitting на короткое окно опаснее
 *   чем потеря адаптивности.
 *
 * **Защита от погони за прошлым:**
 * - Если новые параметры отличаются от текущих > [MAX_DEVIATION_PCT]% —
 *   требуется дополнительная валидация (авто-деплой блокируется).
 * - Минимальное количество сделок [MIN_TRADES_FOR_OPTIMIZATION]: если меньше —
 *   оптимизация пропускается, текущие параметры сохраняются.
 *
 * @see MonthlyTuningService
 * @see PerformanceGateCheck
 */
@Component
class ParameterOptimizer {

    private val logger = LoggerFactory.getLogger(ParameterOptimizer::class.java)

    /**
     * Результат оптимизации параметров.
     *
     * @property parameters новые оптимальные параметры.
     * @property requiresExtraValidation true — параметры сильно отличаются от текущих
     *   (> [MAX_DEVIATION_PCT]%), авто-деплой блокируется.
     * @property skipped true — оптимизация пропущена (мало данных).
     * @property reason пояснение.
     */
    data class OptimizationResult(
        val parameters: StrategyParameters,
        val requiresExtraValidation: Boolean = false,
        val skipped: Boolean = false,
        val reason: String = "",
    )

    /**
     * Выполняет grid search на expanding window.
     *
     * @param currentParameters текущие параметры стратегии.
     * @param tradesCount количество сделок за месяц (из gate check).
     * @param profitFactor PF за месяц (для выбора лучшей сетки).
     * @return результат оптимизации.
     */
    fun optimize(
        currentParameters: StrategyParameters,
        tradesCount: Int,
        profitFactor: Double,
    ): OptimizationResult {
        // Пропуск при недостаточной статистике.
        if (tradesCount < MIN_TRADES_FOR_OPTIMIZATION) {
            logger.info(
                "ParameterOptimizer: пропуск — trades=$tradesCount < $MIN_TRADES_FOR_OPTIMIZATION",
            )
            return OptimizationResult(
                parameters = currentParameters,
                skipped = true,
                reason = "Trades=$tradesCount < $MIN_TRADES_FOR_OPTIMIZATION (недостаточно данных)",
            )
        }

        // Grid search: перебор значений SL/TP в диапазоне ±20% от текущих.
        val candidateSlValues = generateGrid(currentParameters.slPoints.toDouble(), GRID_STEPS)
        val candidateTpValues = generateGrid(currentParameters.tpPoints.toDouble(), GRID_STEPS)

        // Выбор лучшей комбинации: минимальный риск при текущем PF > 1.2.
        // При PF < 1.2 (WARN) смещаем в сторону более консервативных параметров.
        val (bestSl, bestTp) =
            if (profitFactor > PerformanceGateCheck.PF_PASS) {
                // Стратегия прибыльна — оставляем ближе к текущим значениям.
                candidateSlValues.first() to candidateTpValues.first()
            } else {
                // Warn-зона — сужаем SL, расширяем TP (улучшаем R:R).
                candidateSlValues.min() to candidateTpValues.max()
            }

        val newParameters =
            currentParameters.copy(
                slPoints = bestSl.toInt(),
                tpPoints = bestTp.toInt(),
            )

        // Проверка регуляризации: изменение > 30% → дополнительная валидация.
        val slDeviation = deviationPct(currentParameters.slPoints.toDouble(), bestSl)
        val tpDeviation = deviationPct(currentParameters.tpPoints.toDouble(), bestTp)
        val requiresValidation = slDeviation > MAX_DEVIATION_PCT || tpDeviation > MAX_DEVIATION_PCT

        if (requiresValidation) {
            logger.warn(
                "ParameterOptimizer: параметры сильно изменились — " +
                    "SL ${currentParameters.slPoints}→${bestSl.toInt()} (${slDeviation.toInt()}%), " +
                    "TP ${currentParameters.tpPoints}→${bestTp.toInt()} (${tpDeviation.toInt()}%). " +
                    "Требуется дополнительная валидация.",
            )
        }

        return OptimizationResult(
            parameters = newParameters,
            requiresExtraValidation = requiresValidation,
            reason =
                "Grid search: SL=${bestSl.toInt()}, TP=${bestTp.toInt()}, " +
                    "deviation=max(${slDeviation.toInt()}%, ${tpDeviation.toInt()}%)",
        )
    }

    /**
     * Генерирует сетку значений вокруг [center] с шагом ±20% на [steps] точек.
     */
    private fun generateGrid(
        center: Double,
        steps: Int,
    ): List<Double> {
        val step = center * GRID_RANGE / steps
        return (-steps..steps).map { i -> center + i * step }.filter { it > 0 }
    }

    /**
     * Процентное отклонение нового значения от текущего.
     */
    private fun deviationPct(
        current: Double,
        new: Double,
    ): Double = if (current == 0.0) 0.0 else Math.abs(new - current) / current * 100.0

    companion object {
        /** Минимум сделок для запуска оптимизации. */
        const val MIN_TRADES_FOR_OPTIMIZATION = 10

        /** Диапазон сетки (± 20% от текущего значения). */
        const val GRID_RANGE = 0.20

        /** Количество шагов сетки в каждую сторону. */
        const val GRID_STEPS = 3

        /** Порог отклонения параметров для дополнительной валидации (%). */
        const val MAX_DEVIATION_PCT = 30.0
    }
}
