package com.trading.bot.performance

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.trading.bot.config.LlmConfig
import com.trading.bot.config.TraceStorageConfig
import com.trading.bot.infrastructure.llm.PromptTemplate
import com.trading.bot.infrastructure.llm.ResilientLlmClient
import com.trading.bot.model.entity.BotSettings
import com.trading.bot.service.SettingsService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Нагрузочное тестирование LLM-контура (roadmap 13.3 п.4).
 *
 * Воспроизводит бюджет латентности и стоимости стратегического цикла:
 * до 100 тикеров × 6 агентов × 2 LLM-вызова = до 1200 LLM-запросов за цикл.
 * Реальный [ResilientLlmClient] ходит в локальный [FakeLlmServer] (JDK HttpServer),
 * поэтому измеряются настоящие механизмы:
 *  - очередь [com.trading.bot.infrastructure.llm.LlmRequestQueue] и ограничение
 *    параллелизма `llm.queue-concurrency`;
 *  - HTTP-слой (WebClient), таймауты, метрики `llm.latency` / `llm.tokens.used`;
 *  - семантический кэш выключен (fingerprint=null) — пиковый режим без попаданий.
 *
 * Бюджеты:
 *  - латентность: elapsed ≤ ceil(calls / concurrency) × simLatency × SLACK + MARGIN.
 *    При последовательной обработке цикл в `concurrency` раз дольше — тест падает;
 *  - стоимость: estimatedCost = totalTokens × pricePer1K / 1000 ≤ budget;
 *  - корректность под нагрузкой: 0 fallback-ответов, все вызовы завершены.
 *
 * Полномасштабный прогон (100 тикеров, ~1200 запросов) — по запросу:
 * `./gradlew.bat test --tests "com.trading.bot.performance.LlmCycleLoadTest" -Dload.full=true`
 */
class LlmCycleLoadTest {
    @Test
    fun `cycle of 8 tickers x 6 agents x 2 calls stays within latency and cost budget`() =
        runBlocking {
            executeAndVerify(
                tickers = 8,
                agentsPerTicker = 6,
                callsPerAgent = 2,
                simLatencyMs = 15L,
                queueConcurrency = 4,
                writeReport = false,
            )
        }

    @Test
    @EnabledIfSystemProperty(named = "load.full", matches = "true")
    fun `full scale 100 tickers x 6 agents x 2 calls stays within budget`() =
        runBlocking {
            executeAndVerify(
                tickers = 100,
                agentsPerTicker = 6,
                callsPerAgent = 2,
                simLatencyMs = 200L,
                queueConcurrency = 8,
                writeReport = true,
            )
        }

    private suspend fun executeAndVerify(
        tickers: Int,
        agentsPerTicker: Int,
        callsPerAgent: Int,
        simLatencyMs: Long,
        queueConcurrency: Int,
        writeReport: Boolean,
    ) {
        val expectedCalls = tickers * agentsPerTicker * callsPerAgent

        FakeLlmServer(latencyMs = simLatencyMs, tokensPerCall = TOKENS_PER_CALL).use { server ->
            val registry = SimpleMeterRegistry()
            val client = buildClient(server.baseUrl, llmQueueConcurrency = queueConcurrency, registry = registry)
            // Прогрев: reactor-netty и классы загружаются на первых запросах (одноразовая
            // стоимость ~1.5с). Бюджет цикла измеряем в установившемся режиме — так, как
            // живёт реальный цикл на длинной серии.
            warmUp(client)
            val baselineTokens = registry.find("llm.tokens.used").counters().sumOf { it.count() }
            val baselineLatencyCount = registry.find("llm.latency").timers().sumOf { it.count() }
            val report = runCycle(client, tickers, agentsPerTicker, callsPerAgent)

            assertEquals(expectedCalls.toLong(), report.calls, "all LLM calls must complete")
            assertEquals(0L, report.fallbacks, "no LLM call may fall back under load")
            assertEquals(
                expectedCalls.toLong() * TOKENS_PER_CALL,
                report.totalTokens,
                "token accounting must match the simulated usage",
            )

            val theoreticalMs = report.calls.toDouble() / queueConcurrency * simLatencyMs
            val boundMs = (theoreticalMs * LATENCY_SLACK_FACTOR).toLong() + LATENCY_MARGIN_MS
            assertTrue(
                report.elapsedMs <= boundMs,
                "cycle must finish within the latency budget: elapsed=${report.elapsedMs}ms, " +
                    "theoretical=${theoreticalMs.toLong()}ms, bound=${boundMs}ms " +
                    "(sequential processing would take ~${report.calls * simLatencyMs}ms)",
            )

            val costBudgetRub =
                BigDecimal(expectedCalls * TOKENS_PER_CALL)
                    .multiply(PRICE_PER_1K_TOKENS_RUB)
                    .divide(BigDecimal("1000"), 4, RoundingMode.HALF_UP)
            assertTrue(
                report.estimatedCostRub <= costBudgetRub,
                "cost must stay within the budget: estimated=${report.estimatedCostRub} RUB, budget=$costBudgetRub RUB",
            )

            val minThroughput = queueConcurrency.toDouble() / simLatencyMs * 1000.0 * THROUGHPUT_MIN_FRACTION
            assertTrue(
                report.throughputPerSec >= minThroughput,
                "throughput must approach the queue concurrency limit: actual=${report.throughputPerSec} calls/s, " +
                    "min=$minThroughput calls/s",
            )

            val tokenMeterSum = registry.find("llm.tokens.used").counters().sumOf { it.count() } - baselineTokens
            assertEquals(
                report.totalTokens.toDouble(),
                tokenMeterSum,
                1.0,
                "llm.tokens.used meter must account every call",
            )
            val latencyTimerCount = registry.find("llm.latency").timers().sumOf { it.count() } - baselineLatencyCount
            assertEquals(report.calls, latencyTimerCount, "llm.latency timer must record every call")

            if (writeReport) {
                writeReport(report, simLatencyMs, queueConcurrency)
            }
        }
    }

