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
 * по лимитам за минуту / за цикл / за день + дневная стоимость).
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

    /** Результат резервирования бюджета: [allowed] и причина отказа ([reason] при запрете). */
    data class BudgetDecision(
        val allowed: Boolean,
        val reason: String?,
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
        val costEstimate =
            estimatedTokens / MILLION.toDouble() * llmConfig.tokenCostRubPerMillion
        val args =
            listOf(
                estimatedTokens.toString(),
                llmConfig.maxTokensPerMinute.toString(),
                llmConfig.maxTokensPerCycle.toString(),
                llmConfig.maxTokensPerDay.toString(),
                llmConfig.maxDailyCostRub.toString(),
                "%.6f".format(Locale.ROOT, costEstimate),
                "120",
                "600", // TTL ключа цикла: не раньше завершения самого длинного цикла
            )
        return try {
            @Suppress("UNCHECKED_CAST")
            val result = redisTemplate.execute(RESERVE_SCRIPT, keys, *args.toTypedArray()) as? List<Any?>
            val allowed = result?.firstOrNull()?.toString() == "1"
            if (allowed) {
                meterRegistry.counter("llm.budget.reserve").increment()
                BudgetDecision(allowed = true, reason = null)
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

    companion object {
        private const val MILLION = 1_000_000.0

        /**
         * Атомарное резервирование по всем лимитам сразу с полным откатом при
         * превышении. KEYS: 1=минута, 2=цикл, 3=день, 4=стоимость дня.
         * ARGV: estimate, maxMinute, maxCycle, maxDay, maxCost, costEstimate,
         * minuteTtl, cycleTtl.
         */
        private val RESERVE_SCRIPT: RedisScript<List<*>> =
            DefaultRedisScript(
                // language=lua
                """
                local estimate = tonumber(ARGV[1])
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
                redis.call('EXPIRE', KEYS[1], tonumber(ARGV[7]), 'NX')
                redis.call('EXPIRE', KEYS[2], tonumber(ARGV[8]), 'NX')
                return {'1', ''}
                """.trimIndent(),
                List::class.java,
            )
    }
}
