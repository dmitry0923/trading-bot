package com.trading.bot.service

import com.trading.bot.config.RagConfig
import com.trading.bot.infrastructure.llm.LlmResponse
import com.trading.bot.infrastructure.llm.PromptRegistry
import com.trading.bot.infrastructure.llm.ResilientLlmClient
import com.trading.bot.infrastructure.rag.ScoredTrace
import com.trading.bot.infrastructure.rag.TraceCorpusIndex
import com.trading.bot.infrastructure.tracing.TraceStorage
import com.trading.bot.model.RagAnalysis
import com.trading.bot.model.RagRetrievedTrace
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.util.concurrent.TimeUnit

/**
 * RAG-анализ ошибок LLM-агентов по сохранённым трейсам (S3/MinIO).
 *
 * Корпус ([TraceCorpusIndex]) индексирует последние трейсы локальным TF-IDF;
 * по запросу (ошибка/симптом) извлекаются похожие трейсы, и если включено —
 * LLM строит разбор первопричины с использованием извлечённых трейсов как
 * контекста (retrieval-augmented). При недоступности LLM — rule-based сводка.
 *
 * Весь пайплайн best-effort: падение хранилища/LLM не влияет на торговлю.
 */
@Service
class RagErrorAnalyzer(
    private val ragConfig: RagConfig,
    private val corpus: TraceCorpusIndex,
    private val storage: TraceStorage,
    private val llmClient: ResilientLlmClient,
    private val promptRegistry: PromptRegistry,
    private val meterRegistry: MeterRegistry,
) {
    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @EventListener(ApplicationReadyEvent::class)
    fun init() {
        if (ragConfig.enabled) {
            scope.launch {
                logger.info { "RAG corpus initial refresh (limit=${ragConfig.corpusLimit})" }
                refresh()
            }
        }
    }

    /**
     * Переиндексация корпуса по расписанию (не запускается при выключенном RAG).
     */
    @Scheduled(fixedDelayString = "#{@ragConfig.refreshIntervalMs}")
    fun scheduledRefresh() {
        if (ragConfig.enabled) {
            scope.launch {
                refresh()
            }
        }
    }

    /**
     * Переиндексирует корпус из хранилища.
     *
     * @return количество проиндексированных трейсов
     */
    suspend fun refresh(): Int {
        val size =
            try {
                val n = corpus.refresh(ragConfig.corpusLimit)
                meterRegistry.counter("rag.refresh", Tags.of("status", "ok")).increment()
                meterRegistry.gauge("rag.index_size", corpus) { corpus.size.toDouble() }
                n
            } catch (e: Exception) {
                logger.warn(e) { "RAG corpus refresh failed" }
                meterRegistry.counter("rag.refresh", Tags.of("status", "error")).increment()
                corpus.size
            }
        return size
    }

    /**
     * Анализирует ошибку: извлекает похожие трейсы и строит разбор.
     *
     * @param query текст ошибки/симптома
     * @param ticker опциональный тикер для уточнения
     * @param k количество релевантных трейсов (по умолчанию из конфига)
     */
    suspend fun analyze(
        query: String,
        ticker: String? = null,
        k: Int? = null,
    ): RagAnalysis {
        val start = System.nanoTime()
        val kk = (k ?: ragConfig.maxResults).coerceIn(1, 20)
        if (!ragConfig.enabled) {
            return RagAnalysis(query = query, mode = "DISABLED", report = "RAG disabled (rag.enabled=false)", retrievedTraces = emptyList())
        }
        meterRegistry.counter("rag.search").increment()
        val results = corpus.search(query, kk, ragConfig.similarityThreshold)
        val analysis =
            if (results.isEmpty()) {
                RagAnalysis(
                    query = query,
                    mode = "RULE_BASED",
                    report =
                        "No similar traces found in corpus (${corpus.size} indexed). " +
                            "Try refreshing the corpus (POST /api/v1/rag/refresh) or check trace-storage.",
                    retrievedTraces = emptyList(),
                )
            } else {
                val report = buildReport(query, ticker, results)
                RagAnalysis(
                    query = query,
                    mode = report.first,
                    report = report.second,
                    retrievedTraces = results.map { toRetrieved(it) },
                )
            }
        meterRegistry
            .timer("rag.latency")
            .record(System.nanoTime() - start, TimeUnit.NANOSECONDS)
        return analysis
    }

    /**
     * Анализирует конкретный трейс: загружает его из хранилища, находит похожие
     * в корпусе и строит разбор.
     */
    suspend fun analyzeTrace(
        storageKey: String,
        k: Int? = null,
    ): RagAnalysis {
        val trace =
            storage.read(storageKey)
                ?: return RagAnalysis(
                    query = "trace:$storageKey",
                    mode = "RULE_BASED",
                    report = "Trace not found: $storageKey",
                    retrievedTraces = emptyList(),
                )
        val kk = (k ?: ragConfig.maxResults).coerceIn(1, 20)
        val results =
            corpus
                .searchSimilar(trace, kk + 1, ragConfig.similarityThreshold)
                .filterNot { it.key == storageKey }
                .take(kk)
        val query = "agent=${trace.agent} ticker=${trace.ticker} error=${trace.errorMessage ?: "none"}"
        val analysis =
            if (results.isEmpty()) {
                RagAnalysis(
                    query = query,
                    mode = "RULE_BASED",
                    report = "No similar traces in corpus (indexed=${corpus.size}).",
                    retrievedTraces = listOf(toRetrieved(ScoredTrace(storageKey, trace, 1.0))),
                )
            } else {
                val report = buildReport(query, trace.ticker, results)
                RagAnalysis(query = query, mode = report.first, report = report.second, retrievedTraces = results.map { toRetrieved(it) })
            }
        return analysis
    }

    fun status(): Map<String, Any?> =
        mapOf(
            "enabled" to ragConfig.enabled,
            "indexSize" to corpus.size,
            "lastRefresh" to corpus.lastRefresh,
            "llmEnabled" to ragConfig.llmEnabled,
            "corpusLimit" to ragConfig.corpusLimit,
        )

    /**
     * Строит отчёт: LLM (RAG) или rule-based сводку.
     *
     * @return Pair(mode, text) — mode может быть "RULE_BASED", если LLM недоступен
     */
    private suspend fun buildReport(
        query: String,
        ticker: String?,
        results: List<ScoredTrace>,
    ): Pair<String, String> {
        if (!ragConfig.llmEnabled) {
            return "RULE_BASED" to ruleBasedReport(results)
        }
        val response = llmReport(query, ticker, results)
        return when {
            response.isFallback || response.content.isBlank() -> {
                meterRegistry.counter("rag.llm.error").increment()
                "RULE_BASED" to ruleBasedReport(results) + "\n\n[LLM unavailable, fallback to rule-based]"
            }

            else -> {
                "LLM" to response.content
            }
        }
    }

    private suspend fun llmReport(
        query: String,
        ticker: String?,
        results: List<ScoredTrace>,
    ): LlmResponse {
        val prompt = promptRegistry.getTemplate(PROMPT_NAME)
        val tracesText =
            results
                .mapIndexed { i, r ->
                    val t = r.trace
                    """
                    #${i + 1} [score=${"%.3f".format(r.score)}]
                    key: ${r.key}
                    agent: ${t.agent}
                    ticker: ${t.ticker}
                    provider/model: ${t.provider}/${t.model}
                    fallback: ${t.isFallback}
                    fromCache: ${t.fromCache}
                    latencyMs: ${t.latencyMs}
                    errorMessage: ${t.errorMessage ?: "none"}
                    --- system prompt ---
                    ${truncate(t.systemPrompt)}
                    --- user prompt ---
                    ${truncate(t.userPrompt)}
                    --- response ---
                    ${truncate(t.responseContent)}
                    """.trimIndent()
                }.joinToString("\n\n")
        return llmClient.complete(
            agent = "rag-analyzer",
            ticker = ticker ?: "RAG",
            prompt = prompt,
            variables = mapOf("query" to query, "traces" to tracesText),
            fingerprint = null,
            temperature = 0.2,
        )
    }

    private fun ruleBasedReport(results: List<ScoredTrace>): String {
        val byAgent = results.groupBy { it.trace.agent }
        val topAgent = byAgent.maxByOrNull { it.value.size }?.key ?: "unknown"
        val fallbackCount = results.count { it.trace.isFallback }
        val cacheCount = results.count { it.trace.fromCache }
        val errors = results.mapNotNull { it.trace.errorMessage }.distinct().take(5)
        val avgLatency = results.map { it.trace.latencyMs }.average().toLong()
        val sb = StringBuilder()
        sb.appendLine(
            "Rule-based RAG summary: ${results.size} similar traces (avg score ${"%.2f".format(results.map { it.score }.average())}).",
        )
        sb.appendLine(
            "- Top agent: $topAgent; fallback=$fallbackCount/${results.size}; fromCache=$cacheCount/${results.size}; avg latency=${avgLatency}ms.",
        )
        if (errors.isNotEmpty()) sb.appendLine("- Distinct error messages: ${errors.joinToString(" | ")}")
        sb.appendLine(
            "Recommendations: inspect the top similar traces' prompts (storage_key above); " +
                "if fallback/cache dominant check LLM provider + semantic cache; " +
                "if latency high check rate-limiter/circuit-breaker config.",
        )
        return sb.toString()
    }

    private fun toRetrieved(scored: ScoredTrace): RagRetrievedTrace =
        RagRetrievedTrace(
            key = scored.key,
            agent = scored.trace.agent,
            ticker = scored.trace.ticker,
            provider = scored.trace.provider,
            model = scored.trace.model,
            isFallback = scored.trace.isFallback,
            fromCache = scored.trace.fromCache,
            errorMessage = scored.trace.errorMessage,
            score = scored.score,
            responsePreview = truncate(scored.trace.responseContent, 400),
        )

    private fun truncate(
        text: String,
        maxLen: Int = 1500,
    ): String = if (text.length <= maxLen) text else text.take(maxLen) + "...[truncated]"

    companion object {
        private const val PROMPT_NAME = "rag-analyzer"
    }
}
