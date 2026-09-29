package com.trading.bot.backtest

import com.trading.bot.agent.ArbitratorAgent
import com.trading.bot.agent.ContrarianAgent
import com.trading.bot.agent.StrategyAgent
import com.trading.bot.config.BacktestConfig
import com.trading.bot.domain.technical.IndicatorCalculator
import com.trading.bot.infrastructure.llm.PromptRegistry
import com.trading.bot.model.StrategyAction
import com.trading.bot.model.dto.FundamentalReport
import com.trading.bot.model.dto.MarketSnapshot
import com.trading.bot.model.dto.TechnicalReport
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * Query-оверрайды research-LLM-veto (паттерн funding-veto/ML: null → `bt.llm-veto-*`).
 */
data class LlmVetoOverrides(
    val enabled: Boolean? = null,
    val budgetMs: Long? = null,
    val blockOnUnknown: Boolean? = null,
    val promptVersion: String? = null,
    val sampleEvery: Int? = null,
    val minScore: Double? = null,
) {
    val anyProvided: Boolean
        get() =
            enabled != null ||
                budgetMs != null ||
                blockOnUnknown != null ||
                promptVersion != null ||
                sampleEvery != null ||
                minScore != null
}

/**
 * Параметры research-LLM-veto поверх детерминированного сигнала.
 *
 * @property enabled вкл. veto (по умолчанию выключено — live-путь не затронут).
 * @property budgetMs бюджет пары LLM-вызовов (контрариан + арбитр); превышение —
 *   fail-closed блок (`blockOnUnknown`) либо пропуск.
 * @property blockOnUnknown отказ LLM (недоступен/схема/парсинг/таймаут) → блок входа
 *   (true, fail-closed) или пропуск кандидата (false). Настоящий вердикт HOLD
 *   блокирует при любом значении.
 * @property promptVersion версия шаблонов промптов агентов.
 * @property sampleEvery 0 — veto каждого кандидата; N>1 — только каждого N-го
 *   (разбавленный veto, отдельная гипотеза со своим размытием эффекта).
 * @property minScore порог численного score входа (`null` = режим выключен).
 *   Бинарная семантика (`final.action == HOLD`) на измерение вырождена: LLM на
 *   вопрос «отвергнуть ли?» отвечает либо всегда HOLD, либо никогда (замер
 *   2026-09-29, kimi-k3: `aggressive`/`signal` — 0% блокировок,
 *   `default`/`conservative`/`veto` — 100%). Поэтому score-режим переносит
 *   решение на порог: арбитр оценивает качество входа числом в
 *   `signalStrength` (0..1), блок — при `score < minScore`. Направление и цена,
 *   как и раньше, игнорируются: измеряется veto, а не генерация.
 * @property temperature детерминизм прогонов (0.0).
 * @property cacheNamespace изоляция кэша агентов от live-контура.
 */
data class LlmVetoSettings(
    val enabled: Boolean = false,
    val budgetMs: Long = 20_000,
    val blockOnUnknown: Boolean = true,
    val promptVersion: String = PromptRegistry.DEFAULT_VERSION,
    val sampleEvery: Int = 0,
    val minScore: Double? = null,
    val temperature: Double = 0.0,
    val cacheNamespace: String = "backtest-veto",
) {
    init {
        require(sampleEvery >= 0) { "bt.llm-veto-sample-every must be >= 0, got $sampleEvery" }
        require(budgetMs >= 0) { "bt.llm-veto-budget-ms must be >= 0, got $budgetMs" }
        require(minScore == null || minScore in 0.0..1.0) {
            "bt.llm-veto-min-score must be within 0.0..1.0, got $minScore"
        }
    }

    companion object {
        fun from(
            config: BacktestConfig,
            overrides: LlmVetoOverrides? = null,
        ): LlmVetoSettings =
            LlmVetoSettings(
                enabled = overrides?.enabled ?: config.llmVetoEnabled,
                budgetMs = overrides?.budgetMs ?: config.llmVetoBudgetMs,
                blockOnUnknown = overrides?.blockOnUnknown ?: config.llmVetoBlockOnUnknown,
                promptVersion = overrides?.promptVersion ?: config.llmVetoPromptVersion,
                sampleEvery = overrides?.sampleEvery ?: config.llmVetoSampleEvery,
                minScore = overrides?.minScore ?: config.llmVetoMinScore,
                temperature = config.llmVetoTemperature,
                cacheNamespace = config.llmVetoCacheNamespace,
            )
    }
}