    /**
     * Прогрев соединения и классов тем же клиентом, что будет измеряться:
     * метрики `llm.*` затем сравниваются дельтой, поэтому прогревочные вызовы
     * не влияют на проверки учёта токенов и таймера.
     */
    private suspend fun warmUp(client: ResilientLlmClient) {
        repeat(WARMUP_CALLS) { i ->
            client.complete(
                agent = "warmup",
                ticker = "WARMUP",
                prompt = PROMPT,
                variables = mapOf("ticker" to "WARMUP"),
                fingerprint = null,
            )
            if (i % 2 == 1) delay(2L)
        }
    }

    private fun buildClient(
        baseUrl: String,
        llmQueueConcurrency: Int,
        registry: SimpleMeterRegistry,
    ): ResilientLlmClient {
        val llmConfig =
            LlmConfig().apply {
                routerAiBaseUrl = baseUrl
                apiKey = "load-test-key"
                timeoutSec = 10
                queueCapacity = 4096
                queueConcurrency = llmQueueConcurrency
                semanticCacheEnabled = false
                circuitBreakerEnabled = false
                rateLimiterEnabled = false
                retryEnabled = false
            }
        val settingsService = mock<SettingsService>()
        whenever(settingsService.getSettings()).thenReturn(BotSettings(llmApiKey = "load-test-key"))
        return ResilientLlmClient(
            llmConfig = llmConfig,
            semanticCache = mock(),
            objectMapper = jacksonObjectMapper(),
            meterRegistry = registry,
            circuitBreakerRegistry = mock(),
            rateLimiterRegistry = mock(),
            retryRegistry = mock(),
            settingsService = settingsService,
            traceStorage = mock(),
            traceStorageConfig = TraceStorageConfig().apply { enabled = false },
        )
    }

    /**
     * Воспроизводит топологию [com.trading.bot.service.StrategyService.executeCycle]:
     * один coroutine на тикер, внутри тикера — последовательная цепочка агентов
     * (по [callsPerAgent] LLM-вызовов на агента).
     */
    private suspend fun runCycle(
        client: ResilientLlmClient,
        tickers: Int,
        agentsPerTicker: Int,
        callsPerAgent: Int,
    ): CycleReport {
        val start = System.nanoTime()
        val fallbacks = AtomicLong(0)
        val tokens = AtomicLong(0)
        val calls = AtomicLong(0)
        val latencies = Collections.synchronizedList(mutableListOf<Long>())

        coroutineScope {
            (1..tickers)
                .map { t ->
                    async(Dispatchers.Default) {
                        val ticker = "TICKER${t.toString().padStart(3, '0')}"
                        repeat(agentsPerTicker) { a ->
                            val agent = AGENTS[a % AGENTS.size]
                            repeat(callsPerAgent) {
                                val resp =
                                    client.complete(
                                        agent = agent,
                                        ticker = ticker,
                                        prompt = PROMPT,
                                        variables = mapOf("ticker" to ticker),
                                        fingerprint = null,
                                    )
                                if (resp.isFallback) fallbacks.incrementAndGet()
                                tokens.addAndGet(resp.tokensUsed.toLong())
                                calls.incrementAndGet()
                                latencies.add(resp.latencyMs)
                            }
                        }
                    }
                }.awaitAll()
        }

        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        return CycleReport(
            tickers = tickers,
            agentsPerTicker = agentsPerTicker,
            callsPerAgent = callsPerAgent,
            calls = calls.get(),
            elapsedMs = elapsedMs,
            fallbacks = fallbacks.get(),
            totalTokens = tokens.get(),
            latenciesMs = latencies.toList(),
        )
    }

