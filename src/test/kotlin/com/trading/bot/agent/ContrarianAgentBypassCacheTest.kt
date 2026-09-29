package com.trading.bot.agent

import com.trading.bot.config.LlmConfig
import com.trading.bot.config.TraceStorageConfig
import com.trading.bot.infrastructure.llm.LlmResponse
import com.trading.bot.infrastructure.llm.PromptRegistry
import com.trading.bot.infrastructure.llm.PromptTemplate
import com.trading.bot.infrastructure.llm.ResilientLlmClient
import com.trading.bot.infrastructure.llm.SemanticCache
import com.trading.bot.model.StrategyAction
import com.trading.bot.model.dto.FundamentalReport
import com.trading.bot.model.dto.MarketSnapshot
import com.trading.bot.model.dto.TechnicalReport
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * `bypassCache` у [ContrarianAgent] (research-LLM-veto, docs/20 §10).
 *
 * Проверяется ровно то, ради чего параметр добавлен: при `bypassCache = true`
 * агент НЕ спрашивает семантический кэш и передаёт в LLM `fingerprint = null`.
 * Иначе исследовательский прогон получает вердикт, посчитанный для другого бара
 * (отпечаток кэша грубый: цена до 1 знака, бакет RSI×10, `session` от
 * `LocalTime.now()`), а повторный прогон на тех же данных даёт другой результат.
 */
class ContrarianAgentBypassCacheTest {
    private val objectMapper = jacksonObjectMapper()
    private val semanticCache: SemanticCache = mock()
    private val promptRegistry: PromptRegistry = mock()

    /** Клиент, который запоминает переданный fingerprint вместо похода в сеть. */
    private class RecordingLlmClient(
        private val response: LlmResponse,
    ) : ResilientLlmClient(
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
        var lastFingerprint: String? = "not-called"

        override suspend fun complete(
            agent: String,
            ticker: String,
            prompt: PromptTemplate,
            variables: Map<String, Any>,
            fingerprint: String?,
            temperature: Double,
            cacheNamespace: String?,
        ): LlmResponse {
            lastFingerprint = fingerprint
            return response
        }
    }

    private fun agent(llm: ResilientLlmClient): ContrarianAgent =
        ContrarianAgent(
            llmClient = llm,
            promptRegistry = promptRegistry,
            semanticCache = semanticCache,
            agentLogRepository = mock(),
            meterRegistry = SimpleMeterRegistry(),
            objectMapper = objectMapper,
        )

    private val snapshot =
        MarketSnapshot(
            ticker = "CNYRUBF",
            currentPrice = BigDecimal("92.5"),
            volume = 1000L,
            timestamp = LocalDateTime.of(2026, 9, 28, 11, 0).atZone(ZoneId.systemDefault()).toInstant(),
        )
    private val draft = StrategyAgent.Draft(StrategyAction.BUY, BigDecimal("92.5"), 0.7, "deterministic signal")
    private val tech =
        TechnicalReport(trend = "FLAT", rsi = 48.5, atr = 0.42, conclusion = "NEUTRAL", signalStrength = 0.7, reasoning = "up")
    private val fund = FundamentalReport(conclusion = "NEUTRAL", signalStrength = 0.0, reasoning = "no fundamental data")
    private val ok = """{"isValid":true,"riskLevel":"LOW","critique":"ok","signalStrength":0.6}"""

    private fun stubPrompt() {
        runBlocking {
            whenever(promptRegistry.getTemplate("contrarian", PromptRegistry.DEFAULT_VERSION))
                .thenReturn(PromptTemplate(name = "contrarian", version = "v", system = "s", userTemplate = "u"))
        }
    }

    @Test
    fun `bypassCache не спрашивает семантический кэш и передаёт null fingerprint`() {
        stubPrompt()
        val llm = RecordingLlmClient(LlmResponse(content = ok))

        val report =
            runBlocking {
                agent(llm).challenge(
                    draft = draft,
                    tech = tech,
                    fund = fund,
                    snapshot = snapshot,
                    cycleId = "c1",
                    bypassCache = true,
                )
            }

        assertEquals(true, report.isValid, "валидный ответ должен распарситься")
        assertNull(llm.lastFingerprint, "bypassCache=true ⇒ fingerprint в LLM не передаётся")
        // Реальные значения вместо any(): у fingerprint 8 параметров (4 c default'ами),
        // и matcher-стабы рассинхронизируются с числом аргументов.
        verify(semanticCache, never()).fingerprint(BigDecimal("92.5"), 48.5, "FLAT", "contrarian", 0.0)
    }

    @Test
    fun `без bypassCache отпечаток кэша считается и передаётся в LLM`() {
        stubPrompt()
        whenever(semanticCache.fingerprint(BigDecimal("92.5"), 48.5, "FLAT", "contrarian", 0.0)).thenReturn("fp-1")
        val llm = RecordingLlmClient(LlmResponse(content = ok))

        val report =
            runBlocking {
                agent(llm).challenge(
                    draft = draft,
                    tech = tech,
                    fund = fund,
                    snapshot = snapshot,
                    cycleId = "c1",
                    bypassCache = false,
                )
            }

        assertEquals(true, report.isValid)
        assertEquals("fp-1", llm.lastFingerprint, "без bypassCache кэш остаётся источником fingerprint")
    }
}
