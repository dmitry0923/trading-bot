package com.trading.bot.infrastructure.rag

import com.trading.bot.infrastructure.tracing.LlmTrace
import com.trading.bot.infrastructure.tracing.TraceStorage
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Похожий на документ трейс с вектором TF-IDF.
 */
data class ScoredTrace(
    val key: String,
    val trace: LlmTrace,
    val score: Double,
)

/**
 * In-memory корпус LLM-трейсов для RAG.
 *
 * При [refresh] читает последние трейсы из [TraceStorage] (S3/MinIO),
 * индексирует их локальным TF-IDF и хранит снимок в immutable-структуре
 * (@Volatile) — поиск не блокирует переиндексацию и наоборот.
 *
 * Снимок содержит IDF по всей коллекции и нормализованные векторы; [search]
 * возвращает топ-K похожих трейсов по косинусной близости.
 */
@Component
class TraceCorpusIndex(
    private val storage: TraceStorage,
    private val embedder: TraceEmbedder,
) {
    private val logger = KotlinLogging.logger {}

    @Volatile
    private var snapshot: Snapshot = Snapshot(emptyList(), emptyMap(), null)

    @Volatile
    private var lastRefreshValue: Instant? = null

    /** Время последней переиндексации корпуса (null — ещё не индексировался). */
    val lastRefresh: Instant?
        get() = lastRefreshValue

    val size: Int
        get() = snapshot.entries.size

    /**
     * Переиндексирует корпус из хранилища (последние [limit] объектов).
     * Чтение — параллельно с ограничением [READ_CONCURRENCY].
     */
    suspend fun refresh(limit: Int): Int {
        val keys = storage.list(limit)
        if (keys.isEmpty()) {
            snapshot = Snapshot(emptyList(), emptyMap(), null)
            lastRefreshValue = Instant.now()
            return 0
        }
        val semaphore = Semaphore(READ_CONCURRENCY)
        val traces: List<Pair<String, LlmTrace>> =
            withContext(Dispatchers.IO) {
                coroutineScope {
                    keys
                        .map { key ->
                            async {
                                semaphore.withPermit { storage.read(key)?.let { key to it } }
                            }
                        }.awaitAll()
                        .filterNotNull()
                }
            }
        val termFreqs = traces.map { (_, t) -> embedder.termFrequencies(embedder.tokenize(textOf(t))) }
        val idf = embedder.buildIdf(termFreqs)
        val entries =
            traces.mapIndexed { i, (key, trace) ->
                Entry(key = key, trace = trace, vector = embedder.vector(termFreqs[i], idf))
            }
        snapshot = Snapshot(entries = entries, idf = idf, lastRefresh = Instant.now())
        lastRefreshValue = snapshot.lastRefresh
        logger.info { "RAG corpus rebuilt: ${entries.size} traces indexed" }
        return entries.size
    }

    /**
     * Ищет похожие трейсы по тексту запроса.
     *
     * @param query текст (ошибка/симптом/сигнатура)
     * @param k максимум результатов
     * @param threshold минимальная косинусная близость
     */
    fun search(
        query: String,
        k: Int,
        threshold: Double,
    ): List<ScoredTrace> {
        val snap = snapshot
        if (snap.entries.isEmpty()) return emptyList()
        val qVector = embedder.vector(embedder.termFrequencies(embedder.tokenize(query)), snap.idf)
        if (qVector.isEmpty()) return emptyList()
        return snap.entries
            .asSequence()
            .map { e -> ScoredTrace(key = e.key, trace = e.trace, score = embedder.cosine(qVector, e.vector)) }
            .filter { it.score >= threshold }
            .sortedByDescending { it.score }
            .take(k)
            .toList()
    }

    /**
     * Ищет трейсы, похожие на данный (использует его промпты/ответ как запрос).
     */
    fun searchSimilar(
        trace: LlmTrace,
        k: Int,
        threshold: Double,
    ): List<ScoredTrace> = search(textOf(trace), k, threshold)

    /** Текст документа для эмбеддинга трейса. */
    private fun textOf(trace: LlmTrace): String =
        listOfNotNull(
            trace.agent,
            trace.ticker,
            trace.provider,
            trace.errorMessage,
            trace.systemPrompt,
            trace.userPrompt,
            trace.responseContent,
        ).joinToString(" ")

    private data class Entry(
        val key: String,
        val trace: LlmTrace,
        val vector: Map<String, Double>,
    )

    private data class Snapshot(
        val entries: List<Entry>,
        val idf: Map<String, Double>,
        val lastRefresh: Instant?,
    )

    companion object {
        private const val READ_CONCURRENCY = 8
    }
}
