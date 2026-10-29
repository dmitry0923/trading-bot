package com.trading.bot.infrastructure.llm

import com.trading.bot.config.LlmConfig
import com.trading.bot.infrastructure.tracing.TraceContext
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Бюджет LLM-вызовов (P0-аудит, research/llm-signal-source).
 *
 * Защита от «спирали токенов»: пока LLM участвует в выборе сигнала, параллельные
 * циклы могут генерировать неограниченное количество запросов. Бюджет резервирует
 * расход под каждый вызов ДО обращения к провайдеру — атомарно в Redis (Lua INCRBY
 * по лимитам за минуту / за цикл / за день + дневная стоимость). Резерв берётся
 * ровно один раз — тем участником, который реально вызывает провайдера (после
 * single-flight); ожидающие чужой ответ не оплачивают вызов.
 *
 * Превышение любого лимита (или недоступность Redis) → fail-closed: вызов отклонён,
 * резерв откатан, вызывающий код получает NEUTRAL fallback с причиной.
 *
 * Учёт — резервно-оценочный (review/P1): вызывающий код резервирует оценку промпта
 * (длина / 4 токена) ПЛЮС максимальный размер ответа [LlmConfig.maxTokens] — бюджет
 * покрывает полный потенциальный расход вызова, а не только запрос. Точные токены
 * пишутся в трейсинг.
 */
