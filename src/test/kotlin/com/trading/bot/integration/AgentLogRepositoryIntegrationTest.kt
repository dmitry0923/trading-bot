package com.trading.bot.integration

import com.trading.bot.model.entity.AgentLog
import com.trading.bot.repository.AgentLogRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
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
}