/**
 * Бинарный veto LLM поверх детерминированного сигнала (research, 2026-09-29).
 *
 * Проверяемая гипотеза: LLM **не генерирует** сигналы (это уже измерено и
 * отвергнуто: PF 0,71–0,81 на пяти моделях, docs/19), а **отсекает убыточные
 * входы** из детерминированной выборки. Гипотеза ранее не измерялась.
 *
 * Механика — переиспользование боевой цепочки, а не новый контракт вердикта
 * (docs/20 §10.1):
 *
 * 1. `TechnicalReport` строится детерминированно из [IndicatorCalculator.Indicators]
 *    (RSI/ATR/MACD/BB/тренд) — лишних LLM-вызовов нет, арбитр видит тот же
 *    рыночный контекст, что и детерминированная стратегия;
 * 2. `FundamentalReport` = NEUTRAL («в бэктесте фундаментальных данных нет») —
 *    выдумывать фундамент нельзя, иначе измерялся бы шум промпта;
 * 3. `StrategyAgent.Draft` = детерминированный сигнал (действие/сила/цена бара);
 * 4. [ContrarianAgent.challenge] → [ArbitratorAgent.adjudicate];
 * 5. **Veto = `final.action == HOLD`.** Направление, цену и силу, которые вернул
 *    арбитр, игнорируем: вход остаётся детерминированным, иначе получилось бы
 *    измерение генерации, а не veto.
 *
 * Fail-closed семантика не изобретается, а используется готовая: CRITICAL
 * challenge ⇒ блок ещё до вызова арбитра ([ArbitratorAgent]), LLM недоступен /
 * схема не прошла / парсинг ⇒ HOLD ⇒ блок. `blockOnUnknown` решает судьбу только
 * **отказов**; настоящий вердикт HOLD блокирует всегда.
 *
 * Детерминизм (docs/20 §10.3):
 * - semantic cache агентов обходится (`bypassCache = true`): её отпечаток грубый
 *   (цена до 1 знака, бакет RSI×10, `session` от `LocalTime.now()`) и дал бы
 *   вердикт одного бара для другого и невоспроизводимый прогон;
 * - вердикт мемоизируется по (ticker, время бара, действие): WFA заново проигрывает
 *   одни и те же бары в каждом фолде и в сетке подбора SL/TP, при `temperature = 0`
 *   ответ на идентичный вход не меняется. `cycleId` в ключ не входит — он
 *   меняется между прогонами (как в [LlmTimeoutInjection]).
 *
 * @property contrarianAgent агент-«адвокат дьявола» (LLM-оценка риска входа).
 * @property arbitratorAgent арбитр (LLM-финальный вердикт по draft'у).
 */