@Component
class LlmBudgetService(
    private val redisTemplate: StringRedisTemplate,
    private val meterRegistry: MeterRegistry,
    private val llmConfig: LlmConfig,
    private val clock: Clock = Clock.system(ZoneId.of("Europe/Moscow")),
) {
    private val logger = KotlinLogging.logger {}

    init {
        // Дефолты max-tokens=4096 и max-tokens-per-minute=4000 несовместимы: резерв одного
        // вызова превышает потолок минуты, и LLM-путь деградирует в fallback (скан-7/8 P1).
        if (llmConfig.budgetEnabled && llmConfig.maxTokens >= llmConfig.maxTokensPerMinute) {
            logger.warn {
                "LLM budget: llm.max-tokens=${llmConfig.maxTokens} >= llm.max-tokens-per-minute=" +
                    "${llmConfig.maxTokensPerMinute} - резерв клампится, реальный throughput = 1 вызов/мин; " +
                    "уменьшите llm.max-tokens"
            }
        }
    }

    /** Результат резервирования бюджета: [allowed] и причина отказа ([reason] при запрете). */
    data class BudgetDecision(
        val allowed: Boolean,
        val reason: String?,
        /** Что именно зарезервировано — нужно для возврата резерва при сбое вызова. */
        val reservation: Reservation? = null,
    )

    /**
     * Резерв токенов под один вызов LLM.
     *
     * Скан-17 P2: без возврата резерва каждый сетевой сбой/таймаут навсегда съедал
     * ~4000 токенов дневного лимита, и LLM-путь умирал до полуночи при нулевом
     * фактическом расходе.
     */
    data class Reservation(
        val tokens: Int,
        val costEstimate: Double,
        val keys: List<String>,
    )

    /**
     * Оценочный расход промпта (система + пользовательская часть):
     * ~4 символа на токен.
     */
    fun estimateTokens(
        systemPrompt: String,
        userPrompt: String?,
    ): Int = ((systemPrompt.length + (userPrompt?.length ?: 0)) / 4).coerceAtLeast(0)

    /**
     * Резервирует [estimatedTokens] под вызов [agent]. [cacheKey] — семантический ключ
     * вызова (используется только в логе). Возвращает [BudgetDecision].
     */
    fun reserve(
        agent: String,
        cacheKey: String,
        estimatedTokens: Int,
    ): BudgetDecision {
        if (!llmConfig.budgetEnabled || estimatedTokens <= 0) {
            return BudgetDecision(allowed = true, reason = null)
        }
        val now = clock.instant()
        val epochMinute = now.epochSecond / 60
        val cycleId = TraceContext.cycleId() ?: "outside-cycle"
        val day = LocalDate.now(clock).format(DateTimeFormatter.BASIC_ISO_DATE)
        val keys =
            listOf(
                "llm:budget:minute:$epochMinute",
                "llm:budget:cycle:$cycleId",
                "llm:budget:day:$day",
                "llm:budget:cost:$day",
            )
        // Резерв не может превышать потолок минуты: иначе при `max-tokens` >= `max-tokens-per-minute`
        // (дефолт 4096 против 4000) первый же вызов каждой минуты отклонялся бы и весь
        // LLM-путь молча деградировал в fallback (скан-7 P1).
        val reserveTokens = estimatedTokens.coerceAtMost(llmConfig.maxTokensPerMinute.coerceAtLeast(1))
        if (reserveTokens < estimatedTokens) {
            meterRegistry.counter("llm.budget.clamped", Tags.of("agent", agent)).increment()
            logger.warn {
                "LLM budget: reserve $estimatedTokens > max-tokens-per-minute " +
                    "${llmConfig.maxTokensPerMinute} - clamped (проверьте llm.max-tokens)"
            }
        }
        val costEstimate =
            reserveTokens / MILLION.toDouble() * llmConfig.tokenCostRubPerMillion
        val args =
            listOf(
                reserveTokens.toString(),
                llmConfig.maxTokensPerMinute.toString(),
                llmConfig.maxTokensPerCycle.toString(),
                llmConfig.maxTokensPerDay.toString(),
                llmConfig.maxDailyCostRub.toString(),
                "%.6f".format(Locale.ROOT, costEstimate),
                "120",
                "600", // TTL ключа цикла: не раньше завершения самого длинного цикла
                "172800", // TTL дневных ключей (день + стоимость): 2 суток
            )
        return try {
            @Suppress("UNCHECKED_CAST")
            val result = redisTemplate.execute(RESERVE_SCRIPT, keys, *args.toTypedArray()) as? List<Any?>
            val allowed = result?.firstOrNull()?.toString() == "1"
            if (allowed) {
                meterRegistry.counter("llm.budget.reserve").increment()
                BudgetDecision(
                    allowed = true,
                    reason = null,
                    // Ключи 1..3 — токены, ключ 4 — стоимость дня.
                    reservation = Reservation(reserveTokens, costEstimate, keys),
                )
            } else {
                val reason = result?.getOrNull(1)?.toString() ?: "TOKEN_BUDGET_EXCEEDED"
                meterRegistry.counter("llm.budget.reject", Tags.of("agent", agent, "reason", reason)).increment()
                logger.warn { "LLM budget rejected $agent/$cacheKey: $reason" }
                BudgetDecision(allowed = false, reason = reason)
            }
        } catch (e: Exception) {
            logger.warn(e) { "LLM budget unavailable for $agent/$cacheKey" }
            meterRegistry.counter("llm.budget.reject", Tags.of("agent", agent, "reason", "BUDGET_UNAVAILABLE")).increment()
            BudgetDecision(allowed = false, reason = "BUDGET_UNAVAILABLE")
        }
    }

    /**
     * Возвращает зарезервированные токены после неуспешного/отменённого вызова.
     *
     * Без этого сетевой сбой или таймаут навсегда съедал резерв (~4000 токенов) из
     * минутного/циклового/дневного лимита — 50 отказов подряд исчерпывали день
     * при нулевом фактическом расходе (скан-17 P2). Идемпотентно-безопасно: DECRBY
     * с клампом на 0, стоимость — `INCRBYFLOAT -cost`.
     */
    fun release(reservation: Reservation?) {
        if (reservation == null || !llmConfig.budgetEnabled) return
        val tokensArg = reservation.tokens.toString()
        val costArg = "%.6f".format(Locale.ROOT, reservation.costEstimate)
        try {
            redisTemplate.execute(RELEASE_SCRIPT, reservation.keys, tokensArg, costArg)
            meterRegistry.counter("llm.budget.released").increment()
        } catch (e: Exception) {
            logger.warn(e) { "LLM budget release failed (${reservation.tokens} tokens)" }
        }
    }

    companion object {
        private const val MILLION = 1_000_000.0

        /**
         * Возврат резерва: DECRBY по трём токенным ключам (кламп на 0) и
         * `INCRBYFLOAT -cost` по ключу стоимости дня.
         * KEYS: 1=минута, 2=цикл, 3=день, 4=стоимость дня. ARGV: tokens, cost.
         */
        private val RELEASE_SCRIPT: RedisScript<List<*>> =
            DefaultRedisScript(
                // language=lua
                """
                local tokens = tonumber(ARGV[1])
                local cost = tonumber(ARGV[2])
                for i = 1, 3 do
                    local v = tonumber(redis.call('GET', KEYS[i]) or '0') or 0
                    local nv = v - tokens
                    if nv < 0 then nv = 0 end
                    redis.call('SET', KEYS[i], string.format('%.0f', nv), 'KEEPTTL')
                end
                local c = tonumber(redis.call('GET', KEYS[4]) or '0') or 0
                local nc = c - cost
                if nc < 0 then nc = 0 end
                redis.call('SET', KEYS[4], string.format('%.6f', nc), 'KEEPTTL')
                return {1}
                """.trimIndent(),
                List::class.java,
            )

        /**
         * Атомарное резервирование по всем лимитам сразу с полным откатом при
         * превышении. KEYS: 1=минута, 2=цикл, 3=день, 4=стоимость дня.
         * ARGV: estimate, maxMinute, maxCycle, maxDay, maxCost, costEstimate,
         * minuteTtl, cycleTtl, dayTtl.
         *
         * TTL ставится простым `EXPIRE` на ВСЕ ключи: `EXPIRE ... NX` поддерживается
         * только с Redis 7, а на Redis 6 скрипт падал → `BUDGET_UNAVAILABLE` →
         * fail-closed отказ КАЖДОГО LLM-вызова. Без TTL на дневных ключах они бы
         * копились вечно (утечка).
         */
        private val RESERVE_SCRIPT: RedisScript<List<*>> =
            DefaultRedisScript(
                // language=lua
                """
                local estimate = tonumber(ARGV[1])
                -- TTL выставляем ДО любых INCRBY: ветки отказа делают DECRBY и
                -- оставляли бы ключи со значением 0 и без TTL навсегда (скан-1 P2).
                redis.call('EXPIRE', KEYS[1], tonumber(ARGV[7]))
                redis.call('EXPIRE', KEYS[2], tonumber(ARGV[8]))
                redis.call('EXPIRE', KEYS[3], tonumber(ARGV[9]))
                redis.call('EXPIRE', KEYS[4], tonumber(ARGV[9]))
                local m = redis.call('INCRBY', KEYS[1], estimate)
                if m > tonumber(ARGV[2]) then
                    redis.call('DECRBY', KEYS[1], estimate)
                    return {'0', 'TOKEN_BUDGET_EXCEEDED:MINUTE'}
                end
                local c = redis.call('INCRBY', KEYS[2], estimate)
                if c > tonumber(ARGV[3]) then
                    redis.call('DECRBY', KEYS[1], estimate)
                    redis.call('DECRBY', KEYS[2], estimate)
                    return {'0', 'TOKEN_BUDGET_EXCEEDED:CYCLE'}
                end
                local d = redis.call('INCRBY', KEYS[3], estimate)
                if d > tonumber(ARGV[4]) then
                    redis.call('DECRBY', KEYS[1], estimate)
                    redis.call('DECRBY', KEYS[2], estimate)
                    redis.call('DECRBY', KEYS[3], estimate)
                    return {'0', 'TOKEN_BUDGET_EXCEEDED:DAY'}
                end
                local cost = tonumber(redis.call('INCRBYFLOAT', KEYS[4], ARGV[6]))
                if cost > tonumber(ARGV[5]) then
                    redis.call('DECRBY', KEYS[1], estimate)
                    redis.call('DECRBY', KEYS[2], estimate)
                    redis.call('DECRBY', KEYS[3], estimate)
                    redis.call('INCRBYFLOAT', KEYS[4], '-' .. ARGV[6])
                    return {'0', 'TOKEN_BUDGET_EXCEEDED:COST'}
                end
                return {'1', ''}
                """.trimIndent(),
                List::class.java,
            )
    }
}
