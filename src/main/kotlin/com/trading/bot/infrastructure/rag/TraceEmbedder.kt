package com.trading.bot.infrastructure.rag

import org.springframework.stereotype.Component

/**
 * Локальный TF-IDF эмбеддер текста для RAG-поиска по LLM-трейсам.
 *
 * Не требует внешнего vector DB/embedding API: токенизация + стоп-слова,
 * взвешивание tf-idf, косинусная близость. Достаточно для релевантного
 * поиска по промптам/ответам (общие шаблоны агентов дают большой словарный
 * перекрытие), при этом работает офлайн и не увеличивает бюджет LLM.
 */
@Component
class TraceEmbedder {
    private val tokenRegex = Regex("""[\p{L}\p{N}]+""")

    fun tokenize(text: String): List<String> =
        tokenRegex
            .findAll(text.lowercase())
            .map { it.value }
            .filter { it.length > 1 && it !in STOP_WORDS }
            .toList()

    /** Частотная карта токенов. */
    fun termFrequencies(tokens: List<String>): Map<String, Int> = tokens.groupingBy { it }.eachCount()

    /**
     * Строит IDF-словарь по набору документов: idf = log((1+N)/(1+df)) + 1.
     */
    fun buildIdf(docs: List<Map<String, Int>>): Map<String, Double> {
        val df = HashMap<String, Int>()
        for (doc in docs) {
            for (term in doc.keys) {
                df[term] = (df[term] ?: 0) + 1
            }
        }
        val n = docs.size
        return df.mapValues { (_, docFreq) -> Math.log((1.0 + n) / (1.0 + docFreq)) + 1.0 }
    }

    /**
     * Взвешенный нормализованный tf-idf вектор.
     */
    fun vector(
        terms: Map<String, Int>,
        idf: Map<String, Double>,
    ): Map<String, Double> {
        val raw =
            terms.mapValues { (term, tf) ->
                (idf[term] ?: 1.0) * (1.0 + Math.log(tf.toDouble()))
            }
        val norm = Math.sqrt(raw.values.sumOf { it * it })
        if (norm == 0.0) return emptyMap()
        return raw.mapValues { (_, w) -> w / norm }
    }

    /** Косинусная близость нормализованных векторов (0..1). */
    fun cosine(
        a: Map<String, Double>,
        b: Map<String, Double>,
    ): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val smaller = if (a.size <= b.size) a else b
        val larger = if (a.size <= b.size) b else a
        var dot = 0.0
        for ((term, w) in smaller) {
            val other = larger[term] ?: continue
            dot += w * other
        }
        return dot
    }

    companion object {
        private val STOP_WORDS =
            setOf(
                // English
                "the",
                "a",
                "an",
                "and",
                "or",
                "of",
                "to",
                "in",
                "for",
                "on",
                "with",
                "is",
                "are",
                "was",
                "were",
                "be",
                "been",
                "this",
                "that",
                "these",
                "those",
                "from",
                "by",
                "at",
                "as",
                "not",
                "no",
                "it",
                "its",
                "you",
                "your",
                "will",
                "would",
                "should",
                "can",
                "could",
                "may",
                "might",
                "must",
                "do",
                "does",
                "did",
                "have",
                "has",
                "had",
                "if",
                "then",
                "than",
                "so",
                "but",
                "etc",
                "also",
                "only",
                "more",
                "most",
                "other",
                "some",
                "such",
                "all",
                "any",
                "each",
                "both",
                "which",
                "who",
                "whom",
                "whose",
                "what",
                "where",
                "when",
                "why",
                "how",
                // Russian
                "и",
                "в",
                "во",
                "на",
                "с",
                "со",
                "по",
                "для",
                "это",
                "эта",
                "этот",
                "что",
                "как",
                "не",
                "ни",
                "от",
                "до",
                "при",
                "или",
                "если",
                "то",
                "все",
                "вся",
                "быть",
                "будет",
                "будут",
                "которые",
                "который",
                "которая",
                "которое",
                "его",
                "ее",
                "её",
                "их",
                "нам",
                "вам",
                "нас",
                "вас",
                "уже",
                "еще",
                "ещё",
                "даже",
                "так",
                "же",
                "бы",
                "ли",
                "только",
                "также",
                "через",
                "между",
                "после",
                "перед",
                "про",
                "без",
                "из",
                "за",
                "над",
                "под",
                "об",
                "обо",
                "к",
                "ко",
                "у",
                "о",
            )
    }
}
