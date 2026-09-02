package com.trading.bot.integration

import com.trading.bot.model.entity.AgentLog
import com.trading.bot.repository.AgentLogRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

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
}
