package com.trading.bot.backtest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Юнит-тесты детерминированной инъекции таймаутов LLM (research).
 *
 * Ключевые свойства, ради которых механизм и сделан:
 * - `rate = 0.0` → таймаут не выбирается НИКОГДА (поведение прогонов не меняется);
 * - `rate = 1.0` → таймаут выбирается ВСЕГДА (для доказательства fail-closed);
 * - карта инъекций детерминирована по (ticker, индекс бара) и не зависит от
 *   порядка вызовов, поэтому WFA-фолды остаются сопоставимыми, а rate меняет
 *   порог, а не перетасовывает выборку;
 * - фактическая доля на реальной сетке индексов близка к заданной (5% → ~5%,
 *   иначе «процент таймаутов» в чек-листе research не был бы измерим);
 * - rate вне 0.0…1.0 — ошибка конфигурации, а не молчаливое «выключено».
 */
class LlmTimeoutInjectionTest {
    @Test
    fun `rate zero never injects`() {
        val injection = LlmTimeoutInjection.from(0.0)
        assertFalse(injection.enabled)
        (0 until 2000).forEach { i ->
            assertFalse(injection.shouldTimeout("CNYRUBF", i), "index=$i must not time out at rate 0")
        }
    }

    @Test
    fun `rate one always injects`() {
        val injection = LlmTimeoutInjection.from(1.0)
        assertTrue(injection.enabled)
        (0 until 500).forEach { i ->
            assertTrue(injection.shouldTimeout("CNYRUBF", i), "index=$i must time out at rate 1")
        }
    }

    @Test
    fun `injection map is deterministic for ticker and index`() {
        val injection = LlmTimeoutInjection.from(0.05)
        val first = (0 until 500).map { injection.shouldTimeout("CNYRUBF", it) }
        val second = (0 until 500).map { injection.shouldTimeout("CNYRUBF", it) }
        assertEquals(first, second, "same (ticker,index) must give same decision")
    }

    @Test
    fun `different tickers use different injection maps`() {
        val injection = LlmTimeoutInjection.from(0.05)
        val cny = (0 until 500).map { injection.shouldTimeout("CNYRUBF", it) }
        val imoex = (0 until 500).map { injection.shouldTimeout("IMOEXF", it) }
        assertFalse(cny == imoex, "injection map must depend on ticker")
    }

    @Test
    fun `observed share approximates configured rate`() {
        val n = 20_000
        val rate = 0.05
        val injection = LlmTimeoutInjection.from(rate)
        val hits = (0 until n).count { injection.shouldTimeout("CNYRUBF", it) }
        val observed = hits.toDouble() / n
        assertTrue(observed > rate * 0.9, "observed $observed below tolerance for rate $rate")
        assertTrue(observed < rate * 1.1, "observed $observed above tolerance for rate $rate")
    }

    @Test
    fun `higher rate is monotone in injected bars`() {
        val n = 20_000
        val low = LlmTimeoutInjection.from(0.02)
        val mid = LlmTimeoutInjection.from(0.10)
        val high = LlmTimeoutInjection.from(0.40)
        val lowHits = (0 until n).map { low.shouldTimeout("CNYRUBF", it) }
        val midHits = (0 until n).map { mid.shouldTimeout("CNYRUBF", it) }
        val highHits = (0 until n).map { high.shouldTimeout("CNYRUBF", it) }
        assertTrue(highHits.count { it } > midHits.count { it }, "rate 0.40 must inject more than 0.10")
        assertTrue(midHits.count { it } > lowHits.count { it }, "rate 0.10 must inject more than 0.02")
    }

    @Test
    fun `rate outside range is a configuration error`() {
        assertThrows(IllegalArgumentException::class.java) { LlmTimeoutInjection.from(1.5) }
        assertThrows(IllegalArgumentException::class.java) { LlmTimeoutInjection.from(-0.1) }
    }
}