class LlmVeto(
    private val contrarianAgent: ContrarianAgent,
    private val arbitratorAgent: ArbitratorAgent,
    private val settings: LlmVetoSettings,
    private val meterRegistry: MeterRegistry,
) {
    /** Вердикт по одному бар-кандидату: [allow] false ⇒ вход блокируется. */
    data class Verdict(
        val allow: Boolean,
        val reason: String,
    )

    private val memo = ConcurrentHashMap<String, Verdict>()
    private var uniqueCandidates = 0

    /** Причины блокировки — значения тега `reason` метрики. */
    object Reason {
        const val ALLOWED = "ALLOWED"
        const val SAMPLED_OUT = "SAMPLED_OUT"
        const val ARB_HOLD = "ARB_HOLD"
        const val ARB_SCORE_LOW = "ARB_SCORE_LOW"
        const val CHALLENGE_CRITICAL = "CHALLENGE_CRITICAL"
        const val LLM_FAILURE = "LLM_FAILURE"
        const val LLM_FAILURE_PASSED = "LLM_FAILURE_PASSED"
        const val BUDGET_TIMEOUT = "BUDGET_TIMEOUT"
        const val NO_INDICATORS = "NO_INDICATORS"
        const val ERROR = "ERROR"
    }

    /**
     * Вердикт по детерминированному сигналу. [action] = HOLD ⇒ пропуск без вызова
     * LLM: vetoить нечего.
     */
    suspend fun veto(
        ticker: String,
        action: StrategyAction,
        strength: Double,
        snapshot: MarketSnapshot,
        indicators: IndicatorCalculator.Indicators?,
        cycleId: String,
        barTime: LocalDateTime,
    ): Verdict {
        if (action == StrategyAction.HOLD) return Verdict(allow = true, reason = Reason.ALLOWED)
        val key = "$ticker|$barTime|$action"
        memo[key]?.let {
            meterRegistry.counter("bt_llm_veto_cache_hits_total", Tags.of("ticker", ticker)).increment()
            return it
        }
        val candidate = uniqueCandidates++
        if (settings.sampleEvery > 1 && candidate % settings.sampleEvery != 0) {
            return Verdict(allow = true, reason = Reason.SAMPLED_OUT).also { memo[key] = it }
        }
        meterRegistry.counter("bt_llm_veto_candidates_total", Tags.of("ticker", ticker)).increment()
        val verdict = decide(ticker, action, strength, snapshot, indicators, cycleId)
        record(ticker, verdict)
        memo[key] = verdict
        return verdict
    }

    private suspend fun decide(
        ticker: String,
        action: StrategyAction,
        strength: Double,
        snapshot: MarketSnapshot,
        indicators: IndicatorCalculator.Indicators?,
        cycleId: String,
    ): Verdict {
        val ind = indicators ?: return unknown(Reason.NO_INDICATORS, ticker)
        val tech =
            TechnicalReport(
                trend = ind.trend,
                rsi = ind.rsi,
                atr = ind.atr,
                macd = ind.macdHistogram,
                bbUpper = ind.bbUpper,
                bbLower = ind.bbLower,
                conclusion = ind.conclusion,
                signalStrength = strength,
                reasoning = TECH_REASONING,
            )
        val fund = FundamentalReport(conclusion = "NEUTRAL", signalStrength = 0.0, reasoning = FUND_REASONING)
        val draft =
            StrategyAgent.Draft(
                action = action,
                targetPrice = snapshot.currentPrice,
                signalStrength = strength,
                reasoning = DRAFT_REASONING,
            )
        return try {
            withTimeout(settings.budgetMs) {
                val challenge =
                    contrarianAgent.challenge(
                        draft = draft,
                        tech = tech,
                        fund = fund,
                        snapshot = snapshot,
                        cycleId = cycleId,
                        version = settings.promptVersion,
                        temperature = settings.temperature,
                        cacheNamespace = settings.cacheNamespace,
                        techDelta = null,
                        bypassCache = true,
                    )
                when {
                    !challenge.llmAvailable -> unknown(Reason.LLM_FAILURE, ticker)
                    challenge.riskLevel == RISK_CRITICAL -> Verdict(false, Reason.CHALLENGE_CRITICAL)
                    else -> adjudicate(draft, challenge, tech, fund, snapshot, cycleId, ticker)
                }
            }
        } catch (e: TimeoutCancellationException) {
            unknown(Reason.BUDGET_TIMEOUT, ticker)
        } catch (e: Exception) {
            // Сбой veto не должен ронять прогон: fail-closed (или пропуск) по флагу.
            unknown(Reason.ERROR, ticker)
        }
    }

    private suspend fun adjudicate(
        draft: StrategyAgent.Draft,
        challenge: ContrarianAgent.ChallengeReport,
        tech: TechnicalReport,
        fund: FundamentalReport,
        snapshot: MarketSnapshot,
        cycleId: String,
        ticker: String,
    ): Verdict {
        val final =
            arbitratorAgent.adjudicate(
                draft = draft,
                challenge = challenge,
                tech = tech,
                fund = fund,
                snapshot = snapshot,
                cycleId = cycleId,
                contextPrompt = null,
                // 0.0: детерминированные override'ы арбитра (низкая уверенность
                // draft'а/вердикта) выключены — иначе veto смешивается с порогом входа
                // и гипотеза «умеет ли LLM отсекать убыточные входы» не измеряется.
                adaptiveConfidence = 0.0,
                version = settings.promptVersion,
                bypassCache = true,
                temperature = settings.temperature,
                cacheNamespace = settings.cacheNamespace,
            )
        if (final.action != StrategyAction.HOLD) return scoreVerdict(final.signalStrength, ticker)
        // overrideReason != null ⇒ это не вердикт, а отказ (LLM_UNAVAILABLE /
        // SCHEMA_REJECTED / PARSE_ERROR) — такие решает флаг, а не veto.
        return if (final.overrideReason != null) {
            unknown(Reason.LLM_FAILURE, ticker)
        } else {
            Verdict(allow = false, reason = Reason.ARB_HOLD)
        }
    }

    /**
     * Score-режим: арбитр оценил качество входа числом в `signalStrength`.
     *
     * `minScore == null` (дефолт) ⇒ бинарная семантика, вход пропускается —
     * поведение не меняется. Иначе вход блокируется при `score < minScore`;
     * порог калибруется сеткой, потому что сама LLM на этот вопрос отвечает
     * вырожденно (см. [LlmVetoSettings.minScore]).
     */
    private fun scoreVerdict(
        score: Double,
        ticker: String,
    ): Verdict {
        DistributionSummary
            .builder(SCORE_METRIC)
            .tag("ticker", ticker)
            .register(meterRegistry)
            .record(score)
        val minScore = settings.minScore ?: return Verdict(allow = true, reason = Reason.ALLOWED)
        return if (score < minScore) {
            Verdict(allow = false, reason = Reason.ARB_SCORE_LOW)
        } else {
            Verdict(allow = true, reason = Reason.ALLOWED)
        }
    }

    /** Отказ LLM: блок при `blockOnUnknown` (fail-closed), иначе пропуск кандидата. */
    private fun unknown(
        reason: String,
        @Suppress("UNUSED_PARAMETER") ticker: String,
    ): Verdict =
        if (settings.blockOnUnknown) {
            Verdict(allow = false, reason = reason)
        } else {
            Verdict(allow = true, reason = Reason.LLM_FAILURE_PASSED)
        }

    private fun record(
        ticker: String,
        verdict: Verdict,
    ) {
        val metric =
            if (verdict.allow) "bt_llm_veto_allowed_total" else "bt_llm_veto_blocked_total"
        meterRegistry.counter(metric, Tags.of("ticker", ticker, "reason", verdict.reason)).increment()
    }

    companion object {
        private const val RISK_CRITICAL = "CRITICAL"

        /** Распределение score входа (count/sum/min/max/mean) — диагностика калибровки порога. */
        const val SCORE_METRIC = "bt_llm_veto_score"

        /** Тех-контекст, который видит LLM на veto-пути (детерминированные индикаторы). */
        const val TECH_REASONING = "Deterministic indicators (backtest: technical LLM agent is not used on the veto path)."

        /** Фундаментальный отчёт на veto-пути: данных нет, отчёт нейтрален по построению. */
        const val FUND_REASONING = "No fundamental data is available in backtest; report is neutral by construction."

        /** Обоснование draft'а: он не LLM-сигнал, а сигнал детерминированного ансамбля. */
        const val DRAFT_REASONING = "Deterministic baseline signal (live-strategy ensemble)."
    }
}