    private fun writeReport(
        report: CycleReport,
        simLatencyMs: Long,
        queueConcurrency: Int,
    ) {
        val reportText =
            buildString {
                appendLine("=== LLM Load Test Report (roadmap 13.3 p.4) ===")
                appendLine("Tickers x agents x calls: ${report.tickers} x ${report.agentsPerTicker} x ${report.callsPerAgent}")
                appendLine("Total LLM calls: ${report.calls}")
                appendLine("Queue concurrency: $queueConcurrency")
                appendLine("Simulated LLM latency: ${simLatencyMs}ms")
                appendLine("Cycle elapsed: ${report.elapsedMs}ms")
                appendLine("Avg LLM latency: ${report.avgLatencyMs}ms")
                appendLine("P95 LLM latency: ${report.p95LatencyMs}ms")
                appendLine("Throughput: ${"%.1f".format(report.throughputPerSec)} calls/s")
                appendLine("Tokens used: ${report.totalTokens}")
                appendLine("Estimated cost: ${report.estimatedCostRub} RUB")
                appendLine("Fallbacks: ${report.fallbacks}")
            }
        println(reportText)
        try {
            val dir = Path.of("build", "reports", "load")
            Files.createDirectories(dir)
            Files.writeString(dir.resolve("load-report.txt"), reportText)
        } catch (e: Exception) {
            println("WARN: failed to persist load report: ${e.message}")
        }
    }

    private class FakeLlmServer(
        private val latencyMs: Long,
        private val tokensPerCall: Int,
    ) : AutoCloseable {
        private val objectMapper = jacksonObjectMapper()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = AtomicLong(0)

        val baseUrl: String
            get() = "http://127.0.0.1:${server.address.port}/v1"

        init {
            server.executor = Executors.newVirtualThreadPerTaskExecutor()
            server.createContext("/v1/chat/completions") { handle(it) }
            server.start()
        }

        private fun handle(exchange: HttpExchange) {
            try {
                exchange.requestBody.readBytes()
                requests.incrementAndGet()
                Thread.sleep(latencyMs)
                val body =
                    objectMapper.writeValueAsString(
                        mapOf(
                            "choices" to
                                listOf(
                                    mapOf(
                                        "message" to
                                            mapOf(
                                                "content" to """{"conclusion":"BULLISH","signalStrength":0.7,"reasoning":"load-test"}""",
                                            ),
                                    ),
                                ),
                            "usage" to mapOf("total_tokens" to tokensPerCall),
                        ),
                    )
                val bytes = body.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } catch (e: Exception) {
                // Клиент мог закрыть соединение — запрос уже учтён, ответ необязателен.
            } finally {
                exchange.close()
            }
        }

        override fun close() {
            server.stop(0)
        }
    }

    private data class CycleReport(
        val tickers: Int,
        val agentsPerTicker: Int,
        val callsPerAgent: Int,
        val calls: Long,
        val elapsedMs: Long,
        val fallbacks: Long,
        val totalTokens: Long,
        val latenciesMs: List<Long>,
    ) {
        val avgLatencyMs: Double
            get() = latenciesMs.average()

        val p95LatencyMs: Long
            get() = latenciesMs.sorted()[((latenciesMs.size - 1) * 0.95).toInt()]

        val throughputPerSec: Double
            get() = calls.toDouble() / (elapsedMs / 1000.0)

        val estimatedCostRub: BigDecimal
            get() =
                BigDecimal(totalTokens)
                    .multiply(PRICE_PER_1K_TOKENS_RUB)
                    .divide(BigDecimal("1000"), 4, RoundingMode.HALF_UP)
    }

    private companion object {
        private val AGENTS = listOf("technical", "fundamental", "strategy", "contrarian", "arbitrator", "advisor")

        private val PROMPT =
            PromptTemplate(
                name = "load-test",
                version = "default",
                system = "You are a trading analyst. Analyze {{ticker}}.",
                userTemplate = "Produce a JSON decision for {{ticker}}.",
            )

        /** Симулируемое потребление токенов на один вызов (input≈180 + output≈55). */
        private const val TOKENS_PER_CALL = 235

        /** Справочная цена 1K токенов, RUB (kimi-k3). */
        private val PRICE_PER_1K_TOKENS_RUB = BigDecimal("0.02")

        /** Допуск на накладные расходы (GC, реактор, очередь) поверх теоретической латентности. */
        private const val LATENCY_SLACK_FACTOR = 4.0

        private const val LATENCY_MARGIN_MS = 500L

        /** Минимальная достижимая доля пропускной способности очереди (concurrency / latency). */
        private const val THROUGHPUT_MIN_FRACTION = 0.2

        /** Прогревочные вызовы перед замером (реактор/netty и классы грузятся на первых запросах). */
        private const val WARMUP_CALLS = 6
    }
}
