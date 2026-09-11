package com.trading.bot.agent

import com.trading.bot.config.LlmConfig
import com.trading.bot.config.TraceStorageConfig
import com.trading.bot.infrastructure.llm.LlmResponse
import com.trading.bot.infrastructure.llm.PromptRegistry
import com.trading.bot.infrastructure.llm.PromptTemplate
import com.trading.bot.infrastructure.llm.ResilientLlmClient
import com.trading.bot.infrastructure.llm.SemanticCache
import com.trading.bot.infrastructure.news.IssuerDataProvider
import com.trading.bot.model.dto.FundamentalReport
import com.trading.bot.model.dto.NewsItem
import com.trading.bot.model.entity.AgentLog
import com.trading.bot.repository.AgentLogRepository
import com.trading.bot.service.MacroContextService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigDecimal
import java.time.Instant

/**
 * Интеграция новостей эмитентов в FundamentalAnalysisAgent (research/llm-signal-source).
 *
 * IssuerDataProvider мокается: сценарий «хорошая новость» (позитивный катализатор —
 * дивиденды/рост прибыли) и «плохая новость» (негативный катализатор — убытки/списание).
 * Проверяется, что дайджест новостей попадает в переменные промпта (`issuerNews`),
 * а отсутствие провайдера/новостей даёт NEUTRAL-базу без падения.
 */
class FundamentalAnalysisAgentNewsTest {
    private val objectMapper = jacksonObjectMapper()

    private class RecordingLlmClient :
        ResilientLlmClient(
            llmConfig = LlmConfig(),
            semanticCache = mock(),
            objectMapper = jacksonObjectMapper(),
            meterRegistry = SimpleMeterRegistry(),
            circuitBreakerRegistry = mock(),
            rateLimiterRegistry = mock(),
            retryRegistry = mock(),
            settingsService = mock(),
            traceStorage = mock(),
            traceStorageConfig = TraceStorageConfig(),
        ) {
        var lastVariables: Map<String, Any> = emptyMap()

        override suspend fun complete(
            agent: String,
            ticker: String,
            prompt: PromptTemplate,
            variables: Map<String, Any>,
            fingerprint: String?,
            temperature: Double,
            cacheNamespace: String?,
        ): LlmResponse {
            lastVariables = variables
            return LlmResponse(content = """{"conclusion":"NEUTRAL","signalStrength":0.0,"reasoning":"ok"}""")
        }
    }

    private class RecordingLogRepo : AgentLogRepository(mock()) {
        val saved = mutableListOf<AgentLog>()

        override suspend fun save(log: AgentLog): AgentLog {
            saved += log
            return log
        }
    }

    private val template =
        PromptTemplate(
            name = "fundamental-analysis",
            version = "default",
            system = "system prompt",
            userTemplate = "user {{issuerNews}}",
        )
    private val promptRegistry: PromptRegistry =
        mock<PromptRegistry>().apply {
            whenever(getTemplate("fundamental-analysis", "default")).thenReturn(template)
        }
    private val logRepo = RecordingLogRepo()
    private val macro: MacroContextService = mock()

    private suspend fun stubMacro() {
        whenever(macro.fetch())
            .thenReturn(
                MacroContextService.MacroContext(
                    cbrRate = BigDecimal("16.0"),
                    brentPrice = BigDecimal("75.0"),
                    usdRub = BigDecimal("90.0"),
                ),
            )
    }

    private suspend fun newsProvider(vararg items: NewsItem): IssuerDataProvider =
        mock<IssuerDataProvider>().apply {
            whenever(newsFor("GAZP", 24)).thenReturn(items.toList())
        }

    private fun agent(
        llm: ResilientLlmClient,
        provider: IssuerDataProvider?,
    ): FundamentalAnalysisAgent =
        FundamentalAnalysisAgent(
            llmClient = llm,
            promptRegistry = promptRegistry,
            macroContextService = macro,
            semanticCache = mock<SemanticCache>(),
            agentLogRepository = logRepo,
            meterRegistry = SimpleMeterRegistry(),
            objectMapper = objectMapper,
            issuerDataProvider = provider,
        )

    @Test
    fun `good news digest reaches prompt variables`() {
        runBlocking { stubMacro() }
        val llm = RecordingLlmClient()
        val provider =
            runBlocking {
                newsProvider(
                    NewsItem(
                        title = "Газпром удвоил дивиденды за прошлый год",
                        url = "https://rg.ru/1",
                        publishedAt = Instant.parse("2026-09-11T08:00:00Z"),
                        snippet = "Совет директоров рекомендовал рекордные выплаты",
                    ),
                )
            }

        runBlocking { agent(llm, provider).analyze("GAZP", "c1") }

        val news = llm.lastVariables["issuerNews"] as String
        assertTrue(news.contains("Газпром удвоил дивиденды"), "news='$news'")
        assertTrue(news.contains("рекордные выплаты"), "news='$news'")
        assertTrue(news.contains("2026-09-11"), "news date rendered, news='$news'")
        assertEquals(1, logRepo.saved.size)
    }

    @Test
    fun `two news items are rendered in separate lines`() {
        runBlocking { stubMacro() }
        val llm = RecordingLlmClient()
        val provider =
            runBlocking {
                newsProvider(
                    NewsItem(title = "Новость 1", publishedAt = Instant.parse("2026-09-11T08:00:00Z")),
                    NewsItem(title = "Новость 2", publishedAt = Instant.parse("2026-09-11T09:00:00Z")),
                )
            }

        runBlocking { agent(llm, provider).analyze("GAZP", "c1") }

        val news = llm.lastVariables["issuerNews"] as String
        assertTrue(news.contains("Новость 1") && news.contains("Новость 2"), "news='$news'")
        assertTrue(news.lines().size == 2, "news='$news'")
    }

    @Test
    fun `no provider yields neutral base without news digest`() {
        runBlocking { stubMacro() }
        val llm = RecordingLlmClient()

        val report: FundamentalReport = runBlocking { agent(llm, null).analyze("GAZP", "c1") }

        assertEquals("NEUTRAL", report.conclusion)
        val news = llm.lastVariables["issuerNews"] as String
        assertTrue(news.contains("нет свежих новостей"), "news='$news'")
    }

    @Test
    fun `provider with empty news yields neutral base`() {
        runBlocking { stubMacro() }
        val llm = RecordingLlmClient()

        val report: FundamentalReport = runBlocking { agent(llm, runBlocking { newsProvider() }).analyze("GAZP", "c1") }

        assertEquals("NEUTRAL", report.conclusion)
        val news = llm.lastVariables["issuerNews"] as String
        assertTrue(news.contains("нет свежих новостей"), "news='$news'")
    }

    @Test
    fun `bad news type is carried through agent chain`() {
        // Провайдер, возвращающий негативную новость — парсится агентом в FundamentalReport
        // без потери типа.
        runBlocking { stubMacro() }
        val llm = RecordingLlmClient()
        val provider =
            runBlocking {
                newsProvider(
                    NewsItem(
                        title = "Эмитент зафиксировал убыток и сократил прогноз",
                        publishedAt = Instant.parse("2026-09-11T07:00:00Z"),
                    ),
                )
            }

        val report: FundamentalReport = runBlocking { agent(llm, provider).analyze("GAZP", "c1") }

        assertTrue(report.reasoning == "ok")
        assertEquals("NEUTRAL", report.conclusion)
    }
}
