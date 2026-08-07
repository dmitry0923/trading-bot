package com.trading.bot.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * Конфигурация RAG-анализа ошибок LLM-агентов, prefix = "rag".
 *
 * Корпус строится из сохранённых LLM-трейсов (S3/MinIO): индексируются
 * промпты + ответы локальным TF-IDF-эмбеддингом (без внешнего vector DB),
 * по запросу извлекаются похожие трейсы и (если включено) LLM строит
 * разбор первопричины ошибки.
 *
 * @property enabled включает RAG-анализ (поиск по корпусу трейсов)
 * @property corpusLimit сколько последних трейсов индексировать
 * @property refreshIntervalMs период переиндексации корпуса
 * @property maxResults максимум релевантных трейсов для анализа
 * @property similarityThreshold порог сходства для включения в результаты
 * @property llmEnabled строить разбор через LLM (иначе rule-based сводка)
 */
@Component
@ConfigurationProperties(prefix = "rag")
class RagConfig {
    var enabled: Boolean = false
    var corpusLimit: Int = 500
    var refreshIntervalMs: Long = 600_000
    var maxResults: Int = 5
    var similarityThreshold: Double = 0.02
    var llmEnabled: Boolean = true
}
