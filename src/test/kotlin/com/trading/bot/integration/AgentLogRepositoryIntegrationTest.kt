package com.trading.bot.integration

import com.trading.bot.model.entity.AgentLog
import com.trading.bot.repository.AgentLogRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDateTime

/**
 * Интеграционные тесты батч-выборки силы сигнала стратега (roadmap 13.24, FIND-MECH-1):
 * калибровочная выборка должна быть детерминированной и не содержать мусора.
 *
 * - фильтр по тикеру: в одном цикле стратег логируется на каждый (ticker, timeframe),
 *   без фильтра в map могла попасть сила сигнала ДРУГОГО тикера того же цикла;
 * - NULL-сила исключается (не превращается в 0.0);
 * - несколько строк на cycleId -> MAX (детерминированно, вместо «последней строки БД»).
 */
@Tag("integration")
class AgentLogRepositoryIntegrationTest : AbstractTestContainerTest() {
    @Autowired
    lateinit var repo: AgentLogRepository

    private fun strategistLog(
        cycleId: String,
        ticker: String,
        action: String,
        strength: Double?,
    ) = AgentLog(cycleId = cycleId, agentName = "Agent-3-Strategist", ticker = ticker, action = action, signalStrength = strength)

    @Test
    fun `signal strengths are ticker-filtered, null-excluded and aggregated deterministically`() {
        val cycleA = "INT_ALG_CYCLE_A"
        val cycleB = "INT_ALG_CYCLE_B"
        val cycleC = "INT_ALG_CYCLE_C"

        runBlocking {
            repo.save(strategistLog(cycleA, "SBER", "BUY", 0.70))
            repo.save(strategistLog(cycleA, "GAZP", "BUY", 0.99))
            repo.save(strategistLog(cycleA, "SBER", "BUY", 0.85))
            repo.save(strategistLog(cycleB, "SBER", "HOLD", null))
            repo.save(strategistLog(cycleB, "SBER", "BUY", 0.80))
            repo.save(strategistLog(cycleC, "GAZP", "BUY", 0.95))

            val result = repo.findStrategySignalStrengthByCycleIds("SBER", listOf(cycleA, cycleB, cycleC))

            assertEquals(mapOf(cycleA to 0.85, cycleB to 0.80), result)
        }
    }

    @Test
    fun `strategy decision is resolved per ticker inside the same cycle`() {
        // Один цикл = все тикеры × таймфреймы: без фильтра по тикеру решение бралось
        // у последней записанной пары и ML-датасет размечивался чужим сигналом (скан-6 P2).
        val cycle = "INT_DECISION_CYCLE"
        runBlocking {
            repo.save(strategistLog(cycle, "SBER", "BUY", 0.70))
            Thread.sleep(20)
            repo.save(strategistLog(cycle, "CNYRUBF", "SELL", 0.55))

            assertEquals("BUY", repo.findStrategyDecision(cycle, "SBER")?.action)
            assertEquals("SELL", repo.findStrategyDecision(cycle, "CNYRUBF")?.action)
            // Без тикера поведение прежнее (последняя запись цикла) — обратная совместимость.
            assertEquals("SELL", repo.findStrategyDecision(cycle)?.action)
        }
    }

    @Test
    fun `empty or blank cycle ids return empty map`() {
        runBlocking {
            assertTrue(repo.findStrategySignalStrengthByCycleIds("SBER", emptyList()).isEmpty())
            assertTrue(repo.findStrategySignalStrengthByCycleIds("SBER", listOf("  ", "  ")).isEmpty())
        }
    }

    @Test
    fun `findByCycleId returns all agents of cycle in chronological order`() {
        val cycle = "INT_LINEAGE_CYCLE"
        val t0 = LocalDateTime.of(2026, 9, 10, 10, 0, 0, 0)
        runBlocking {
            repo.save(
                AgentLog(
                    cycleId = cycle,
                    agentName = "Agent-1-Technical",
                    ticker = "CNYRUBF",
                    action = "BUY",
                    createdAt = t0,
                ),
            )
            repo.save(
                AgentLog(
                    cycleId = cycle,
                    agentName = "Agent-3-Strategist",
                    ticker = "CNYRUBF",
                    action = "BUY",
                    signalStrength = 0.80,
                    createdAt = t0.plusSeconds(1),
                ),
            )
            repo.save(
                AgentLog(
                    cycleId = cycle,
                    agentName = "Agent-6-Advisor",
                    ticker = "CNYRUBF",
                    action = "AGREE",
                    signalStrength = 0.05,
                    createdAt = t0.plusSeconds(2),
                ),
            )
            repo.save(
                AgentLog(
                    cycleId = "INT_OTHER_CYCLE",
                    agentName = "Agent-1-Technical",
                    ticker = "CNYRUBF",
                    action = "BUY",
                    createdAt = t0,
                ),
            )

            val result = repo.findByCycleId(cycle)

            assertEquals(3, result.size)
            assertEquals(
                listOf("Agent-1-Technical", "Agent-3-Strategist", "Agent-6-Advisor"),
                result.map { it.agentName },
            )
            assertTrue(result.all { it.cycleId == cycle })
        }
    }

    @Test
    fun `filtered lookup applies optional filters and ignores blank values`() {
        runBlocking {
            val suffix = System.nanoTime().toString().takeLast(6)
            val tickerA = "FA$suffix"
            val tickerB = "FB$suffix"
            val tech = "Tech$suffix"
            val strat = "Strat$suffix"
            val cycle = "INT_FILTERED_CYCLE"
            repo.save(AgentLog(cycleId = cycle, agentName = tech, ticker = tickerA, action = "BUY", latencyMs = 0, tokensUsed = 0))
            repo.save(AgentLog(cycleId = cycle, agentName = strat, ticker = tickerA, action = "BUY"))
            repo.save(AgentLog(cycleId = cycle, agentName = tech, ticker = tickerB, action = "SELL"))

            val byTicker = repo.findFiltered(tickerA, null, 50)
            assertEquals(2, byTicker.size)
            assertTrue(byTicker.all { it.ticker == tickerA })

            val byAgent = repo.findFiltered(null, tech, 50)
            assertEquals(2, byAgent.size)
            assertTrue(byAgent.all { it.agentName == tech })

            val byBoth = repo.findFiltered(tickerB, tech, 50)
            assertEquals(1, byBoth.size)
            assertEquals("SELL", byBoth.single().action)

            // Пустые строки — фильтр не применяется, а limit клампится в допустимый диапазон.
            assertTrue(repo.findFiltered("  ", "", 10_000).isNotEmpty())
            assertEquals(1, repo.findFiltered(tickerA, null, 0).size)

            // Нулевые latency/tokens читаются как «неизвестно» (null), а не как 0.
            val technical = repo.findFiltered(tickerA, tech, 50).single()
            assertNull(technical.latencyMs)
            assertNull(technical.tokensUsed)
        }
    }
}
