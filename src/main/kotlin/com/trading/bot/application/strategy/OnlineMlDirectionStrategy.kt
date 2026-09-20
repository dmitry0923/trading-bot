package com.trading.bot.application.strategy

import com.trading.bot.domain.ml.MlFeatureExtractor
import com.trading.bot.domain.ml.OnlineLogisticRegression
import com.trading.bot.domain.strategy.Strategy
import com.trading.bot.domain.strategy.StrategyContext
import com.trading.bot.domain.strategy.StrategyDecision
import com.trading.bot.model.StrategyAction
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Research-оверрайд параметров [OnlineMlDirectionStrategy] для калибровки через
 * query-параметры API (паттерн funding-veto): null → значение из `bt.ml-direction-*`.
 */
data class MlDirectionOverrides(
    val enabled: Boolean? = null,
    val horizonBars: Int? = null,
    val minReturnPercent: Double? = null,
    val learningRate: Double? = null,
    val l2: Double? = null,
    val signalMargin: Double? = null,
    /** fail-closed поведение фильтра при неизвестном ML-направлении (генератор, не стратегия). */
    val blockOnUnknown: Boolean? = null,
) {
    val anyProvided: Boolean
        get() =
            enabled != null ||
                horizonBars != null ||
                minReturnPercent != null ||
                learningRate != null ||
                l2 != null ||
                signalMargin != null ||
                blockOnUnknown != null
}

/**
 * Онлайн-логистическая регрессия направления (research, 2026-09-19, docs/17 этап 4b).
 *
 * В отличие от панельных детерминированных стратегий (RSI/BB/EMA-правила), эта
 * стратегия САМА обучает P(up) инкрементальной логистической регрессией на барах
 * истории и выставляет BUY/SELL по калиброванной вероятности.
 *
 * Дизайн (без lookahead, паритет live-потокам данных):
 *   - признаки: 9 технических признаков [MlFeatureExtractor.Features] на текущем
 *     баре (rsi14, atrPercent, macdHistogramPercent, bbPercentB, emaSlopePercent,
 *     volatility20Percent, return3/10/20) — те же, что в live ML-фильтре;
 *   - метка: знак доходности за [horizonBars] баров от точки входа. Пример обучается
 *     ТОЛЬКО когда его горизонт закрылся: стратегия хранит кольцевой буфер
 *     (features, close) последних [horizonBars] баров и обновляет веса на самом
 *     старом элементе, когда он «выходит» за горизонт относительно текущего бара —
 *     без взгляда в будущее;
 *   - обучение: онлайн-SGD (L2), ОДИН шаг на пример, веса сбрасываются на старте
 *     каждой симуляции (по [cycleId]) — изоляция между фолдами WFA и MC-симуляциями;
 *   - сигнал: BUY при P(up) > 0.5+margin, SELL при P(up) < 0.5-margin, HOLD иначе.
 *     СИЛА сигнала (2*|P-0.5|) в live-конкуренции не используется — стратегия
 *     применяется КАК ФИЛЬТР НАПРАВЛЕНИЯ к победителю-стратегии (см.
 *     LiveStrategyBacktestSignalGenerator): veto при противоречии направления.
 *   - warmup: [minSamples] обучающих примеров до первого сигнала (SGD сходится
 *     быстро, но без warmup ранние P шумные).
 *
 * research-инструмент: включается только bt.ml-direction-enabled, на live-входы
 * не влияет (в live-конвейере стратегии нет).
 */
