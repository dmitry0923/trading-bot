package com.trading.bot.agent

import com.trading.bot.config.LlmConfig
import com.trading.bot.config.TraceStorageConfig
import com.trading.bot.infrastructure.llm.JsonSchemaValidator
import com.trading.bot.infrastructure.llm.LlmResponse
import com.trading.bot.infrastructure.llm.PromptRegistry
import com.trading.bot.infrastructure.llm.PromptTemplate
import com.trading.bot.infrastructure.llm.ResilientLlmClient
import com.trading.bot.model.dto.MarketSnapshot
import com.trading.bot.model.entity.Candle
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * [TechnicalAnalysisAgent] — нестандартные входы: полная микроструктура книги,
 * доступный старший ТФ, неизвестная волатильность и нарушение контракта
 * валидатора (JSON-схема «пропустила» не-JSON → parse error, а не падение).
 */
class TechnicalAnalysisAgentBranchesTest {
    private val objectMapper = jacksonObjectMapper()

    private class StubLlmClient(
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
        override suspend fun complete(
            agent: String,
            ticker: String,
            prompt: PromptTemplate,
            variables: Map<String, Any>,
            fingerprint: String?,
            temperature: Double,
            cacheNamespace: String?,
        ): LlmResponse = response
    }

    private fun candle(
        price: BigDecimal,
        time: LocalDateTime,
    ): Candle =
        Candle(
            ticker = "SBER",
            timeframe = "MINUTE_10",
            openPrice = price,
            highPrice = price,
            lowPrice = price,
            closePrice = price,
            volume = 1000L,
            time = time,
        )

    private fun flatCandles(count: Int): List<Candle> =
        (0 until count).map { i ->
            candle(BigDecimal("100"), LocalDateTime.of(2026, 1, 5, 10, 0).plusMinutes(10L * i))
        }

    /** 40 часов истории (по 6 баров в час) — HOUR_1 набирает 40 баров, DAY_1 остаётся < 30. */
    private fun multiHourCandles(): List<Candle> =
        (0 until 240).map { i ->
            candle(BigDecimal("100"), LocalDateTime.of(2026, 1, 5, 10, 0).plusMinutes(10L * i))
        }

    private fun fullBookSnapshot(price: BigDecimal = BigDecimal("100")): MarketSnapshot =
        MarketSnapshot(
            ticker = "SBER",
            currentPrice = price,
            volume = 1000L,
            bid = BigDecimal("99.90"),
            ask = BigDecimal("100.10"),
            bidSize = 10L,
            askSize = 12L,
            microprice = BigDecimal("100.00"),
            obi = BigDecimal("0.21"),
        )

    private fun agent(
        content: String,
        validator: JsonSchemaValidator? = null,
        meter: SimpleMeterRegistry = SimpleMeterRegistry(),
    ): TechnicalAnalysisAgent {
        val llm = StubLlmClient(LlmResponse(content = content, tokensUsed = 12, storageKey = "trace/key"))
        val base = TechnicalAnalysisAgent(llm, mock(), mock(), mock(), meter, objectMapper)
        if (validator == null) return base
        return TechnicalAnalysisAgent(llm, mock(), mock(), mock(), meter, objectMapper, validator)
    }

    @Test
    fun `full orderbook and multi timeframe data enrich the prompt`() {
        val report =
            runBlocking {
                agent("""{"conclusion":"BULLISH","signalStrength":0.7,"reasoning":"book"}""")
                    .analyze("SBER", multiHourCandles(), fullBookSnapshot(), "c1")
            }

        assertEquals("BULLISH", report.conclusion)
        assertEquals(0.7, report.signalStrength)
    }

    @Test
    fun `zero price degrades volatility regime without failing`() {
        val report =
            runBlocking {
                agent("""{"conclusion":"NEUTRAL","signalStrength":0.1,"reasoning":"x"}""")
                    .analyze("SBER", flatCandles(30), fullBookSnapshot(price = BigDecimal.ZERO), "c1")
            }

        assertEquals("NEUTRAL", report.conclusion)
    }

    @Test
    fun `crossed zero book spread degrades to NA without division error`() {
        val snapshot = fullBookSnapshot().copy(bid = BigDecimal.ZERO, ask = BigDecimal.ZERO)

        val report =
            runBlocking {
                agent("""{"conclusion":"NEUTRAL","signalStrength":0.1,"reasoning":"x"}""")
                    .analyze("SBER", flatCandles(30), snapshot, "c1")
            }

        assertEquals("NEUTRAL", report.conclusion)
    }

    @Test
    fun `validator contract violation falls back to baseline instead of crashing`() {
        val validator = mock<JsonSchemaValidator>()
        runBlocking { whenever(validator.isValid(any(), any())).thenReturn(true) }

        val report =
            runBlocking {
                agent("this is not json at all", validator)
                    .analyze("SBER", flatCandles(30), fullBookSnapshot(), "c1")
            }

        assertTrue(report.conclusion != "this is not json at all")
        assertTrue(report.reasoning.isNotBlank())
    }

    @Test
    fun `unknown conclusion from a passing validator is normalised to NEUTRAL`() {
        val validator = mock<JsonSchemaValidator>()
        runBlocking { whenever(validator.isValid(any(), any())).thenReturn(true) }

        val report =
            runBlocking {
                agent("""{"conclusion":"MAYBE","signalStrength":0.4,"reasoning":"x"}""", validator)
                    .analyze("SBER", flatCandles(30), fullBookSnapshot(), "c1")
            }

        assertEquals("NEUTRAL", report.conclusion)
    }
}
