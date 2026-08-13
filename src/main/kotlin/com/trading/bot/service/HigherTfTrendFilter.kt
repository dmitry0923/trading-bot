package com.trading.bot.service

import com.trading.bot.config.MtfConfig
import com.trading.bot.domain.signal.Signal
import com.trading.bot.domain.technical.CandleResampler
import com.trading.bot.domain.technical.IndicatorCalculator
import com.trading.bot.model.StrategyAction
import com.trading.bot.model.entity.Candle
import com.trading.bot.repository.CandleRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import org.springframework.stereotype.Service
import java.time.LocalDateTime

/**
 * Multi-timeframe фильтр тренда входа (roadmap v2.5, раздел 13.12.1).
 *
 * Входы по младшему таймфрейму (10-мин) гейтятся трендом старшего таймфрейма
 * ([MtfConfig.higherTimeframe], по умолчанию HOUR_1). Тренд вычисляется из свечей
 * младшего ТФ, агрегированных ресемплером [CandleResampler] в старший ТФ
 * (без обращения к MOEX: старшие свечи нигде не хранятся).
 *
 * Вызывается из `DecisionEngine` после risk- и ML-этапов: BUY при тренде старшего
 * ТФ DOWN блокируется, SELL при тренде UP блокируется; HOLD всегда пропускается.
 *
 * Ядро [shouldBlock] параметризовано временем `at` и флагом `requireEnabled` —
 * тот же фильтр используется в бэктесте (раздел 13.12.1): старший ТФ строится на
 * исторический момент бара (point-in-time через `completedBefore` ресемплера),
 * включение управляется отдельным флагом `bt.mtf-filter-enabled` без влияния на
 * live-гейт.
 *
 * Политика отказов:
 * - фильтр выключен — pass-through (`requireEnabled=true` и `mtf.filter.enabled=false`;
 *   `requireEnabled=false` — принудительное включение, используется бэктестом);
 * - фильтр включён, но данных недостаточно для тренда (< 30 баров старшего ТФ) —
 *   БЛОК (fail-closed: оператор явно включил фильтр, вход без тренда недопустим);
 * - тренд старшего ТФ противоположен действию — БЛОК (result=REJECT);
 * - тренд совпадает или SIDEWAYS — PASS.
 */
@Service
class HigherTfTrendFilter(
    private val mtfConfig: MtfConfig,
    private val candleRepository: CandleRepository,
    private val meterRegistry: MeterRegistry,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Live-вход из [com.trading.bot.application.decision.DecisionEngine]: тренд
     * строится по свечам того же таймфрейма, что и сигнал, за lookback
     * `bars × длительность старшего ТФ`.
     *
     * @return причина блокировки входа или null, если вход разрешён
     */
    suspend fun shouldBlock(signal: Signal): String? {
        if (!mtfConfig.enabled || !isDirectional(signal.action)) return null
        val now = LocalDateTime.now()
        val lookbackMinutes = mtfConfig.bars * CandleResampler.durationMinutes(mtfConfig.higherTimeframe)
        val candles =
            candleRepository.findByTickerAndTimeframeAndTimeBetween(
                ticker = signal.ticker,
                timeframe = signal.timeframe,
                from = now.minusMinutes(lookbackMinutes),
                to = now,
            )
        return shouldBlock(
            ticker = signal.ticker,
            action = signal.action,
            sourceCandles = candles,
            at = now,
            requireEnabled = false,
        )
    }

    /**
     * Ядро фильтра. Старший ТФ строится по [sourceCandles] с point-in-time
     * обрезкой на момент `at` (live — сейчас, бэктест — время бара).
     * [requireEnabled]=false форсирует включение фильтра независимо от
     * `mtf.filter.enabled` (используется бэктестом, раздел 13.9.1).
     */
    suspend fun shouldBlock(
        ticker: String,
        action: StrategyAction,
        sourceCandles: List<Candle>,
        at: LocalDateTime,
        requireEnabled: Boolean = true,
    ): String? {
        if (requireEnabled && !mtfConfig.enabled) return null
        if (!isDirectional(action)) return null

        val trend = trendOf(sourceCandles, at)
        if (trend == null) {
            return blocked(
                ticker,
                action,
                "insufficient candles for higher-TF trend " +
                    "(need >= 30 ${mtfConfig.higherTimeframe} bars, got ${resampledCount(sourceCandles, at)})",
            )
        }
        return when {
            action == StrategyAction.BUY && trend == "DOWN" -> {
                reject(ticker, action, "higher-TF trend DOWN opposes BUY")
            }

            action == StrategyAction.SELL && trend == "UP" -> {
                reject(ticker, action, "higher-TF trend UP opposes SELL")
            }

            else -> {
                pass(ticker)
            }
        }
    }

    private fun trendOf(
        sourceCandles: List<Candle>,
        at: LocalDateTime,
    ): String? {
        val resampled = CandleResampler.resample(sourceCandles, mtfConfig.higherTimeframe, completedBefore = at)
        return IndicatorCalculator.calculate(resampled)?.trend
    }

    private fun resampledCount(
        sourceCandles: List<Candle>,
        at: LocalDateTime,
    ): Int = CandleResampler.resample(sourceCandles, mtfConfig.higherTimeframe, completedBefore = at).size

    private fun isDirectional(action: StrategyAction): Boolean = action == StrategyAction.BUY || action == StrategyAction.SELL

    private fun reject(
        ticker: String,
        action: StrategyAction,
        reason: String,
    ): String {
        meterRegistry.counter("mtf.entry.filter", Tags.of("ticker", ticker, "result", "REJECT")).increment()
        logger.warn { "Higher-TF filter rejected $ticker $action: $reason" }
        return "Higher-TF filter: $reason"
    }

    private fun blocked(
        ticker: String,
        action: StrategyAction,
        reason: String,
    ): String {
        meterRegistry.counter("mtf.entry.filter", Tags.of("ticker", ticker, "result", "FAIL_CLOSED")).increment()
        logger.warn { "Higher-TF filter blocked $ticker $action: $reason" }
        return "Higher-TF filter: $reason"
    }

    private fun pass(ticker: String): String? {
        meterRegistry.counter("mtf.entry.filter", Tags.of("ticker", ticker, "result", "PASS")).increment()
        return null
    }
}