class OnlineMlDirectionStrategy(
    private val lookbackBars: Int = 30,
    private val horizonBars: Int = 6,
    private val minReturnPercent: Double = 0.05,
    private val learningRate: Double = 0.05,
    private val l2: Double = 0.001,
    private val signalMargin: Double = 0.05,
    private val minSamples: Int = 30,
) : Strategy {
    override val id = "ML_DIRECTION"

    private var model: OnlineLogisticRegression = OnlineLogisticRegression(FEATURE_COUNT, learningRate, l2)
    private var activeCycleId: String? = null

    private val pending = ArrayDeque<Pair<DoubleArray, BigDecimal>>()

    override suspend fun evaluate(context: StrategyContext): StrategyDecision {
        val price = context.snapshot.currentPrice

        // Сброс модели на старте новой симуляции (явление каждого simulate():
        // cycleId уникален для прогона). Предотвращает утечку между фолдами WFA
        // и базовым/stress прогонами MC.
        if (context.cycleId != activeCycleId) {
            model.reset()
            pending.clear()
            activeCycleId = context.cycleId
        }

        val raw =
            MlFeatureExtractor.extract(context.candles, lookbackBars)
        val features =
            raw?.let { normalize(it) }
                ?: return StrategyDecision.hold(price, "Insufficient bars for ML features")

        val lastClose = context.candles.lastOrNull()?.closePrice ?: price

        // Обучаем пример, у которого горизонт только что закрылся: когда буфер
        // заполнился больше, чем horizonBars, самый старый элемент стал «в прошлом»
        // ровно на horizonBars баров от текущего.
        pending.addLast(features to lastClose)
        if (pending.size > horizonBars) {
            val (labelFeatures, labelClose) = pending.removeFirst()
            val ret = (lastClose.toDouble() / labelClose.toDouble() - 1.0) * 100.0
            val label =
                when {
                    ret >= minReturnPercent -> 1.0
                    ret <= -minReturnPercent -> 0.0
                    else -> null
                }
            if (label != null) {
                model.update(labelFeatures, label)
            }
        }

        if (model.trainedSamples < minSamples) {
            return StrategyDecision.hold(price, "ML direction warmup (${model.trainedSamples}/$minSamples samples)")
        }

        val pUp = model.predict(features)
        val strength = (2.0 * kotlin.math.abs(pUp - 0.5)).coerceIn(0.0, 1.0)

        if (strength < signalMargin) {
            return StrategyDecision.hold(price, "ML direction probability $pUp within margin")
        }

        val action = if (pUp > 0.5) StrategyAction.BUY else StrategyAction.SELL
        val reasoning = "OnlineLogisticRegression P(up)=${round(pUp)} trained=${model.trainedSamples} -> $action"
        return StrategyDecision(action, price, strength, reasoning)
    }

    /** Фиксированная нормализация признаков (без данных-зависимых статистик). */
    private fun normalize(features: MlFeatureExtractor.Features): DoubleArray =
        doubleArrayOf(
            features.rsi14 / 100.0,
            features.atrPercent / 10.0,
            features.macdHistogramPercent / 10.0,
            features.bbPercentB / 100.0,
            features.emaSlopePercent / 10.0,
            features.volatility20Percent / 20.0,
            features.return3 / 10.0,
            features.return10 / 10.0,
            features.return20 / 10.0,
        )

    private fun round(v: Double): String = v.toBigDecimal().setScale(3, RoundingMode.HALF_UP).toPlainString()

    companion object {
        const val FEATURE_COUNT = 9

        /** Создание стратегии из конфига бэктеста (или null, если выключена). */
        fun from(config: com.trading.bot.config.BacktestConfig): OnlineMlDirectionStrategy? = from(config, MlDirectionOverrides())

        /**
         * Создание стратегии из конфига бэктеста с research-оверрайдами (null →
         * значение из [com.trading.bot.config.BacktestConfig]). Калибровка порогов
         * через query-параметры (см. MlDirectionOverrides) без перезапуска.
         */
        fun from(
            config: com.trading.bot.config.BacktestConfig,
            overrides: MlDirectionOverrides,
        ): OnlineMlDirectionStrategy? =
            if (overrides.enabled ?: config.mlDirectionEnabled) {
                OnlineMlDirectionStrategy(
                    horizonBars = overrides.horizonBars ?: config.mlDirectionHorizonBars,
                    minReturnPercent = overrides.minReturnPercent ?: config.mlDirectionMinReturnPercent,
                    learningRate = overrides.learningRate ?: config.mlDirectionLearningRate,
                    l2 = overrides.l2 ?: config.mlDirectionL2,
                    signalMargin = overrides.signalMargin ?: config.mlDirectionSignalMargin,
                )
            } else {
                null
            }
    }
}
