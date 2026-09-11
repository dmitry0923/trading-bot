package com.trading.bot.agent

import com.trading.bot.infrastructure.llm.PromptRegistry
import com.trading.bot.infrastructure.llm.ResilientLlmClient
import com.trading.bot.infrastructure.llm.SemanticCache
import com.trading.bot.infrastructure.news.IssuerDataProvider
import com.trading.bot.model.dto.FundamentalReport
import com.trading.bot.model.dto.NewsItem
import com.trading.bot.model.entity.AgentLog
import com.trading.bot.repository.AgentLogRepository
import com.trading.bot.service.MacroContextService
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.format.DateTimeFormatter

/**
 * Агент фундаментального анализа (Agent-2).
 *
 * - Зависит от макро-контекста (ставка ЦБ, нефть Brent, курс USD/RUB)
 * - При наличии [IssuerDataProvider] получает новости по конкретному эмитенту
 *   и добавляет их в промпт (переменная `issuerNews`). Отсутствие новостей /
 *   недоступность источника → анализ идёт только по макро (+ NEUTRAL-база)
 * - Кэширует результат по макро-отпечатку и стабильным полям новостей
 *   (количество, дата последней публикации) — SemanticCache
 * - При недоступности LLM возвращает NEUTRAL c нулевой уверенностью
 * - Пишет лог в AgentLogRepository и метрики agent.fundamental.decision
 */
@Component
class FundamentalAnalysisAgent(
    private val llmClient: ResilientLlmClient,
    private val promptRegistry: PromptRegistry,
    private val macroContextService: MacroContextService,
    private val semanticCache: SemanticCache,
    private val agentLogRepository: AgentLogRepository,
    private val meterRegistry: MeterRegistry,
    private val objectMapper: ObjectMapper,
    private val issuerDataProvider: IssuerDataProvider? = null,
) {
    private val logger = KotlinLogging.logger {}
    private val newsDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /**
     * Фундаментальный анализ тикера на основе макро-контекста и новостей
     * по эмитенту (если источник настроен).
     *
     * @param ticker тикер инструмента
     * @param cycleId идентификатор торгового цикла
     * @param version версия LLM-шаблона промпта
     * @param temperature температура генерации (live-путь 0.1, бэктест — 0.0)
     * @param cacheNamespace изолирует semantic cache (бэктест: "backtest")
     * @return отчёт с заключением, уверенностью и обоснованием
     */
    suspend fun analyze(
        ticker: String,
        cycleId: String,
        version: String = PromptRegistry.DEFAULT_VERSION,
        temperature: Double = 0.1,
        cacheNamespace: String? = null,
    ): FundamentalReport {
        val start = System.currentTimeMillis()
        val macro = macroContextService.fetch()
        val news = issuerDataProvider?.newsFor(ticker, 24) ?: emptyList()

        val variables =
            mapOf(
                "ticker" to ticker,
                "cbrRate" to macro.cbrRate.toPlainString(),
                "brentPrice" to macro.brentPrice.toPlainString(),
                "usdRub" to macro.usdRub.toPlainString(),
                "issuerNews" to renderNews(news),
            )

        // Фундаментальный fingerprint: макро-фон + СТАБИЛЬНЫЕ поля новостей
        // (количество, час публикации последней). Заголовки/содержимое НЕ входят:
        // новая новость в том же часе не должна аннулировать кэш на каждый байт.
        val fingerprint =
            semanticCache.genericFingerprint(
                macro.cbrRate.toPlainString(),
                macro.brentPrice.toPlainString(),
                macro.usdRub.toPlainString(),
                news.size,
                news.maxOfOrNull { it.publishedAt?.epochSecond ?: 0L }?.coerceAtLeast(0L)?.div(3600L),
            )

        val prompt = promptRegistry.getTemplate("fundamental-analysis", version)
        val resp =
            llmClient.complete(
                agent = "fundamental",
                ticker = ticker,
                prompt = prompt,
                variables = variables,
                fingerprint = fingerprint,
                temperature = temperature,
                cacheNamespace = cacheNamespace,
            )

        val report =
            if (resp.isFallback) {
                logger.info { "LLM unavailable for fundamental analysis of $ticker" }
                FundamentalReport(conclusion = "NEUTRAL", signalStrength = 0.0, reasoning = "LLM unavailable")
            } else {
                try {
                    val j = objectMapper.readTree(resp.content)
                    FundamentalReport(
                        conclusion =
                            j.path("conclusion").asString("NEUTRAL").uppercase().let {
                                if (it in setOf("BULLISH", "BEARISH", "NEUTRAL")) it else "NEUTRAL"
                            },
                        signalStrength = j.path("signalStrength").asDouble(0.0).coerceIn(0.0, 1.0),
                        reasoning = j.path("reasoning").asString(""),
                    )
                } catch (e: Exception) {
                    logger.warn(e) { "Fundamental LLM parse error for $ticker" }
                    FundamentalReport(conclusion = "NEUTRAL", signalStrength = 0.0, reasoning = "Parse error")
                }
            }

        agentLogRepository.save(
            AgentLog(
                cycleId = cycleId,
                agentName = "Agent-2-Fundamental",
                ticker = ticker,
                action = report.conclusion,
                signalStrength = report.signalStrength,
                reasoning = report.reasoning,
                rawOutput = resp.content,
                latencyMs = System.currentTimeMillis() - start,
                tokensUsed = resp.tokensUsed,
                isCached = resp.fromCache,
                storageKey = resp.storageKey,
            ),
        )
        meterRegistry.counter("agent.fundamental.decision", Tags.of("action", report.conclusion, "ticker", ticker)).increment()
        return report
    }

    /** Формирует компактный дайджест новостей для промпта (лимит [NewsItem] приходит уже срезанным). */
    private fun renderNews(news: List<NewsItem>): String =
        if (news.isEmpty()) {
            "нет свежих новостей по эмитенту"
        } else {
            news.joinToString(separator = "\n") { item ->
                val date = item.publishedAt?.let { newsDateFormatter.format(it.atZone(java.time.ZoneId.of("Europe/Moscow"))) } ?: "н/д"
                "- [$date] ${item.title}${if (item.snippet.isNotBlank()) ": ${item.snippet}" else ""}"
            }
        }
}
